package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.User;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

/**
 * Proves the Edge Cases §7 requirement: a booking must never survive a failed session
 * creation. Deliberately NOT @Transactional, so the rollback is real and observable.
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class SessionAtomicityTest {

	private static final Instant START = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@MockitoBean
	private SessionRepository sessionRepository;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private MentorService mentorService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MentorProfileRepository mentorProfileRepository;

	@Autowired
	private AvailabilityRepository availabilityRepository;

	@Autowired
	private BookingRepository bookingRepository;

	private TransactionTemplate transactionTemplate;

	private String mentorEmail;

	private String candidateEmail;

	private Long slotId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedCommittedFixture() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "atomic.mentor." + suffix + "@example.com";
		candidateEmail = "atomic.candidate." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			persistUser(mentorEmail, "Atomic Mentor", Role.MENTOR);
			persistUser(candidateEmail, "Atomic Candidate", Role.CANDIDATE);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
			slotId = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END)).id();
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
			bookingsForSlot().forEach(bookingRepository::delete);
			if (slotId != null) {
				availabilityRepository.findById(slotId).ifPresent(availabilityRepository::delete);
			}
			mentorProfileRepository.findByUserEmail(mentorEmail).ifPresent(mentorProfileRepository::delete);
			userRepository.findByEmail(mentorEmail).ifPresent(userRepository::delete);
			userRepository.findByEmail(candidateEmail).ifPresent(userRepository::delete);
		});
	}

	@Test
	void bookingRollsBackEntirelyWhenSessionCreationFails() {
		given(sessionRepository.save(any(Session.class)))
				.willThrow(new DataIntegrityViolationException("session insert failed"));

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> bookingService.create(candidateEmail, new BookingRequest(slotId)));

		// No orphan booking: the state Edge Cases §7 forbids.
		assertThat(bookingsForSlot()).isEmpty();

		// The slot status change rolled back too, proving the whole transaction unwound.
		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
	}

	private List<Booking> bookingsForSlot() {
		if (slotId == null) {
			return List.of();
		}
		return bookingRepository.findAll().stream()
				.filter(booking -> slotId.equals(booking.getAvailability().getId()))
				.toList();
	}

	private void persistUser(String email, String name, Role role) {
		User user = new User();
		user.setName(name);
		user.setEmail(email);
		user.setPasswordHash("hashed");
		user.setRole(role);
		userRepository.save(user);
	}

}
