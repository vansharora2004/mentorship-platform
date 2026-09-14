package com.mentorship.service;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.ChatMessageRequest;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.entity.ChatMessage;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.exception.InvalidChatMessageException;
import com.mentorship.exception.SessionNotFoundException;
import com.mentorship.repository.ChatMessageRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

/**
 * Chat rules, kept out of the WebSocket controller so they can be unit tested without a broker.
 *
 * <p>Every documented WebSocket check lives here or in the STOMP interceptor:
 * the session must exist, the sender must be one of its two participants, the session must still be
 * open, and the content must be non-blank and within the length bound.
 */
@Service
public class ChatService {

	private final ChatMessageRepository chatMessageRepository;

	private final SessionRepository sessionRepository;

	private final UserRepository userRepository;

	public ChatService(ChatMessageRepository chatMessageRepository, SessionRepository sessionRepository,
			UserRepository userRepository) {
		this.chatMessageRepository = chatMessageRepository;
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
	}

	@Transactional
	public ChatMessageResponse post(String senderEmail, Long sessionId, ChatMessageRequest request) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));

		User sender = userRepository.findByEmail(senderEmail)
				.orElseThrow(() -> new AccessDeniedException("Unknown sender"));

		if (!isParticipant(session, sender.getId())) {
			throw new AccessDeniedException("You are not a participant of this session");
		}
		if (!isOpen(session)) {
			throw new InvalidChatMessageException("Session " + sessionId + " is no longer open for chat");
		}

		String content = request == null || request.content() == null ? null : request.content().trim();
		if (content == null || content.isEmpty()) {
			throw new InvalidChatMessageException("Message content must not be empty");
		}
		if (content.length() > ChatMessage.MAX_LENGTH) {
			throw new InvalidChatMessageException(
					"Message content must not exceed " + ChatMessage.MAX_LENGTH + " characters");
		}

		ChatMessage message = new ChatMessage();
		message.setSession(session);
		message.setSender(sender);
		message.setContent(content);

		return ChatMessageResponse.from(chatMessageRepository.save(message));
	}

	@Transactional(readOnly = true)
	public List<ChatMessageResponse> history(String email, Long sessionId) {
		Session session = sessionRepository.findById(sessionId)
				.orElseThrow(() -> new SessionNotFoundException(sessionId));

		User user = userRepository.findByEmail(email)
				.orElseThrow(() -> new AccessDeniedException("Unknown user"));

		if (!isParticipant(session, user.getId())) {
			throw new AccessDeniedException("You are not a participant of this session");
		}

		return chatMessageRepository.findBySessionIdOrderBySentAtAsc(sessionId).stream()
				.map(ChatMessageResponse::from)
				.toList();
	}

	/**
	 * Used by the STOMP interceptor to authorise a subscription, so nobody can read another pair's
	 * conversation by guessing a session id.
	 */
	@Transactional(readOnly = true)
	public boolean isParticipant(Long sessionId, String email) {
		return sessionRepository.findById(sessionId)
				.flatMap(session -> userRepository.findByEmail(email)
						.map(user -> isParticipant(session, user.getId())))
				.orElse(false);
	}

	private boolean isParticipant(Session session, Long userId) {
		return session.getBooking().getCandidate().getId().equals(userId)
				|| session.getBooking().getMentor().getId().equals(userId);
	}

	// COMPLETED and CANCELLED sessions reject new messages; history stays readable.
	private boolean isOpen(Session session) {
		return session.getStatus() == SessionStatus.SCHEDULED || session.getStatus() == SessionStatus.ACTIVE;
	}

}
