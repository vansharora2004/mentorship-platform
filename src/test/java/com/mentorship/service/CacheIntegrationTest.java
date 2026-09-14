package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

/**
 * Requires a running Redis: docker compose up -d
 *
 * <p>These tests assert the observable contract: a cached read does not reach the repository a
 * second time, and a write makes the next read reach it again. They deliberately do NOT inspect
 * Redis directly. A Redis MONITOR trace showed the @Cacheable SET arriving after the calling method
 * had already returned, on a separate connection, so "is the key present right now" is a race
 * rather than a contract. Verifying repository invocations proves the cache actually
 * short-circuited the database, which is the behaviour that matters.
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class CacheIntegrationTest {

	private static final Instant START = Instant.now().plus(9, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private MentorService mentorService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private SessionRepository sessionRepository;

	@MockitoSpyBean
	private MentorProfileRepository mentorProfileRepository;

	@MockitoSpyBean
	private AvailabilityRepository availabilityRepository;

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
		mentorEmail = "cache.mentor." + suffix + "@example.com";
		candidateEmail = "cache.candidate." + suffix + "@example.com";

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "Cache Mentor", Role.MENTOR).getId();
			persistUser(candidateEmail, "Cache Candidate", Role.CANDIDATE);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		});

		evictBoth();
		clearInvocations(mentorProfileRepository, availabilityRepository);
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
		evictBoth();
	}

	// ---------- mentor cache ----------

	@Test
	void repeatedMentorReadIsServedFromCache() {
		var first = mentorService.getByMentorId(mentorId);
		var second = mentorService.getByMentorId(mentorId);

		assertThat(second.mentorId()).isEqualTo(first.mentorId());
		assertThat(second.industry()).isEqualTo("FinTech");
		verify(mentorProfileRepository, times(1)).findByUserId(mentorId);
	}

	@Test
	void mentorUpdateInvalidatesMentorCache() {
		mentorService.getByMentorId(mentorId);
		verify(mentorProfileRepository, times(1)).findByUserId(mentorId);

		mentorService.updateProfile(mentorEmail, new MentorProfileRequest("HealthTech", "Kotlin", 3, null));

		assertThat(mentorService.getByMentorId(mentorId).industry()).isEqualTo("HealthTech");
		verify(mentorProfileRepository, times(2)).findByUserId(mentorId);
	}

	@Test
	void mentorDeleteInvalidatesMentorCache() {
		mentorService.getByMentorId(mentorId);
		verify(mentorProfileRepository, times(1)).findByUserId(mentorId);

		mentorService.deleteProfile(mentorEmail);

		// A stale entry would answer here instead of reporting the profile as gone.
		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> mentorService.getByMentorId(mentorId));
		verify(mentorProfileRepository, times(2)).findByUserId(mentorId);
	}

	// ---------- availability cache ----------

	@Test
	void repeatedAvailabilityReadIsServedFromCache() {
		availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		clearInvocations(availabilityRepository);

		var first = availabilityService.listForMentor(mentorId);
		var second = availabilityService.listForMentor(mentorId);

		assertThat(first).hasSize(1);
		assertThat(second).hasSize(1);
		assertThat(second.getFirst().startTime()).isEqualTo(START);
		verify(availabilityRepository, times(1)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	@Test
	void availabilityCreateInvalidatesCache() {
		availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		clearInvocations(availabilityRepository);
		availabilityService.listForMentor(mentorId);

		availabilityService.create(mentorEmail, new AvailabilityRequest(END, END.plus(1, ChronoUnit.HOURS)));

		assertThat(availabilityService.listForMentor(mentorId)).hasSize(2);
		verify(availabilityRepository, times(2)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	@Test
	void availabilityUpdateInvalidatesCache() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		clearInvocations(availabilityRepository);
		availabilityService.listForMentor(mentorId);

		Instant moved = START.plus(4, ChronoUnit.HOURS);
		availabilityService.update(mentorEmail, slot.id(), new AvailabilityRequest(moved, moved.plusSeconds(3600)));

		assertThat(availabilityService.listForMentor(mentorId).getFirst().startTime()).isEqualTo(moved);
		verify(availabilityRepository, times(2)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	@Test
	void availabilityDeleteInvalidatesCache() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		clearInvocations(availabilityRepository);
		availabilityService.listForMentor(mentorId);

		availabilityService.delete(mentorEmail, slot.id());

		assertThat(availabilityService.listForMentor(mentorId)).isEmpty();
		verify(availabilityRepository, times(2)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	@Test
	void bookingCreationInvalidatesAvailabilityCache() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		clearInvocations(availabilityRepository);
		availabilityService.listForMentor(mentorId);

		bookingService.create(candidateEmail, new BookingRequest(slot.id()));

		assertThat(availabilityService.listForMentor(mentorId).getFirst().status())
				.isEqualTo(AvailabilityStatus.BOOKED);
		verify(availabilityRepository, times(2)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	@Test
	void bookingCancellationInvalidatesAvailabilityCache() {
		var slot = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END));
		var booking = bookingService.create(candidateEmail, new BookingRequest(slot.id()));
		clearInvocations(availabilityRepository);
		availabilityService.listForMentor(mentorId);

		bookingService.cancel(candidateEmail, booking.id());

		assertThat(availabilityService.listForMentor(mentorId).getFirst().status())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
		verify(availabilityRepository, times(2)).findByMentorProfileUserIdOrderByStartTimeAsc(mentorId);
	}

	// ---------- helpers ----------

	private void evictBoth() {
		if (mentorId != null) {
			cacheManager.getCache(CacheEvictor.MENTOR_CACHE).evict(mentorId);
			cacheManager.getCache(CacheEvictor.AVAILABILITY_CACHE).evict(mentorId);
		}
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
