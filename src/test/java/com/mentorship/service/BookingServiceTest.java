package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.BookingResponse;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.exception.BookingNotFoundException;
import com.mentorship.exception.InvalidBookingException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

	private static final String CANDIDATE = "candidate@example.com";

	private static final String MENTOR = "mentor@example.com";

	private static final String STRANGER = "stranger@example.com";

	private static final Instant START = Instant.now().plus(1, ChronoUnit.DAYS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Mock
	private BookingRepository bookingRepository;

	@Mock
	private AvailabilityRepository availabilityRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private SessionRepository sessionRepository;

	@Mock
	private CacheEvictor cacheEvictor;

	// Phase 9: booking now announces BOOKING_CREATED / BOOKING_CANCELLED through Spring's event
	// publisher. NotificationEventRelay forwards them to RabbitMQ only after commit, so these unit
	// tests stay broker-free and simply record that the announcement was made.
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private BookingService bookingService;

	private final BookingRequest request = new BookingRequest(10L);

	@Test
	void createsConfirmedBookingForTheAuthenticatedCandidate() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(slot(AvailabilityStatus.AVAILABLE)));
		given(bookingRepository.save(any(Booking.class))).willAnswer(call -> call.getArgument(0));

		BookingResponse response = bookingService.create(CANDIDATE, request);

		ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
		verify(bookingRepository).save(saved.capture());

		assertThat(saved.getValue().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(saved.getValue().getCandidate().getEmail()).isEqualTo(CANDIDATE);
		assertThat(saved.getValue().getMentor().getEmail()).isEqualTo(MENTOR);
		assertThat(saved.getValue().getStartTime()).isEqualTo(START);
		assertThat(saved.getValue().getEndTime()).isEqualTo(END);
		assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
	}

	@Test
	void bookingMarksTheSlotAsBooked() {
		Availability slot = slot(AvailabilityStatus.AVAILABLE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(slot));
		given(bookingRepository.save(any(Booking.class))).willAnswer(call -> call.getArgument(0));

		bookingService.create(CANDIDATE, request);

		assertThat(slot.getStatus()).isEqualTo(AvailabilityStatus.BOOKED);
		verify(availabilityRepository).save(slot);
	}

	@Test
	void createAlsoCreatesAScheduledSessionForTheBooking() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(slot(AvailabilityStatus.AVAILABLE)));
		given(bookingRepository.save(any(Booking.class))).willAnswer(call -> call.getArgument(0));

		bookingService.create(CANDIDATE, request);

		ArgumentCaptor<Session> saved = ArgumentCaptor.forClass(Session.class);
		verify(sessionRepository).save(saved.capture());

		assertThat(saved.getValue().getStatus()).isEqualTo(SessionStatus.SCHEDULED);
		assertThat(saved.getValue().getStartTime()).isEqualTo(START);
		assertThat(saved.getValue().getEndTime()).isEqualTo(END);
		assertThat(saved.getValue().getBooking()).isNotNull();
	}

	@Test
	void createFailsWhenTheSlotDoesNotExist() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

		assertThatExceptionOfType(AvailabilityNotFoundException.class)
				.isThrownBy(() -> bookingService.create(CANDIDATE, request));

		verify(bookingRepository, never()).save(any());
	}

	@Test
	void createFailsWhenTheSlotIsAlreadyBooked() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(slot(AvailabilityStatus.BOOKED)));

		assertThatExceptionOfType(BookingConflictException.class)
				.isThrownBy(() -> bookingService.create(CANDIDATE, request));

		verify(bookingRepository, never()).save(any());
	}

	@Test
	void createFailsWhenTheSlotIsBlocked() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(slot(AvailabilityStatus.BLOCKED)));

		assertThatExceptionOfType(BookingConflictException.class)
				.isThrownBy(() -> bookingService.create(CANDIDATE, request));
	}

	@Test
	void createFailsForASlotInThePast() {
		Availability past = slot(AvailabilityStatus.AVAILABLE);
		past.setStartTime(Instant.now().minus(2, ChronoUnit.HOURS));
		past.setEndTime(Instant.now().minus(1, ChronoUnit.HOURS));

		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(past));

		assertThatExceptionOfType(InvalidBookingException.class)
				.isThrownBy(() -> bookingService.create(CANDIDATE, request));

		verify(bookingRepository, never()).save(any());
	}

	@Test
	void candidateSeesOwnBookings() {
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(candidate()));
		given(bookingRepository.findByCandidateIdOrderByStartTimeDesc(2L))
				.willReturn(List.of(booking(BookingStatus.CONFIRMED)));

		assertThat(bookingService.listForUser(CANDIDATE)).hasSize(1);
		verify(bookingRepository, never()).findByMentorIdOrderByStartTimeDesc(any());
	}

	@Test
	void mentorSeesBookingsAgainstTheirSlots() {
		given(userRepository.findByEmail(MENTOR)).willReturn(Optional.of(mentor()));
		given(bookingRepository.findByMentorIdOrderByStartTimeDesc(1L))
				.willReturn(List.of(booking(BookingStatus.CONFIRMED)));

		assertThat(bookingService.listForUser(MENTOR)).hasSize(1);
		verify(bookingRepository, never()).findByCandidateIdOrderByStartTimeDesc(any());
	}

	@Test
	void participantsCanReadTheBooking() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

		assertThat(bookingService.getById(CANDIDATE, 1L).id()).isEqualTo(1L);
	}

	@Test
	void mentorCanReadTheBooking() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

		assertThat(bookingService.getById(MENTOR, 1L).id()).isEqualTo(1L);
	}

	@Test
	void nonParticipantCannotReadTheBooking() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> bookingService.getById(STRANGER, 1L));
	}

	@Test
	void readingAMissingBookingFails() {
		given(bookingRepository.findById(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(BookingNotFoundException.class)
				.isThrownBy(() -> bookingService.getById(CANDIDATE, 404L));
	}

	@Test
	void cancellingReleasesTheSlot() {
		Booking booking = booking(BookingStatus.CONFIRMED);
		booking.getAvailability().setStatus(AvailabilityStatus.BOOKED);
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(booking.getAvailability()));

		bookingService.cancel(CANDIDATE, 1L);

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.getAvailability().getStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
		verify(bookingRepository).save(booking);
		verify(cacheEvictor).evictAvailability(1L);
	}

	@Test
	void cancellingAlsoCancelsTheSession() {
		Booking booking = booking(BookingStatus.CONFIRMED);
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking));
		given(availabilityRepository.findByIdForUpdate(10L)).willReturn(Optional.of(booking.getAvailability()));

		Session session = new Session();
		session.setBooking(booking);
		session.setStatus(SessionStatus.SCHEDULED);
		given(sessionRepository.findByBookingId(1L)).willReturn(Optional.of(session));

		bookingService.cancel(CANDIDATE, 1L);

		assertThat(session.getStatus()).isEqualTo(SessionStatus.CANCELLED);
		verify(sessionRepository).save(session);
	}

	@Test
	void mentorCannotCancel() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> bookingService.cancel(MENTOR, 1L));

		verify(bookingRepository, never()).save(any());
	}

	@Test
	void strangerCannotCancel() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CONFIRMED)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> bookingService.cancel(STRANGER, 1L));
	}

	@Test
	void anAlreadyCancelledBookingCannotBeCancelledAgain() {
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking(BookingStatus.CANCELLED)));

		assertThatExceptionOfType(BookingConflictException.class)
				.isThrownBy(() -> bookingService.cancel(CANDIDATE, 1L));
	}

	@Test
	void aBookingCannotBeCancelledOnceItsSlotHasStarted() {
		Booking booking = booking(BookingStatus.CONFIRMED);
		booking.setStartTime(Instant.now().minus(10, ChronoUnit.MINUTES));
		given(bookingRepository.findById(1L)).willReturn(Optional.of(booking));

		assertThatExceptionOfType(BookingConflictException.class)
				.isThrownBy(() -> bookingService.cancel(CANDIDATE, 1L));

		verify(bookingRepository, never()).save(any());
	}

	@Test
	void cancellingAMissingBookingFails() {
		given(bookingRepository.findById(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(BookingNotFoundException.class)
				.isThrownBy(() -> bookingService.cancel(CANDIDATE, 404L));
	}

	private User mentor() {
		User user = new User();
		user.setId(1L);
		user.setName("Mentor Mentorson");
		user.setEmail(MENTOR);
		user.setPasswordHash("hashed");
		user.setRole(Role.MENTOR);
		return user;
	}

	private User candidate() {
		User user = new User();
		user.setId(2L);
		user.setName("Candy Date");
		user.setEmail(CANDIDATE);
		user.setPasswordHash("hashed");
		user.setRole(Role.CANDIDATE);
		return user;
	}

	private Availability slot(AvailabilityStatus status) {
		MentorProfile profile = new MentorProfile();
		profile.setId(5L);
		profile.setUser(mentor());

		Availability slot = new Availability();
		slot.setId(10L);
		slot.setMentorProfile(profile);
		slot.setStartTime(START);
		slot.setEndTime(END);
		slot.setStatus(status);
		return slot;
	}

	private Booking booking(BookingStatus status) {
		Booking booking = new Booking();
		booking.setId(1L);
		booking.setMentor(mentor());
		booking.setCandidate(candidate());
		booking.setAvailability(slot(AvailabilityStatus.BOOKED));
		booking.setStartTime(START);
		booking.setEndTime(END);
		booking.setStatus(status);
		booking.setCreatedAt(Instant.now());
		booking.setUpdatedAt(Instant.now());
		return booking;
	}

}
