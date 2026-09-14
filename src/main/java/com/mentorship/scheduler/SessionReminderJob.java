package com.mentorship.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.entity.Booking;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.event.NotificationEvent;
import com.mentorship.repository.SessionRepository;

/**
 * Emits SESSION_REMINDER for sessions starting soon.
 *
 * <pre>
 * every minute -&gt; find sessions starting within the lead time -&gt; publish -&gt; RabbitMQ -&gt; consumer
 * </pre>
 *
 * <p>Each session is reminded once: {@code reminderSentAt} is stamped in the same transaction that
 * publishes, so the row is only claimed if the publish decision commits. Should two application
 * instances run this job concurrently, the reminder's event id is derived from the session id, so
 * the idempotent consumer still produces exactly one notification per recipient. Proper distributed
 * locking is noted as future work in the documentation.
 */
@Component
public class SessionReminderJob {

	private static final Logger log = LoggerFactory.getLogger(SessionReminderJob.class);

	private final SessionRepository sessionRepository;

	private final ApplicationEventPublisher eventPublisher;

	private final Duration leadTime;

	private final int batchSize;

	public SessionReminderJob(SessionRepository sessionRepository, ApplicationEventPublisher eventPublisher,
			@Value("${app.scheduler.reminder-lead-time}") Duration leadTime,
			@Value("${app.scheduler.batch-size}") int batchSize) {
		this.sessionRepository = sessionRepository;
		this.eventPublisher = eventPublisher;
		this.leadTime = leadTime;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${app.scheduler.reminder-interval:60000}")
	@Transactional
	public int sendDueReminders() {
		Instant now = Instant.now();
		List<Session> due = sessionRepository.findDueForReminder(SessionStatus.SCHEDULED, now, now.plus(leadTime),
				PageRequest.of(0, batchSize));

		due.forEach(session -> {
			Booking booking = session.getBooking();
			session.setReminderSentAt(now);
			eventPublisher.publishEvent(NotificationEvent.sessionReminder(session.getId(), booking.getId(),
					booking.getCandidate().getId(), booking.getMentor().getId()));
		});

		if (!due.isEmpty()) {
			sessionRepository.saveAll(due);
			log.info("Queued {} session reminder(s)", due.size());
		}

		return due.size();
	}

}
