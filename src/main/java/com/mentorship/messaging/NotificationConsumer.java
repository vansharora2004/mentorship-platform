package com.mentorship.messaging;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.config.RabbitConfig;
import com.mentorship.entity.Notification;
import com.mentorship.event.NotificationEvent;
import com.mentorship.repository.NotificationRepository;

/**
 * Turns a notification event into a persisted notification for each participant.
 *
 * <p>Idempotent by construction: ({@code eventId}, {@code recipientId}) is unique, so a redelivered
 * message produces no second notification. The pre-check keeps the common case cheap; the caught
 * constraint violation covers two deliveries racing each other, which the pre-check alone cannot.
 *
 * <p>A payload that can never succeed - missing ids, unknown participants - is rejected without
 * requeue so it lands on the dead-letter queue immediately instead of cycling through retries.
 */
@Component
public class NotificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

	private final NotificationRepository notificationRepository;

	public NotificationConsumer(NotificationRepository notificationRepository) {
		this.notificationRepository = notificationRepository;
	}

	@RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
	@Transactional
	public void onNotificationEvent(NotificationEvent event) {
		if (event == null || event.eventId() == null || event.eventType() == null) {
			throw new AmqpRejectAndDontRequeueException("Malformed notification event");
		}

		Set<Long> recipients = new LinkedHashSet<>();
		if (event.candidateId() != null) {
			recipients.add(event.candidateId());
		}
		if (event.mentorId() != null) {
			recipients.add(event.mentorId());
		}
		if (recipients.isEmpty()) {
			throw new AmqpRejectAndDontRequeueException(
					"Notification event " + event.eventId() + " names no recipient");
		}

		recipients.forEach(recipientId -> deliver(event, recipientId));
	}

	private void deliver(NotificationEvent event, Long recipientId) {
		if (notificationRepository.existsByEventIdAndRecipientId(event.eventId(), recipientId)) {
			log.debug("Skipping duplicate {} for recipient {}", event.eventId(), recipientId);
			return;
		}

		Notification notification = new Notification();
		notification.setEventId(event.eventId());
		notification.setEventType(event.eventType());
		notification.setRecipientId(recipientId);
		notification.setBookingId(event.bookingId());
		notification.setSessionId(event.sessionId());
		notification.setMessage(describe(event));

		try {
			notificationRepository.saveAndFlush(notification);
			log.info("Delivered {} to user {}", event.eventType(), recipientId);
		}
		catch (DataIntegrityViolationException ex) {
			// Another delivery of the same event won the race; the notification already exists.
			log.debug("Concurrent duplicate {} for recipient {} ignored", event.eventId(), recipientId);
		}
	}

	private String describe(NotificationEvent event) {
		return switch (event.eventType()) {
			case BOOKING_CREATED -> "Your mentorship session has been booked (booking " + event.bookingId() + ").";
			case BOOKING_CANCELLED -> "Your mentorship session has been cancelled (booking " + event.bookingId() + ").";
			case SESSION_REMINDER -> "Your mentorship session is starting soon (session " + event.sessionId() + ").";
		};
	}

}
