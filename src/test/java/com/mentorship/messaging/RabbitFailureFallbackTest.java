package com.mentorship.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.OutboxStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.OutboxEventRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.service.AvailabilityService;
import com.mentorship.service.BookingService;
import com.mentorship.service.MentorService;

/**
 * Points RabbitMQ at a closed port so every publish fails. Nothing here needs a broker running -
 * that is the point.
 *
 * <p>Edge Cases: "The booking transaction should not incorrectly be rolled back merely because
 * notification delivery failed." Booking, session creation, and cancellation must all remain
 * correct and durable with the broker unreachable.
 *
 * <p>And the other half of the same requirement - "The notification event should be recoverable" -
 * the event is written to the outbox inside the booking transaction, so it survives the outage as
 * PENDING. {@code NotificationOutboxIntegrationTest} covers the retry that later delivers it.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256",
		"spring.rabbitmq.port=5699",
		"spring.rabbitmq.connection-timeout=200ms",
		"spring.rabbitmq.listener.simple.auto-startup=false" })
class RabbitFailureFallbackTest {

	private static final Instant START = Instant.now().plus(23, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private MentorService mentorService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private BookingService bookingService;

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
	private OutboxEventRepository outboxEventRepository;

	private TransactionTemplate transactionTemplate;

	private String mentorEmail;

	private String candidateEmail;

	private Long mentorId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedCommittedFixture() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "norabbit.mentor." + suffix + "@example.com";
		candidateEmail = "norabbit.candidate." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "No Rabbit Mentor", Role.MENTOR).getId();
			persistUser(candidateEmail, "No Rabbit Candidate", Role.CANDIDATE);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
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

	@Test
	void bookingSucceedsAndPersistsWhenTheBrokerIsUnreachable() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(bookingRepository.findById(booking.id())).isPresent();
		assertThat(availabilityRepository.findById(slot.id()).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.BOOKED);
		assertThat(sessionRepository.findByBookingId(booking.id())).isPresent();
	}

	@Test
	void cancellationSucceedsWhenTheBrokerIsUnreachable() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThatCode(() -> bookingService.cancel(candidateEmail, booking.id())).doesNotThrowAnyException();

		assertThat(bookingRepository.findById(booking.id()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		assertThat(availabilityRepository.findById(slot.id()).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
	}

	@Test
	void theNotificationEventSurvivesTheOutageAsPending() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		// Recorded inside the booking transaction, so the unreachable broker could not lose it.
		var recorded = outboxEventRepository.findByEventId("BOOKING_CREATED:" + booking.id());
		assertThat(recorded).isPresent();
		assertThat(recorded.orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(recorded.orElseThrow().getSentAt()).isNull();
	}

	@Test
	void aCancellationEventAlsoSurvivesTheOutageAsPending() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		bookingService.cancel(candidateEmail, booking.id());

		var recorded = outboxEventRepository.findByEventId("BOOKING_CANCELLED:" + booking.id());
		assertThat(recorded).isPresent();
		assertThat(recorded.orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
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
