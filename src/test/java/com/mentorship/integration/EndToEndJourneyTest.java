package com.mentorship.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.mentorship.dto.ChatMessageRequest;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Notification;
import com.mentorship.entity.OutboxStatus;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.ChatMessageRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.NotificationRepository;
import com.mentorship.repository.OutboxEventRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.scheduler.SessionReminderJob;

/**
 * Phase 17 acceptance criteria, executed as one continuous journey over real HTTP and a real
 * WebSocket against the running application:
 *
 * <pre>
 * register -&gt; login -&gt; JWT -&gt; mentor discovery -&gt; availability -&gt; booking
 *          -&gt; concurrency protection -&gt; notification -&gt; session reminder -&gt; chat
 * </pre>
 *
 * <p>This does not replace the focused suites - Phase 7's {@code BookingConcurrencyTest} still
 * proves double-booking protection under genuinely parallel threads, and each phase keeps its own
 * tests. What this adds is proof that the phases compose: that a token minted at step 2 opens the
 * booking endpoint at step 6, and that the session created by that booking is the one the chat at
 * step 9 attaches to.
 *
 * <p>Requires PostgreSQL, Redis and RabbitMQ: docker compose up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
		"app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256",
		"app.scheduler.reminder-lead-time=15m" })
class EndToEndJourneyTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	@LocalServerPort
	private int port;

	private RestTemplate rest;

	@Autowired
	private SessionReminderJob sessionReminderJob;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MentorProfileRepository mentorProfileRepository;

	@Autowired
	private AvailabilityRepository availabilityRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SessionRepository sessionRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ChatMessageRepository chatMessageRepository;

	private TransactionTemplate transactionTemplate;

	private WebSocketStompClient stompClient;

	private String mentorEmail;

	private String candidateEmail;

	private String rivalEmail;

	private Long mentorId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		// Boot 4 no longer puts TestRestTemplate on the default test classpath, and pulling in an
		// extra module for one test is not worth it. A plain RestTemplate with a non-throwing error
		// handler does the same job, and this journey has to assert 401/403/409 as ordinary results.
		rest = new RestTemplate();
		rest.setErrorHandler(new DefaultResponseErrorHandler() {

			@Override
			public boolean hasError(ClientHttpResponse response) {
				return false;
			}

		});

		stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		stompClient.setMessageConverter(new JacksonJsonMessageConverter());

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "e2e.mentor." + suffix + "@example.com";
		candidateEmail = "e2e.candidate." + suffix + "@example.com";
		rivalEmail = "e2e.rival." + suffix + "@example.com";
	}

	@AfterEach
	void tearDown() {
		if (stompClient != null) {
			stompClient.stop();
		}
		if (mentorId == null) {
			return;
		}
		transactionTemplate.executeWithoutResult(status -> {
			bookingRepository.findAll().stream()
					.filter(booking -> mentorId.equals(booking.getMentor().getId()))
					.forEach(booking -> {
						outboxEventRepository.findByEventId("BOOKING_CREATED:" + booking.getId())
								.ifPresent(outboxEventRepository::delete);
						outboxEventRepository.findByEventId("BOOKING_CANCELLED:" + booking.getId())
								.ifPresent(outboxEventRepository::delete);
						notificationRepository.deleteAll(
								notificationRepository.findByEventId("BOOKING_CREATED:" + booking.getId()));
						notificationRepository.deleteAll(
								notificationRepository.findByEventId("BOOKING_CANCELLED:" + booking.getId()));
						sessionRepository.findByBookingId(booking.getId()).ifPresent(session -> {
							outboxEventRepository.findByEventId("SESSION_REMINDER:" + session.getId())
									.ifPresent(outboxEventRepository::delete);
							notificationRepository.deleteAll(
									notificationRepository.findByEventId("SESSION_REMINDER:" + session.getId()));
							chatMessageRepository.deleteAll(
									chatMessageRepository.findBySessionIdOrderBySentAtAsc(session.getId()));
							sessionRepository.delete(session);
						});
						bookingRepository.delete(booking);
					});
			availabilityRepository.findByMentorProfileUserIdOrderByStartTimeAsc(mentorId)
					.forEach(availabilityRepository::delete);
			mentorProfileRepository.findByUserEmail(mentorEmail).ifPresent(mentorProfileRepository::delete);
			userRepository.findByEmail(mentorEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(candidateEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(rivalEmail).ifPresent(userRepository::delete);
		});
	}

	@Test
	@DisplayName("The complete documented workflow, end to end")
	void theCompleteMentorshipWorkflow() throws Exception {
		// ---------- 1. Registration ----------
		String mentorToken = register("E2E Mentor", mentorEmail, "MENTOR");
		String candidateToken = register("E2E Candidate", candidateEmail, "CANDIDATE");
		register("E2E Rival", rivalEmail, "CANDIDATE");
		assertThat(mentorToken).isNotBlank();

		// ---------- 2. Login issues a working token ----------
		String loginToken = login(candidateEmail);
		assertThat(loginToken).isNotBlank();

		// ---------- 3. JWT authenticates a protected call ----------
		ResponseEntity<Map> me = rest.exchange(url("/api/auth/me"), HttpMethod.GET, authorized(loginToken), Map.class);
		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(me.getBody()).containsEntry("email", candidateEmail);

		// An unauthenticated call to the same endpoint is refused.
		assertThat(rest.exchange(url("/api/auth/me"), HttpMethod.GET, null, Map.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		// ---------- 4. Mentor publishes a profile ----------
		ResponseEntity<Map> profile = rest.exchange(url("/api/mentors/profile"), HttpMethod.POST,
				authorized(mentorToken, """
						{"industry":"FinTech","expertise":"Java","experience":8,"bio":"End to end"}"""), Map.class);
		assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		mentorId = ((Number) profile.getBody().get("mentorId")).longValue();

		// ---------- 5. Candidate discovers the mentor ----------
		ResponseEntity<Map> search = rest.exchange(url("/api/mentors?industry=FinTech&expertise=Java"),
				HttpMethod.GET, authorized(loginToken), Map.class);
		assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(search.getBody()).containsKey("content");

		ResponseEntity<Map> viewed = rest.exchange(url("/api/mentors/") + mentorId, HttpMethod.GET,
				authorized(loginToken), Map.class);
		assertThat(viewed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(viewed.getBody()).containsEntry("industry", "FinTech");

		// ---------- 6. Mentor opens a slot, candidate sees it ----------
		// Starting soon, so the same session also exercises the reminder job below.
		Instant start = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
		Instant end = start.plus(1, ChronoUnit.HOURS);

		ResponseEntity<Map> slot = rest.exchange(url("/api/availability"), HttpMethod.POST,
				authorized(mentorToken, """
						{"startTime":"%s","endTime":"%s"}""".formatted(start, end)), Map.class);
		assertThat(slot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		Long slotId = ((Number) slot.getBody().get("id")).longValue();

		ResponseEntity<List> published = rest.exchange(url("/api/mentors/") + mentorId + "/availability",
				HttpMethod.GET, authorized(loginToken), List.class);
		assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(published.getBody()).hasSize(1);

		// ---------- 7. Booking ----------
		ResponseEntity<Map> booking = rest.exchange(url("/api/bookings"), HttpMethod.POST,
				authorized(loginToken, """
						{"availabilityId":%d}""".formatted(slotId)), Map.class);
		assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(booking.getBody()).containsEntry("status", BookingStatus.CONFIRMED.name());
		Long bookingId = ((Number) booking.getBody().get("id")).longValue();

		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.BOOKED);

		// ---------- 8. Concurrency protection ----------
		// A second candidate cannot take the same slot. BookingConcurrencyTest proves this under
		// genuinely parallel threads; here it confirms the rule survives the full HTTP stack.
		String rivalToken = login(rivalEmail);
		ResponseEntity<Map> rejected = rest.exchange(url("/api/bookings"), HttpMethod.POST,
				authorized(rivalToken, """
						{"availabilityId":%d}""".formatted(slotId)), Map.class);
		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// ---------- 9. Session created with the booking ----------
		Session session = sessionRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(session.getStatus()).isEqualTo(SessionStatus.SCHEDULED);

		// ---------- 10. Notification delivered asynchronously ----------
		String bookingEventId = "BOOKING_CREATED:" + bookingId;
		await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(bookingEventId).size() >= 2);

		assertThat(notificationRepository.findByEventId(bookingEventId))
				.extracting(Notification::getRecipientId)
				.hasSize(2);

		// The outbox records the event as delivered rather than merely attempted.
		await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
				.until(() -> outboxEventRepository.findByEventId(bookingEventId)
						.filter(record -> record.getStatus() == OutboxStatus.SENT).isPresent());

		// ---------- 11. Session reminder ----------
		assertThat(sessionReminderJob.sendDueReminders()).isEqualTo(1);

		String reminderEventId = "SESSION_REMINDER:" + session.getId();
		await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(reminderEventId).size() >= 2);

		// ---------- 12. Real-time chat ----------
		StompSession candidateSocket = connect(loginToken);
		StompSession mentorSocket = connect(mentorToken);
		BlockingQueue<ChatMessageResponse> mentorInbox = subscribe(mentorSocket, session.getId());

		candidateSocket.send("/app/chat/" + session.getId(),
				new ChatMessageRequest("Looking forward to the session"));

		ChatMessageResponse delivered = mentorInbox.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		assertThat(delivered).isNotNull();
		assertThat(delivered.content()).isEqualTo("Looking forward to the session");
		assertThat(delivered.senderName()).isEqualTo("E2E Candidate");

		// ---------- 13. Chat history over REST ----------
		ResponseEntity<List> history = rest.exchange(url("/api/sessions/") + session.getId() + "/messages",
				HttpMethod.GET, authorized(mentorToken), List.class);
		assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(history.getBody()).hasSize(1);

		// A user outside the session cannot read the transcript.
		assertThat(rest.exchange(url("/api/sessions/") + session.getId() + "/messages", HttpMethod.GET,
				authorized(rivalToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// ---------- 14. Cancellation releases the slot ----------
		ResponseEntity<Void> cancelled = rest.exchange(url("/api/bookings/") + bookingId, HttpMethod.DELETE,
				authorized(loginToken), Void.class);
		assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
	}

	// ---------- helpers ----------

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private String register(String name, String email, String role) {
		ResponseEntity<Map> response = rest.exchange(url("/api/auth/register"), HttpMethod.POST,
				json("""
						{"name":"%s","email":"%s","password":"password123","role":"%s"}"""
						.formatted(name, email, role)), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return (String) response.getBody().get("token");
	}

	private String login(String email) {
		ResponseEntity<Map> response = rest.exchange(url("/api/auth/login"), HttpMethod.POST,
				json("""
						{"email":"%s","password":"password123"}""".formatted(email)), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return (String) response.getBody().get("token");
	}

	private HttpEntity<String> json(String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(body, headers);
	}

	private HttpEntity<Void> authorized(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return new HttpEntity<>(headers);
	}

	private HttpEntity<String> authorized(String token, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		return new HttpEntity<>(body, headers);
	}

	private StompSession connect(String token) throws Exception {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + token);
		return stompClient
				.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
						new StompSessionHandlerAdapter() {
						})
				.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
	}

	private BlockingQueue<ChatMessageResponse> subscribe(StompSession session, Long sessionId) {
		BlockingQueue<ChatMessageResponse> queue = new LinkedBlockingQueue<>();
		session.subscribe("/topic/session/" + sessionId, new StompFrameHandler() {

			@Override
			public Type getPayloadType(StompHeaders headers) {
				return ChatMessageResponse.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				queue.add((ChatMessageResponse) payload);
			}

		});
		return queue;
	}

}
