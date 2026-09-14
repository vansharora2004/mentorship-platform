package com.mentorship.service;

import java.time.Instant;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.BookingResponse;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.event.NotificationEvent;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.exception.BookingNotFoundException;
import com.mentorship.exception.InvalidBookingException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

@Service
public class BookingService {

	private final BookingRepository bookingRepository;

	private final AvailabilityRepository availabilityRepository;

	private final UserRepository userRepository;

	private final SessionRepository sessionRepository;

	private final CacheEvictor cacheEvictor;

	private final ApplicationEventPublisher eventPublisher;

	public BookingService(BookingRepository bookingRepository, AvailabilityRepository availabilityRepository,
			UserRepository userRepository, SessionRepository sessionRepository, CacheEvictor cacheEvictor,
			ApplicationEventPublisher eventPublisher) {
		this.bookingRepository = bookingRepository;
		this.availabilityRepository = availabilityRepository;
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.cacheEvictor = cacheEvictor;
		this.eventPublisher = eventPublisher;
	}

	// The slot row is locked FOR UPDATE before its status is read, so a competing booking
	// blocks here and re-reads the committed status once this transaction ends.
	// Redis is only evicted afterwards; it never participates in the booking decision.
	@CacheEvict(cacheNames = CacheEvictor.AVAILABILITY_CACHE, key = "#result.mentorId()")
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

		Booking saved = bookingRepository.save(booking);

		// Same transaction as the booking: if this fails, the booking and the slot
		// status change roll back together.
		Session session = new Session();
		session.setBooking(saved);
		session.setStartTime(saved.getStartTime());
		session.setEndTime(saved.getEndTime());
		session.setStatus(SessionStatus.SCHEDULED);
		sessionRepository.save(session);

		// Handed to RabbitMQ only after this transaction commits; the booking never waits on,
		// nor is rolled back by, notification delivery. See NotificationEventRelay.
		eventPublisher.publishEvent(NotificationEvent.bookingCreated(saved.getId(), candidate.getId(),
				saved.getMentor().getId()));

		return BookingResponse.from(saved);
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

		// Bookings created before sessions existed have none, hence ifPresent.
		sessionRepository.findByBookingId(booking.getId()).ifPresent(session -> {
			session.setStatus(SessionStatus.CANCELLED);
			sessionRepository.save(session);
		});

		bookingRepository.save(booking);

		// Booking stores the mentor directly, so no traversal or extra query is needed.
		cacheEvictor.evictAvailability(booking.getMentor().getId());

		eventPublisher.publishEvent(NotificationEvent.bookingCancelled(booking.getId(),
				booking.getCandidate().getId(), booking.getMentor().getId()));
	}

	private boolean isParticipant(Booking booking, String email) {
		return booking.getCandidate().getEmail().equals(email) || booking.getMentor().getEmail().equals(email);
	}

}
