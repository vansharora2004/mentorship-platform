package com.mentorship.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mentorship.entity.OutboxEvent;
import com.mentorship.entity.OutboxStatus;
import com.mentorship.messaging.NotificationOutbox;
import com.mentorship.messaging.NotificationPublisher;
import com.mentorship.repository.OutboxEventRepository;

/**
 * Publishes notification events that RabbitMQ never acknowledged.
 *
 * <p>This is the half of Edge Cases "RabbitMQ unavailable" that makes an event <em>recoverable</em>
 * rather than merely harmless: while the broker is down, events accumulate as PENDING, and once it
 * returns they are delivered oldest first.
 *
 * <p>Deliberately not {@code @Transactional}. Publishing is a network call, and holding a database
 * transaction open across a batch of them would be worse than useless if the broker is hanging.
 * Each row's outcome is recorded in its own short transaction by {@link NotificationOutbox}.
 *
 * <p>Safe to run again, and safe to run twice at once:
 *
 * <ul>
 * <li>Only PENDING rows are selected, so an event already delivered is never republished.</li>
 * <li>A grace period excludes rows the after-commit publish is still working on.</li>
 * <li>Publishing the same event twice is harmless anyway - the consumer deduplicates on
 * (eventId, recipientId), so it produces no second notification.</li>
 * </ul>
 *
 * <p>There is no attempt limit. An event stays recoverable for as long as it takes; the attempt
 * counter exists for visibility, not to give up.
 */
@Component
public class OutboxRetryJob {

	private static final Logger log = LoggerFactory.getLogger(OutboxRetryJob.class);

	private final OutboxEventRepository outboxEventRepository;

	private final NotificationPublisher publisher;

	private final NotificationOutbox outbox;

	private final Duration retryAfter;

	private final int batchSize;

	public OutboxRetryJob(OutboxEventRepository outboxEventRepository, NotificationPublisher publisher,
			NotificationOutbox outbox, @Value("${app.outbox.retry-after}") Duration retryAfter,
			@Value("${app.outbox.batch-size}") int batchSize) {
		this.outboxEventRepository = outboxEventRepository;
		this.publisher = publisher;
		this.outbox = outbox;
		this.retryAfter = retryAfter;
		this.batchSize = batchSize;
	}

	/**
	 * @return how many pending events were published on this run
	 */
	@Scheduled(fixedDelayString = "${app.outbox.retry-interval:60000}")
	public int publishPendingEvents() {
		List<OutboxEvent> pending = outboxEventRepository.findRetryable(OutboxStatus.PENDING,
				Instant.now().minus(retryAfter), PageRequest.of(0, batchSize));

		if (pending.isEmpty()) {
			return 0;
		}

		int published = 0;
		for (OutboxEvent record : pending) {
			if (publisher.publish(record.toEvent())) {
				outbox.markSent(record.getEventId());
				published++;
			}
			else {
				outbox.markAttemptFailed(record.getEventId());
				// The broker is unreachable; the rest of this batch would fail the same way.
				break;
			}
		}

		log.info("Republished {} of {} pending notification event(s)", published, pending.size());
		return published;
	}

}
