package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

/**
 * Points Redis at a closed port so every cache operation fails. Nothing here needs Redis running -
 * that is the point. Reads must still serve correct data from PostgreSQL and booking must remain
 * fully functional, proving Redis is never a hard dependency.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256",
		"spring.data.redis.port=6399",
		"spring.data.redis.timeout=200ms",
		"spring.data.redis.connect-timeout=200ms" })
class RedisFailureFallbackTest {

	private static final Instant START = Instant.now().plus(11, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

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
		mentorEmail = "nordis.mentor." + suffix + "@example.com";
		candidateEmail = "nordis.candidate." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "No Redis Mentor", Role.MENTOR).getId();
			persistUser(candidateEmail, "No Redis Candidate", Role.CANDIDATE);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
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
	void mentorReadFallsBackToPostgresWhenRedisIsDown() {
		var profile = mentorService.getByMentorId(mentorId);

		assertThat(profile.mentorId()).isEqualTo(mentorId);
		assertThat(profile.industry()).isEqualTo("FinTech");
	}

	@Test
	void availabilityReadFallsBackToPostgresWhenRedisIsDown() {
		availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var slots = availabilityService.listForMentor(mentorId);

		assertThat(slots).hasSize(1);
		assertThat(slots.getFirst().startTime()).isEqualTo(START);
	}

	@Test
	void bookingRemainsCorrectWhenRedisIsDown() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));

		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(availabilityRepository.findById(slot.id()).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.BOOKED);
		assertThat(sessionRepository.findByBookingId(booking.id())).isPresent();
	}

	@Test
	void cancellationRemainsCorrectWhenRedisIsDown() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		bookingService.cancel(candidateEmail, booking.id());

		assertThat(bookingRepository.findById(booking.id()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		assertThat(availabilityRepository.findById(slot.id()).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
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
