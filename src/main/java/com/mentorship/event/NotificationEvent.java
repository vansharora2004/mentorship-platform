package com.mentorship.event;

import java.time.Instant;

/**
 * Payload published to RabbitMQ. Deliberately carries only identifiers and no entity graph, so a
 * consumer re-reads current state from PostgreSQL rather than trusting a possibly stale message.
 *
 * <p>{@code eventId} is derived from the event type and the subject id rather than being random.
 * Redelivery of the same logical event therefore produces the same id, which is what makes the
 * consumer idempotent - see Edge Cases "Duplicate message".
 */
public record NotificationEvent(String eventId, NotificationEventType eventType, Long bookingId, Long sessionId,
		Long candidateId, Long mentorId, Instant occurredAt) {

	public static NotificationEvent bookingCreated(Long bookingId, Long candidateId, Long mentorId) {
		return new NotificationEvent(id(NotificationEventType.BOOKING_CREATED, bookingId),
				NotificationEventType.BOOKING_CREATED, bookingId, null, candidateId, mentorId, Instant.now());
	}

	public static NotificationEvent bookingCancelled(Long bookingId, Long candidateId, Long mentorId) {
		return new NotificationEvent(id(NotificationEventType.BOOKING_CANCELLED, bookingId),
				NotificationEventType.BOOKING_CANCELLED, bookingId, null, candidateId, mentorId, Instant.now());
	}

	public static NotificationEvent sessionReminder(Long sessionId, Long bookingId, Long candidateId, Long mentorId) {
		return new NotificationEvent(id(NotificationEventType.SESSION_REMINDER, sessionId),
				NotificationEventType.SESSION_REMINDER, bookingId, sessionId, candidateId, mentorId, Instant.now());
	}

	private static String id(NotificationEventType type, Long subjectId) {
		return type.name() + ":" + subjectId;
	}

	public String routingKey() {
		return "notification." + eventType.name().toLowerCase();
	}

}
