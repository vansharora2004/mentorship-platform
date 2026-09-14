package com.mentorship.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.event.NotificationEventType;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.NotificationRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.service.AvailabilityService;
import com.mentorship.service.BookingService;
import com.mentorship.service.MentorService;

/**
 * End-to-end Phase 9 flow against a real broker: booking publishes, RabbitMQ delivers, the consumer
 * persists a notification for each participant.
 *
 * <p>Requires RabbitMQ and Redis: docker compose up -d
 *
 * <p>Delivery is genuinely asynchronous, so the assertions poll for a bounded period rather than
 * assuming the consumer has already run. That is waiting for a defined outcome, not padding a race.
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class NotificationFlowIntegrationTest {

	private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

	private static final Instant START = Instant.now().plus(21, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private MentorService mentorService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private BookingService bookingService;

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

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedCommittedFixture() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "amqp.mentor." + suffix + "@example.com";
		candidateEmail = "amqp.candidate." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "Amqp Mentor", Role.MENTOR).getId();
			candidateId = persistUser(candidateEmail, "Amqp Candidate", Role.CANDIDATE).getId();
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
			notificationRepository.deleteAll(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(mentorId));
			notificationRepository.deleteAll(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(candidateId));
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
		});
	}

	@Test
	void bookingDeliversOneNotificationToEachParticipant() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		List<Notification> delivered = awaitNotifications("BOOKING_CREATED:" + booking.id(), 2);
		assertThat(delivered)
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(candidateId, mentorId);
		assertThat(delivered)
				.allSatisfy(notification -> {
					assertThat(notification.getEventType()).isEqualTo(NotificationEventType.BOOKING_CREATED);
					assertThat(notification.getBookingId()).isEqualTo(booking.id());
					assertThat(notification.getMessage()).isNotBlank();
				});
	}

	@Test
	void cancellationDeliversItsOwnNotification() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));
		awaitNotifications("BOOKING_CREATED:" + booking.id(), 2);

		bookingService.cancel(candidateEmail, booking.id());

		List<Notification> delivered = awaitNotifications("BOOKING_CANCELLED:" + booking.id(), 2);
		assertThat(delivered)
				.extracting(Notification::getEventType)
				.containsOnly(NotificationEventType.BOOKING_CANCELLED);
	}

	/**
	 * The booking API must not block on notification processing. The publish happens after commit,
	 * so the booking is already durable and readable before the consumer has necessarily run.
	 */
	@Test
	void bookingIsCommittedBeforeTheNotificationIsProcessed() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThat(bookingRepository.findById(booking.id())).isPresent();
		awaitNotifications("BOOKING_CREATED:" + booking.id(), 2);
	}

	private List<Notification> awaitNotifications(String eventId, int expected) {
		await().atMost(DELIVERY_TIMEOUT)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(eventId).size() >= expected);
		return notificationRepository.findByEventId(eventId);
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
