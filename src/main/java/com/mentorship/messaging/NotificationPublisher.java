package com.mentorship.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.mentorship.config.RabbitConfig;
import com.mentorship.event.NotificationEvent;

/**
 * Publishes notification events to RabbitMQ.
 *
 * <p>A broker failure is logged and swallowed, then reported through the return value. Edge Cases
 * "RabbitMQ unavailable" requires that a booking stay valid when notification delivery fails, and
 * callers only ever reach this class after their transaction has committed, so there is nothing
 * left to roll back. Letting the exception escape could only corrupt an already-successful booking.
 *
 * <p>The event itself is not lost when this fails: it is already recorded in the outbox, and stays
 * PENDING until a later attempt succeeds. See {@link NotificationOutbox}.
 */
@Component
public class NotificationPublisher {

	private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

	private final RabbitTemplate rabbitTemplate;

	public NotificationPublisher(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	/**
	 * @return true if the broker accepted the message, false if it could not be reached
	 */
	public boolean publish(NotificationEvent event) {
		try {
			rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, event.routingKey(), event);
			log.debug("Published {} for booking {}", event.eventType(), event.bookingId());
			return true;
		}
		catch (AmqpException ex) {
			// Deliberately not rethrown - see class javadoc.
			log.error("Could not publish {} (eventId={}); the originating operation is unaffected "
					+ "and the event stays pending in the outbox for retry", event.eventType(), event.eventId(), ex);
			return false;
		}
	}

}
