package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.UserRepository;

/**
 * Deliberately NOT @Transactional. A test-managed transaction would roll the seed data back,
 * hide it from the worker threads, and defeat row locking entirely.
 */
@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class BookingConcurrencyTest {

	private static final Instant START = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

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

	private Long slotId;

	private final List<String> candidateEmails = new ArrayList<>();

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedCommittedFixture() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "conc.mentor." + suffix + "@example.com";
		candidateEmails.clear();
		for (int i = 0; i < 8; i++) {
			candidateEmails.add("conc.candidate." + i + "." + suffix + "@example.com");
		}

		transactionTemplate.executeWithoutResult(status -> {
			persistUser(mentorEmail, "Concurrency Mentor", Role.MENTOR);
			mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
			slotId = availabilityService.create(mentorEmail, new AvailabilityRequest(START, END)).id();

			candidateEmails.forEach(email -> persistUser(email, "Concurrent Candidate", Role.CANDIDATE));
		});
	}

	@AfterEach
	void removeFixture() {
		transactionTemplate.executeWithoutResult(status -> {
			bookingsForSlot().forEach(bookingRepository::delete);
			if (slotId != null) {
				availabilityRepository.findById(slotId).ifPresent(availabilityRepository::delete);
			}
			if (mentorEmail != null) {
				mentorProfileRepository.findByUserEmail(mentorEmail).ifPresent(mentorProfileRepository::delete);
				userRepository.findByEmail(mentorEmail).ifPresent(userRepository::delete);
			}
			candidateEmails.forEach(email -> userRepository.findByEmail(email).ifPresent(userRepository::delete));
		});
	}

	@Test
	void twoSimultaneousCandidatesProduceExactlyOneBooking() throws Exception {
		RaceResult result = race(2);

		assertThat(result.successes).isEqualTo(1);
		assertThat(result.failures).hasSize(1);
		assertRejectedForConflict(result);
		assertExactlyOneConfirmedBookingAndSlotBooked();
	}

	@Test
	void eightSimultaneousCandidatesProduceExactlyOneBooking() throws Exception {
		RaceResult result = race(8);

		assertThat(result.successes).isEqualTo(1);
		assertThat(result.failures).hasSize(7);
		assertRejectedForConflict(result);
		assertExactlyOneConfirmedBookingAndSlotBooked();
	}

	@Test
	void slotCanBeRebookedAfterTheWinnerCancels() throws Exception {
		RaceResult result = race(2);
		assertThat(result.successes).isEqualTo(1);

		Booking winner = confirmedBookings().getFirst();
		String winnerEmail = winner.getCandidate().getEmail();

		bookingService.cancel(winnerEmail, winner.getId());

		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);
		assertThat(confirmedBookings()).isEmpty();

		String nextCandidate = candidateEmails.stream().filter(email -> !email.equals(winnerEmail)).findFirst()
				.orElseThrow();
		var rebooked = bookingService.create(nextCandidate, new BookingRequest(slotId));

		assertThat(rebooked.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertExactlyOneConfirmedBookingAndSlotBooked();
	}

	// Every thread parks on the same latch, so they are released into create() together.
	private RaceResult race(int threads) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(threads);
		AtomicInteger successes = new AtomicInteger();
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		try {
			for (int i = 0; i < threads; i++) {
				String email = candidateEmails.get(i);
				pool.submit(() -> {
					try {
						startGate.await();
						bookingService.create(email, new BookingRequest(slotId));
						successes.incrementAndGet();
					}
					catch (Throwable throwable) {
						failures.add(throwable);
					}
					finally {
						finished.countDown();
					}
				});
			}

			startGate.countDown();
			assertThat(finished.await(30, TimeUnit.SECONDS)).as("all booking threads finished").isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		return new RaceResult(successes.get(), failures);
	}

	private void assertRejectedForConflict(RaceResult result) {
		assertThat(result.failures).allSatisfy(throwable -> assertThat(throwable)
				.isInstanceOfAny(BookingConflictException.class, DataIntegrityViolationException.class));
	}

	private void assertExactlyOneConfirmedBookingAndSlotBooked() {
		assertThat(confirmedBookings()).hasSize(1);
		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.BOOKED);
	}

	private List<Booking> confirmedBookings() {
		return bookingsForSlot().stream().filter(booking -> booking.getStatus() == BookingStatus.CONFIRMED).toList();
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

	private record RaceResult(int successes, List<Throwable> failures) {
	}

}
