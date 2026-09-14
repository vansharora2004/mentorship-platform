package com.mentorship.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A message exchanged during a mentorship session. Persisted so chat history survives a
 * disconnect or restart, which the documented data model allows for.
 *
 * <p>Indexed by session and time because the only read pattern is "the transcript of one session,
 * in order".
 */
@Entity
@Table(name = "chat_messages",
		indexes = @Index(name = "idx_chat_messages_session_sent", columnList = "session_id, sent_at"))
public class ChatMessage {

	/** Matches the column length and the inbound validation constraint. */
	public static final int MAX_LENGTH = 2000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false)
	private Session session;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sender_id", nullable = false)
	private User sender;

	@Column(nullable = false, length = MAX_LENGTH)
	private String content;

	@Column(name = "sent_at", nullable = false, updatable = false)
	private Instant sentAt;

	@PrePersist
	void onCreate() {
		if (sentAt == null) {
			sentAt = Instant.now();
		}
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public User getSender() {
		return sender;
	}

	public void setSender(User sender) {
		this.sender = sender;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public void setSentAt(Instant sentAt) {
		this.sentAt = sentAt;
	}

}
