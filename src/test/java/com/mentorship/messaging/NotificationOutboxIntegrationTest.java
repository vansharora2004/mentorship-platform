package com.mentorship.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.Notification;
import com.mentorship.entity.OutboxEvent;
import com.mentorship.entity.OutboxStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.event.NotificationEvent;
import com.mentorship.event.NotificationEventType;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.NotificationRepository;
import com.mentorship.repository.OutboxEventRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.scheduler.OutboxRetryJob;
import com.mentorship.service.AvailabilityService;
import com.mentorship.service.BookingService;
import com.mentorship.service.MentorService;

/**
 * The outbox against a real broker: an event raised by a booking is recorded, published, and marked
 * sent; and an event left PENDING by an earlier outage is delivered by the retry job.
 *
 * <p>A pending row is created directly here to stand in for one written while RabbitMQ was down.
 * That is exactly the row {@code NotificationOutbox.record} writes, and it lets the recovery path
 * be tested against a broker that is actually reachable.
 * {@code RabbitFailureFallbackTest} covers the other half - that the row survives the outage in the
 * first place.
 *
 * <p>Requires RabbitMQ and Redis: docker compose up -d
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class NotificationOutboxIntegrationTest {

	private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

	private static final Instant START = Instant.now().plus(27, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private MentorService mentorService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private OutboxRetryJob outboxRetryJob;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private NotificationRepository notificationRepository;

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

	private TransactionTemplate transactionTemplate;

	private String mentorEmail;

	private String candidateEmail;

	private Long mentorId;

	private Long candidateId;

	private String orphanEventId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedCommittedFixture() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "outbox.mentor." + suffix + "@example.com";
		candidateEmail = "outbox.candidate." + suffix + "@example.com";
		orphanEventId = null;

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "Outbox Mentor", Role.MENTOR).getId();
			candidateId = persistUser(candidateEmail, "Outbox Candidate", Role.CANDIDATE).getId();
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
			if (orphanEventId != null) {
				outboxEventRepository.findByEventId(orphanEventId).ifPresent(outboxEventRepository::delete);
				notificationRepository.deleteAll(notificationRepository.findByEventId(orphanEventId));
			}
			notificationRepository.deleteAll(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(mentorId));
			notificationRepository.deleteAll(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(candidateId));
			bookingRepository.findAll().stream()
					.filter(booking -> mentorId.equals(booking.getMentor().getId()))
					.forEach(booking -> {
						outboxEventRepository.findByEventId("BOOKING_CREATED:" + booking.getId())
								.ifPresent(outboxEventRepository::delete);
						outboxEventRepository.findByEventId("BOOKING_CANCELLED:" + booking.getId())
								.ifPresent(outboxEventRepository::delete);
						sessionRepository.findByBookingId(booking.getId()).ifPresent(sessionRepository::delete);
						bookingRepository.delete(booking);
					});
			availabilityRepository.findByMentorProfileUserIdOrderByStartTimeAsc(mentorId)
					.forEach(availabilityRepository::delete);
			mentorProfileRepository.findByUserEmail(mentorEmail).ifPresent(mentorProfileRepository::delete);
			userRepository.findByEmail(mentorEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(candidateEmail).ifPresent(userRepository::delete);
		});
	}

	// ---------- normal path ----------

	@Test
	void aBookingRecordsItsEventInTheOutbox() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThat(outboxEventRepository.findByEventId("BOOKING_CREATED:" + booking.id())).isPresent();
	}

	@Test
	void aPublishedEventIsMarkedSent() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		String eventId = "BOOKING_CREATED:" + booking.id();
		await().atMost(DELIVERY_TIMEOUT)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> outboxEventRepository.findByEventId(eventId)
						.filter(record -> record.getStatus() == OutboxStatus.SENT)
						.isPresent());

		OutboxEvent record = outboxEventRepository.findByEventId(eventId).orElseThrow();
		assertThat(record.getSentAt()).isNotNull();
		assertThat(record.getAttempts()).isZero();
	}

	@Test
	void cancellationRecordsItsOwnOutboxEntry() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		bookingService.cancel(candidateEmail, booking.id());

		assertThat(outboxEventRepository.findByEventId("BOOKING_CREATED:" + booking.id())).isPresent();
		assertThat(outboxEventRepository.findByEventId("BOOKING_CANCELLED:" + booking.id())).isPresent();
	}

	// ---------- recovery path ----------

	@Test
	void theRetryJobPublishesAnEventLeftPendingByAnOutage() {
		OutboxEvent stranded = givenPendingEventFromAnEarlierOutage();

		assertThat(outboxRetryJob.publishPendingEvents()).isEqualTo(1);

		assertThat(outboxEventRepository.findByEventId(stranded.getEventId()).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.SENT);
	}

	@Test
	void aRecoveredEventReachesBothParticipants() {
		OutboxEvent stranded = givenPendingEventFromAnEarlierOutage();

		outboxRetryJob.publishPendingEvents();

		await().atMost(DELIVERY_TIMEOUT)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(stranded.getEventId()).size() >= 2);

		assertThat(notificationRepository.findByEventId(stranded.getEventId()))
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(candidateId, mentorId);
	}

	@Test
	void anEventAlreadySentIsNotPublishedAgain() {
		givenPendingEventFromAnEarlierOutage();
		assertThat(outboxRetryJob.publishPendingEvents()).isEqualTo(1);

		// Second run: the row is SENT, so there is nothing left to publish.
		assertThat(outboxRetryJob.publishPendingEvents()).isZero();
	}

	@Test
	void repeatedRunsProduceNoDuplicateNotifications() {
		OutboxEvent stranded = givenPendingEventFromAnEarlierOutage();

		outboxRetryJob.publishPendingEvents();
		await().atMost(DELIVERY_TIMEOUT)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(stranded.getEventId()).size() >= 2);

		outboxRetryJob.publishPendingEvents();
		outboxRetryJob.publishPendingEvents();

		// Exactly one notification per participant, however many times the job runs.
		assertThat(notificationRepository.findByEventId(stranded.getEventId())).hasSize(2);
	}

	/**
	 * A row in the state an outage leaves behind: recorded and committed, never acknowledged, and
	 * old enough to be past the retry grace period.
	 */
	private OutboxEvent givenPendingEventFromAnEarlierOutage() {
		orphanEventId = "BOOKING_CREATED:" + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);

		return transactionTemplate.execute(tx -> {
			OutboxEvent record = OutboxEvent.pending(new NotificationEvent(orphanEventId,
					NotificationEventType.BOOKING_CREATED, 424242L, null, candidateId, mentorId,
					Instant.now().minus(30, ChronoUnit.MINUTES)));
			record.setCreatedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
			return outboxEventRepository.save(record);
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
