package com.mentorship.dto;

import com.mentorship.entity.ChatMessage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound STOMP payload. The size bound is enforced here and by the column length, so an
 * oversized message is rejected before it can reach the database.
 */
public record ChatMessageRequest(
		@NotBlank(message = "Message content must not be empty")
		@Size(max = ChatMessage.MAX_LENGTH, message = "Message content must not exceed "
				+ ChatMessage.MAX_LENGTH + " characters")
		String content) {
}
