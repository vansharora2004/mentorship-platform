package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.UserRepository;

// Verifies the Booking relationships and slot state transitions against PostgreSQL.
@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class BookingIntegrationTest {

	private static final Instant START = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private BookingService bookingService;

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private MentorService mentorService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AvailabilityRepository availabilityRepository;

	@Autowired
	private BookingRepository bookingRepository;

	private User user(String email, String name, Role role) {
		User user = new User();
		user.setName(name);
		user.setEmail(email);
		user.setPasswordHash("hashed");
		user.setRole(role);
		return userRepository.saveAndFlush(user);
	}

	private Long slotFor(String mentorEmail) {
		user(mentorEmail, "Booking Mentor", Role.MENTOR);
		mentorService.createProfile(mentorEmail, new MentorProfileRequest("FinTech", "Java", 8, null));
		return availabilityService.create(mentorEmail, new AvailabilityRequest(START, END)).id();
	}

	@Test
	void persistsBookingAndMarksTheSlotBooked() {
		Long slotId = slotFor("book.mentor@example.com");
		User candidate = user("book.candidate@example.com", "Candy Date", Role.CANDIDATE);

		var booking = bookingService.create("book.candidate@example.com", new BookingRequest(slotId));

		assertThat(booking.id()).isNotNull();
		assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking.candidateId()).isEqualTo(candidate.getId());
		assertThat(booking.availabilityId()).isEqualTo(slotId);
		assertThat(booking.startTime()).isEqualTo(START);

		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.BOOKED);
	}

	@Test
	void secondBookingOfTheSameSlotIsRejected() {
		Long slotId = slotFor("book.taken@example.com");
		user("book.first@example.com", "First", Role.CANDIDATE);
		user("book.second@example.com", "Second", Role.CANDIDATE);

		bookingService.create("book.first@example.com", new BookingRequest(slotId));

		assertThatExceptionOfType(BookingConflictException.class)
				.isThrownBy(() -> bookingService.create("book.second@example.com", new BookingRequest(slotId)));
	}

	@Test
	void candidateAndMentorBothSeeTheBooking() {
		Long slotId = slotFor("book.visible@example.com");
		user("book.viewer@example.com", "Viewer", Role.CANDIDATE);
		bookingService.create("book.viewer@example.com", new BookingRequest(slotId));

		assertThat(bookingService.listForUser("book.viewer@example.com")).hasSize(1);
		assertThat(bookingService.listForUser("book.visible@example.com")).hasSize(1);
	}

	@Test
	void unrelatedUserCannotReadTheBooking() {
		Long slotId = slotFor("book.private@example.com");
		user("book.owner@example.com", "Owner", Role.CANDIDATE);
		user("book.stranger@example.com", "Stranger", Role.CANDIDATE);
		var booking = bookingService.create("book.owner@example.com", new BookingRequest(slotId));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> bookingService.getById("book.stranger@example.com", booking.id()));
	}

	@Test
	void cancellingReleasesTheSlotForRebooking() {
		Long slotId = slotFor("book.cancel@example.com");
		user("book.canceller@example.com", "Canceller", Role.CANDIDATE);
		var booking = bookingService.create("book.canceller@example.com", new BookingRequest(slotId));

		bookingService.cancel("book.canceller@example.com", booking.id());

		assertThat(bookingRepository.findById(booking.id()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		assertThat(availabilityRepository.findById(slotId).orElseThrow().getStatus())
				.isEqualTo(AvailabilityStatus.AVAILABLE);

		var rebooked = bookingService.create("book.canceller@example.com", new BookingRequest(slotId));
		assertThat(rebooked.status()).isEqualTo(BookingStatus.CONFIRMED);
	}

}
