package com.mentorship.messaging;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.entity.OutboxEvent;
import com.mentorship.event.NotificationEvent;
import com.mentorship.repository.OutboxEventRepository;

/**
 * Durable record of every notification event, and the bookkeeping that goes with publishing it.
 *
 * <p>Recording joins the caller's transaction; marking the result does not. That split is the whole
 * point of the outbox:
 *
 * <ul>
 * <li>The row commits atomically with the booking, so an event cannot be lost by a broker that is
 * down at the moment it is raised.</li>
 * <li>Marking the row sent runs in its own transaction, because by then the booking transaction has
 * already committed and closed.</li>
 * </ul>
 */
@Component
public class NotificationOutbox {

	private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

	private final OutboxEventRepository outboxEventRepository;

	public NotificationOutbox(OutboxEventRepository outboxEventRepository) {
		this.outboxEventRepository = outboxEventRepository;
	}

	/**
	 * Persists the event as PENDING inside the caller's transaction. MANDATORY on purpose: if this
	 * is ever called outside a transaction the guarantee is gone, and failing loudly is better than
	 * recording an event that might not commit with the operation that caused it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(NotificationEvent event) {
		outboxEventRepository.save(OutboxEvent.pending(event));
		log.debug("Recorded {} in the outbox", event.eventId());
	}

	/**
	 * Marks an event as delivered. Runs in its own transaction because callers reach it after their
	 * original transaction has committed. Idempotent: marking an already-sent event changes
	 * nothing, so a retry that overlaps the original publish is harmless.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markSent(String eventId) {
		outboxEventRepository.findByEventId(eventId)
				.ifPresent(record -> {
					record.markSent(Instant.now());
					outboxEventRepository.save(record);
				});
	}

	/**
	 * Records that a publish attempt failed. The event stays PENDING and therefore stays
	 * recoverable; the counter exists for visibility, not to give up.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markAttemptFailed(String eventId) {
		outboxEventRepository.findByEventId(eventId)
				.ifPresent(record -> {
					record.recordFailedAttempt(Instant.now());
					outboxEventRepository.save(record);
					log.warn("Publish attempt {} failed for {}; it remains pending for retry",
							record.getAttempts(), eventId);
				});
	}

}
