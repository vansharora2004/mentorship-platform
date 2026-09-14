package com.mentorship.entity;

import java.time.Instant;

import com.mentorship.event.NotificationEvent;
import com.mentorship.event.NotificationEventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A notification event recorded durably in the same transaction as the operation that raised it.
 *
 * <p>This is what makes a notification survive a broker outage, as Edge Cases "RabbitMQ
 * unavailable" requires. The row commits with the booking, so an event can never be lost by a
 * RabbitMQ failure; publishing is a separate step afterwards, so RabbitMQ can never roll a booking
 * back. A row that has not been acknowledged stays {@link OutboxStatus#PENDING} and is retried by
 * the scheduler for as long as it takes.
 *
 * <p>The event's fields are stored as columns rather than as a serialized blob, so the outbox stays
 * queryable and does not depend on a message format that may change.
 *
 * <p>{@code eventId} is unique. Event ids are derived from ids that are never reused - a rebooking
 * gets a new booking id, and a reminder is guarded by {@code Session.reminderSentAt} - so the
 * constraint can only fire on a genuine double-record, which should fail the transaction.
 */
@Entity
@Table(name = "outbox_events", indexes = {
		@Index(name = "uq_outbox_events_event_id", columnList = "event_id", unique = true),
		@Index(name = "idx_outbox_events_status_created", columnList = "status, created_at") })
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, unique = true, length = 100)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 40)
	private NotificationEventType eventType;

	@Column(name = "booking_id")
	private Long bookingId;

	@Column(name = "session_id")
	private Long sessionId;

	@Column(name = "candidate_id")
	private Long candidateId;

	@Column(name = "mentor_id")
	private Long mentorId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxStatus status;

	/** Publish attempts made so far. Recorded for visibility; it never stops the retries. */
	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
		if (status == null) {
			status = OutboxStatus.PENDING;
		}
	}

	public static OutboxEvent pending(NotificationEvent event) {
		OutboxEvent record = new OutboxEvent();
		record.eventId = event.eventId();
		record.eventType = event.eventType();
		record.bookingId = event.bookingId();
		record.sessionId = event.sessionId();
		record.candidateId = event.candidateId();
		record.mentorId = event.mentorId();
		record.occurredAt = event.occurredAt();
		record.status = OutboxStatus.PENDING;
		return record;
	}

	/** Rebuilds the exact payload that was recorded, so a retry publishes the original event. */
	public NotificationEvent toEvent() {
		return new NotificationEvent(eventId, eventType, bookingId, sessionId, candidateId, mentorId, occurredAt);
	}

	public void markSent(Instant when) {
		this.status = OutboxStatus.SENT;
		this.sentAt = when;
		this.lastAttemptAt = when;
	}

	public void recordFailedAttempt(Instant when) {
		this.attempts++;
		this.lastAttemptAt = when;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEventId() {
		return eventId;
	}

	public void setEventId(String eventId) {
		this.eventId = eventId;
	}

	public NotificationEventType getEventType() {
		return eventType;
	}

	public void setEventType(NotificationEventType eventType) {
		this.eventType = eventType;
	}

	public Long getBookingId() {
		return bookingId;
	}

	public void setBookingId(Long bookingId) {
		this.bookingId = bookingId;
	}

	public Long getSessionId() {
		return sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public Long getCandidateId() {
		return candidateId;
	}

	public void setCandidateId(Long candidateId) {
		this.candidateId = candidateId;
	}

	public Long getMentorId() {
		return mentorId;
	}

	public void setMentorId(Long mentorId) {
		this.mentorId = mentorId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public void setOccurredAt(Instant occurredAt) {
		this.occurredAt = occurredAt;
	}

	public OutboxStatus getStatus() {
		return status;
	}

	public void setStatus(OutboxStatus status) {
		this.status = status;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}

	public Instant getLastAttemptAt() {
		return lastAttemptAt;
	}

	public void setLastAttemptAt(Instant lastAttemptAt) {
		this.lastAttemptAt = lastAttemptAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public void setSentAt(Instant sentAt) {
		this.sentAt = sentAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}
