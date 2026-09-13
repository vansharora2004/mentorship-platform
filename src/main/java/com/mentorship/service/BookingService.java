package com.mentorship.service;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.BookingResponse;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.exception.BookingNotFoundException;
import com.mentorship.exception.InvalidBookingException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.UserRepository;

@Service
public class BookingService {

	private final BookingRepository bookingRepository;

	private final AvailabilityRepository availabilityRepository;

	private final UserRepository userRepository;

	public BookingService(BookingRepository bookingRepository, AvailabilityRepository availabilityRepository,
			UserRepository userRepository) {
		this.bookingRepository = bookingRepository;
		this.availabilityRepository = availabilityRepository;
		this.userRepository = userRepository;
	}

	// The slot row is locked FOR UPDATE before its status is read, so a competing booking
	// blocks here and re-reads the committed status once this transaction ends.
	@Transactional
	public BookingResponse create(String candidateEmail, BookingRequest request) {
		User candidate = userRepository.findByEmail(candidateEmail).orElseThrow();

		Availability slot = availabilityRepository.findByIdForUpdate(request.availabilityId())
				.orElseThrow(() -> new AvailabilityNotFoundException(request.availabilityId()));

		if (slot.getStatus() != AvailabilityStatus.AVAILABLE) {
			throw BookingConflictException.slotUnavailable();
		}
		if (!slot.getStartTime().isAfter(Instant.now())) {
			throw new InvalidBookingException("Cannot book a slot that has already started");
		}

		Booking booking = new Booking();
		booking.setCandidate(candidate);
		booking.setMentor(slot.getMentorProfile().getUser());
		booking.setAvailability(slot);
		booking.setStartTime(slot.getStartTime());
		booking.setEndTime(slot.getEndTime());
		booking.setStatus(BookingStatus.CONFIRMED);

		slot.setStatus(AvailabilityStatus.BOOKED);
		availabilityRepository.save(slot);

		return BookingResponse.from(bookingRepository.save(booking));
	}

	@Transactional(readOnly = true)
	public List<BookingResponse> listForUser(String email) {
		User user = userRepository.findByEmail(email).orElseThrow();

		List<Booking> bookings = user.getRole() == Role.MENTOR
				? bookingRepository.findByMentorIdOrderByStartTimeDesc(user.getId())
				: bookingRepository.findByCandidateIdOrderByStartTimeDesc(user.getId());

		return bookings.stream().map(BookingResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public BookingResponse getById(String email, Long bookingId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new BookingNotFoundException(bookingId));

		if (!isParticipant(booking, email)) {
			throw new AccessDeniedException("You may only view your own bookings");
		}

		return BookingResponse.from(booking);
	}

	@Transactional
	public void cancel(String email, Long bookingId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new BookingNotFoundException(bookingId));

		if (!booking.getCandidate().getEmail().equals(email)) {
			throw new AccessDeniedException("You may only cancel your own bookings");
		}
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw BookingConflictException.notCancellable();
		}
		if (!booking.getStartTime().isAfter(Instant.now())) {
			throw BookingConflictException.alreadyStarted();
		}

		booking.setStatus(BookingStatus.CANCELLED);

		// Locked for the same reason as create: every slot-status mutation is serialised.
		Availability slot = availabilityRepository.findByIdForUpdate(booking.getAvailability().getId())
				.orElseThrow(() -> new AvailabilityNotFoundException(booking.getAvailability().getId()));
		slot.setStatus(AvailabilityStatus.AVAILABLE);
		availabilityRepository.save(slot);

		bookingRepository.save(booking);
	}

	private boolean isParticipant(Booking booking, String email) {
		return booking.getCandidate().getEmail().equals(email) || booking.getMentor().getEmail().equals(email);
	}

}
