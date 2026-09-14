package com.mentorship.dto;

import java.time.Instant;

import com.mentorship.entity.ChatMessage;

/** Broadcast to /topic/session/{sessionId}. Carries the sender's name so clients need no lookup. */
public record ChatMessageResponse(Long id, Long sessionId, Long senderId, String senderName, String content,
		Instant sentAt) {

	public static ChatMessageResponse from(ChatMessage message) {
		return new ChatMessageResponse(
				message.getId(),
				message.getSession().getId(),
				message.getSender().getId(),
				message.getSender().getName(),
				message.getContent(),
				message.getSentAt());
	}

}
