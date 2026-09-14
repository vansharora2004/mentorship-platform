package com.mentorship.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.mentorship.dto.ChatMessageRequest;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.ChatMessage;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.ChatMessageRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.security.JwtService;
import com.mentorship.service.MentorService;

/**
 * Drives a real STOMP client over a real WebSocket connection against the running application, so
 * the handshake, the CONNECT authentication, the SUBSCRIBE authorisation, the controller and the
 * broadcast are all exercised together rather than simulated.
 *
 * <p>Covers the documented WebSocket security rules: an unauthenticated or forged token cannot
 * connect, a non-participant cannot subscribe to a session by guessing its id, and an invalid or
 * out-of-state message is rejected without being broadcast.
 *
 * <p>Requires Redis and RabbitMQ for the application context: docker compose up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class ChatWebSocketIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private MentorService mentorService;

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
	private ChatMessageRepository chatMessageRepository;

	private TransactionTemplate transactionTemplate;

	private WebSocketStompClient stompClient;

	private String mentorEmail;

	private String candidateEmail;

	private String strangerEmail;

	private Long mentorId;

	private Long sessionId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		stompClient.setMessageConverter(new JacksonJsonMessageConverter());

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "chat.mentor." + suffix + "@example.com";
		candidateEmail = "chat.candidate." + suffix + "@example.com";
		strangerEmail = "chat.stranger." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "Chat Mentor", Role.MENTOR).getId();
			persistUser(candidateEmail, "Chat Candidate", Role.CANDIDATE);
			persistUser(strangerEmail, "Chat Stranger", Role.CANDIDATE);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});

		sessionId = createSession(SessionStatus.ACTIVE).getId();
	}

	@AfterEach
	void tearDown() {
		if (stompClient != null) {
			stompClient.stop();
		}
		transactionTemplate.executeWithoutResult(status -> {
			chatMessageRepository.deleteAll(chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId));
			bookingRepository.findAll().stream()
					.filter(booking -> mentorId.equals(booking.getMentor().getId()))
					.forEach(booking -> {
						sessionRepository.findByBookingId(booking.getId()).ifPresent(sessionRepository::delete);
						bookingRepository.delete(booking);
					});
			availabilityRepository.findByMentorProfileUserIdOrderByStartTimeAsc(mentorId)
					.forEach(availabilityRepository::delete);
			mentorProfileRepository.findByUserEmail(mentorEmail).ifPresent(mentorProfileRepository::delete);
			userRepository.findByEmail(mentorEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(candidateEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(strangerEmail).ifPresent(userRepository::delete);
		});
	}

	// ---------- happy path ----------

	@Test
	void aMessageSentByOneParticipantReachesTheOther() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		StompSession mentor = connect(mentorEmail, Role.MENTOR);

		BlockingQueue<ChatMessageResponse> received = subscribe(mentor);
		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("https://example.com/resource"));

		ChatMessageResponse delivered = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		assertThat(delivered).isNotNull();
		assertThat(delivered.content()).isEqualTo("https://example.com/resource");
		assertThat(delivered.senderName()).isEqualTo("Chat Candidate");
		assertThat(delivered.sessionId()).isEqualTo(sessionId);
	}

	@Test
	void aDeliveredMessageIsAlsoPersisted() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> received = subscribe(candidate);

		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("Persist me"));
		assertThat(received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNotNull();

		assertThat(chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId))
				.extracting(ChatMessage::getContent)
				.containsExactly("Persist me");
	}

	@Test
	void bothParticipantsSeeTheConversationInOrder() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		StompSession mentor = connect(mentorEmail, Role.MENTOR);
		BlockingQueue<ChatMessageResponse> mentorInbox = subscribe(mentor);

		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("first"));
		assertThat(poll(mentorInbox).content()).isEqualTo("first");

		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("second"));
		assertThat(poll(mentorInbox).content()).isEqualTo("second");
	}

	// ---------- authentication ----------

	@Test
	void connectingWithoutATokenIsRejected() {
		assertThatThrownBy(() -> stompClient.connectAsync(url(), new StompSessionHandlerAdapter() {
		}).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class);
	}

	@Test
	void connectingWithAForgedTokenIsRejected() {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer not-a-real-token");

		assertThatThrownBy(() -> stompClient
				.connectAsync(url(), new org.springframework.web.socket.WebSocketHttpHeaders(), headers,
						new StompSessionHandlerAdapter() {
						})
				.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class);
	}

	// ---------- authorisation ----------

	@Test
	void aStrangerCannotSubscribeToSomeoneElsesSession() throws Exception {
		StompSession stranger = connect(strangerEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> stolen = subscribe(stranger);

		StompSession mentor = connect(mentorEmail, Role.MENTOR);
		BlockingQueue<ChatMessageResponse> legitimate = subscribe(mentor);

		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("private matter"));

		// The control: the broadcast really did happen, to the participant who is entitled to it.
		// Without this the assertion below would also pass if the send had failed outright.
		assertThat(poll(legitimate).content()).isEqualTo("private matter");
		assertThat(stolen.poll(2, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void aStrangerCannotSendToSomeoneElsesSession() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> inbox = subscribe(candidate);

		StompSession stranger = connect(strangerEmail, Role.CANDIDATE);
		stranger.send("/app/chat/" + sessionId, new ChatMessageRequest("let me in"));

		assertThat(inbox.poll(2, TimeUnit.SECONDS)).isNull();
		assertThat(chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId)).isEmpty();
	}

	// ---------- message validation ----------

	@Test
	void anEmptyMessageIsNeitherBroadcastNorStored() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> inbox = subscribe(candidate);

		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("   "));
		assertThat(inbox.poll(2, TimeUnit.SECONDS)).isNull();
		assertThat(chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId)).isEmpty();

		// Control: a valid message on the same subscription does arrive.
		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("valid"));
		assertThat(poll(inbox).content()).isEqualTo("valid");
	}

	@Test
	void anOversizedMessageIsNeitherBroadcastNorStored() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> inbox = subscribe(candidate);

		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("x".repeat(ChatMessage.MAX_LENGTH + 1)));

		assertThat(inbox.poll(2, TimeUnit.SECONDS)).isNull();
		assertThat(chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId)).isEmpty();
	}

	@Test
	void aCompletedSessionRejectsNewMessages() throws Exception {
		Long completedId = createSession(SessionStatus.COMPLETED).getId();
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);

		BlockingQueue<ChatMessageResponse> inbox = new LinkedBlockingQueue<>();
		candidate.subscribe("/topic/session/" + completedId, handler(inbox));
		candidate.send("/app/chat/" + completedId, new ChatMessageRequest("session is over"));

		assertThat(inbox.poll(2, TimeUnit.SECONDS)).isNull();
		assertThat(chatMessageRepository.findBySessionIdOrderBySentAtAsc(completedId)).isEmpty();
	}

	@Test
	void sendingToAnUnknownSessionIsRejectedWithoutBreakingTheConnection() throws Exception {
		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		BlockingQueue<ChatMessageResponse> inbox = subscribe(candidate);

		candidate.send("/app/chat/99999999", new ChatMessageRequest("anyone there"));
		assertThat(inbox.poll(2, TimeUnit.SECONDS)).isNull();

		// The rejection is graceful: the same session can still be used afterwards.
		candidate.send("/app/chat/" + sessionId, new ChatMessageRequest("still connected"));
		assertThat(poll(inbox).content()).isEqualTo("still connected");
	}

	@Test
	void disconnectingDoesNotDisturbTheOtherParticipant() throws Exception {
		StompSession mentor = connect(mentorEmail, Role.MENTOR);
		BlockingQueue<ChatMessageResponse> mentorInbox = subscribe(mentor);

		StompSession candidate = connect(candidateEmail, Role.CANDIDATE);
		candidate.disconnect();

		StompSession reconnected = connect(candidateEmail, Role.CANDIDATE);
		reconnected.send("/app/chat/" + sessionId, new ChatMessageRequest("back again"));

		assertThat(poll(mentorInbox).content()).isEqualTo("back again");
	}

	// ---------- helpers ----------

	private String url() {
		return "ws://localhost:" + port + "/ws";
	}

	private StompSession connect(String email, Role role) throws Exception {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + jwtService.generateToken(email, role));

		return stompClient
				.connectAsync(url(), new org.springframework.web.socket.WebSocketHttpHeaders(), headers,
						new StompSessionHandlerAdapter() {
						})
				.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
	}

	private BlockingQueue<ChatMessageResponse> subscribe(StompSession session) {
		BlockingQueue<ChatMessageResponse> queue = new LinkedBlockingQueue<>();
		session.subscribe("/topic/session/" + sessionId, handler(queue));
		return queue;
	}

	private StompFrameHandler handler(BlockingQueue<ChatMessageResponse> queue) {
		return new StompFrameHandler() {

			@Override
			public Type getPayloadType(StompHeaders headers) {
				return ChatMessageResponse.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				queue.add((ChatMessageResponse) payload);
			}

		};
	}

	private ChatMessageResponse poll(BlockingQueue<ChatMessageResponse> queue) throws InterruptedException {
		ChatMessageResponse message = queue.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		assertThat(message).isNotNull();
		return message;
	}

	private Session createSession(SessionStatus status) {
		return transactionTemplate.execute(tx -> {
			MentorProfile profile = mentorProfileRepository.findByUserEmail(mentorEmail).orElseThrow();
			User mentor = userRepository.findByEmail(mentorEmail).orElseThrow();
			User candidate = userRepository.findByEmail(candidateEmail).orElseThrow();

			Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
			Instant end = start.plus(1, ChronoUnit.HOURS);

			Availability slot = new Availability();
			slot.setMentorProfile(profile);
			slot.setStartTime(start);
			slot.setEndTime(end);
			slot.setStatus(AvailabilityStatus.BOOKED);
			slot = availabilityRepository.save(slot);

			Booking booking = new Booking();
			booking.setCandidate(candidate);
			booking.setMentor(mentor);
			booking.setAvailability(slot);
			booking.setStartTime(start);
			booking.setEndTime(end);
			booking.setStatus(BookingStatus.CONFIRMED);
			booking = bookingRepository.save(booking);

			Session session = new Session();
			session.setBooking(booking);
			session.setStartTime(start);
			session.setEndTime(end);
			session.setStatus(status);
			return sessionRepository.save(session);
		});
	}

	private User persistUser(String email, String name, Role role) {
		User user = new User();
		user.setName(name);
		user.setEmail(email);
		user.setPasswordHash("hashed");
		user.setRole(role);
		return userRepository.save(user);
	}

}
