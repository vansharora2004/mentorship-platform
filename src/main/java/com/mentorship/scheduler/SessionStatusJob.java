package com.mentorship.scheduler;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.repository.BookingRepository;
import com.mentorship.repository.SessionRepository;

/**
 * Advances session state as time passes: SCHEDULED to ACTIVE at the start time, then to COMPLETED
 * once the end time is behind us. A CONFIRMED booking is completed alongside its session, which is
 * the final step of the documented end-to-end flow.
 *
 * <p>CANCELLED sessions are never touched, and the queries select only rows still in an open state,
 * so re-running the job - after a restart, or on an instance that ran it a moment ago - changes
 * nothing further. That is what makes a missed tick self-healing: the next run picks up everything
 * whose time has since passed.
 */
@Component
public class SessionStatusJob {

	private static final Logger log = LoggerFactory.getLogger(SessionStatusJob.class);

	private static final List<SessionStatus> OPEN_STATUSES = List.of(SessionStatus.SCHEDULED, SessionStatus.ACTIVE);

	private final SessionRepository sessionRepository;

	private final BookingRepository bookingRepository;

	private final int batchSize;

	public SessionStatusJob(SessionRepository sessionRepository, BookingRepository bookingRepository,
			@Value("${app.scheduler.batch-size}") int batchSize) {
		this.sessionRepository = sessionRepository;
		this.bookingRepository = bookingRepository;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${app.scheduler.status-interval:60000}")
	@Transactional
	public int advanceSessionStates() {
		Instant now = Instant.now();
		return activateStartedSessions(now) + completeFinishedSessions(now);
	}

	private int activateStartedSessions(Instant now) {
		List<Session> starting = sessionRepository.findDueToStart(SessionStatus.SCHEDULED, now,
				PageRequest.of(0, batchSize));
		if (starting.isEmpty()) {
			return 0;
		}

		starting.forEach(session -> session.setStatus(SessionStatus.ACTIVE));
		sessionRepository.saveAll(starting);
		log.info("Activated {} session(s)", starting.size());
		return starting.size();
	}

	private int completeFinishedSessions(Instant now) {
		List<Session> finished = sessionRepository.findDueToComplete(OPEN_STATUSES, now,
				PageRequest.of(0, batchSize));
		if (finished.isEmpty()) {
			return 0;
		}

		finished.forEach(session -> {
			session.setStatus(SessionStatus.COMPLETED);

			// A cancelled booking cannot reach here - cancelling also cancels its session -
			// but the guard keeps the job honest if that ever changes.
			Booking booking = session.getBooking();
			if (booking.getStatus() == BookingStatus.CONFIRMED) {
				booking.setStatus(BookingStatus.COMPLETED);
				bookingRepository.save(booking);
			}
		});
		sessionRepository.saveAll(finished);
		log.info("Completed {} session(s)", finished.size());
		return finished.size();
	}

}
