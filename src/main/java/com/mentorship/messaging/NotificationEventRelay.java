package com.mentorship.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mentorship.event.NotificationEvent;

/**
 * Carries a domain event from the transaction that raised it to RabbitMQ, in two separate steps.
 *
 * <ol>
 * <li><b>BEFORE_COMMIT</b> - the event is written to the outbox <em>inside</em> the originating
 * transaction, so it commits atomically with the booking. A broker that is down at this moment
 * cannot lose the event, and a booking that rolls back leaves no orphan record behind.</li>
 * <li><b>AFTER_COMMIT</b> - the event is published. The transaction is already closed, so a broker
 * outage cannot roll the booking back; the row simply stays PENDING and
 * {@code OutboxRetryJob} publishes it later.</li>
 * </ol>
 *
 * <p>Between them these satisfy both halves of Edge Cases "RabbitMQ unavailable": the booking is
 * never damaged by a notification failure, and the notification is never lost by one.
 *
 * <p>Services depend only on Spring's {@code ApplicationEventPublisher} and stay free of AMQP and
 * outbox types, which also keeps their unit tests broker-free.
 */
@Component
public class NotificationEventRelay {

	private final NotificationPublisher publisher;

	private final NotificationOutbox outbox;

	public NotificationEventRelay(NotificationPublisher publisher, NotificationOutbox outbox) {
		this.publisher = publisher;
		this.outbox = outbox;
	}

	/**
	 * Runs inside the originating transaction. A failure here fails that transaction on purpose:
	 * an operation whose notification cannot be recorded has not really succeeded.
	 */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void record(NotificationEvent event) {
		outbox.record(event);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(NotificationEvent event) {
		if (publisher.publish(event)) {
			outbox.markSent(event.eventId());
		}
		else {
			outbox.markAttemptFailed(event.eventId());
		}
	}

}
