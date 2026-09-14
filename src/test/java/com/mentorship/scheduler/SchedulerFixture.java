package com.mentorship.scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.NotificationRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;
import com.mentorship.service.MentorService;

/**
 * Builds sessions at arbitrary points in time for the Phase 10 jobs.
 *
 * <p>Rows are created through the repositories rather than through BookingService, because the
 * booking rules correctly refuse a slot in the past - and a scheduler that advances or completes
 * sessions can only be tested against sessions whose times have already passed. The booking path
 * itself is covered by the Phase 6 and 7 tests.
 */
abstract class SchedulerFixture {

	@Autowired
	private MentorService mentorService;

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
	private NotificationRepository notificationRepository;

	private TransactionTemplate transactionTemplate;

	private final List<Long> createdSessionIds = new ArrayList<>();

	protected String mentorEmail;

	protected String candidateEmail;

	protected Long mentorId;

	protected Long candidateId;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void seedParticipants() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mentorEmail = "sched.mentor." + suffix + "@example.com";
		candidateEmail = "sched.candidate." + suffix + "@example.com";
		createdSessionIds.clear();

		transactionTemplate.executeWithoutResult(status -> {
			mentorId = persistUser(mentorEmail, "Sched Mentor", Role.MENTOR).getId();
			candidateId = persistUser(candidateEmail, "Sched Candidate", Role.CANDIDATE).getId();
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

	protected Session createSession(Instant start, Instant end, SessionStatus status) {
		return createSession(start, end, status, BookingStatus.CONFIRMED);
	}

	protected Session createSession(Instant start, Instant end, SessionStatus status, BookingStatus bookingStatus) {
		return transactionTemplate.execute(tx -> {
			MentorProfile profile = mentorProfileRepository.findByUserEmail(mentorEmail).orElseThrow();
			User mentor = userRepository.findByEmail(mentorEmail).orElseThrow();
			User candidate = userRepository.findByEmail(candidateEmail).orElseThrow();

			Availability slot = new Availability();
			slot.setMentorProfile(profile);
			slot.setStartTime(start);
			slot.setEndTime(end);
			slot.setStatus(AvailabilityStatus.BOOKED);
			slot = availabilityRepository.save(slot);

			Booking booking = new Booking();
			booking.setCandidate(candidate);
			booking.setMentor(mentor);
			booking.setAvailability(slot);
			booking.setStartTime(start);
			booking.setEndTime(end);
			booking.setStatus(bookingStatus);
			booking = bookingRepository.save(booking);

			Session session = new Session();
			session.setBooking(booking);
			session.setStartTime(start);
			session.setEndTime(end);
			session.setStatus(status);
			session = sessionRepository.save(session);

			createdSessionIds.add(session.getId());
			return session;
		});
	}

	protected Session reload(Long sessionId) {
		return sessionRepository.findById(sessionId).orElseThrow();
	}

	protected Booking reloadBooking(Long bookingId) {
		return bookingRepository.findById(bookingId).orElseThrow();
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
