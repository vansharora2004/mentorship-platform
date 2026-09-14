package com.mentorship.entity;

import java.time.Instant;

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
import jakarta.persistence.UniqueConstraint;

/**
 * The record a notification consumer writes when it handles an event.
 *
 * <p>One event notifies both participants, so the idempotency key is
 * ({@code eventId}, {@code recipientId}) rather than {@code eventId} alone: a redelivered message
 * hits the unique constraint instead of producing a second notification for the same person.
 * Recipient and subject are stored as plain ids rather than associations, because a notification is
 * a historical fact that must survive even if the booking it refers to is later removed.
 */
@Entity
@Table(name = "notifications",
		uniqueConstraints = @UniqueConstraint(name = "uq_notifications_event_recipient",
				columnNames = { "event_id", "recipient_id" }),
		indexes = @Index(name = "idx_notifications_recipient", columnList = "recipient_id"))
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, length = 100)
	private String eventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 40)
	private NotificationEventType eventType;

	@Column(name = "recipient_id", nullable = false)
	private Long recipientId;

	@Column(name = "booking_id")
	private Long bookingId;

	@Column(name = "session_id")
	private Long sessionId;

	@Column(nullable = false, length = 500)
	private String message;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
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

	public Long getRecipientId() {
		return recipientId;
	}

	public void setRecipientId(Long recipientId) {
		this.recipientId = recipientId;
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

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}
