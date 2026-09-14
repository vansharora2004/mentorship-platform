package com.mentorship.controller;

import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.mentorship.dto.ChatMessageRequest;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.service.ChatService;

/**
 * Receives chat frames on {@code /app/chat/{sessionId}} and broadcasts the stored message to
 * {@code /topic/session/{sessionId}}.
 *
 * <p>The message is persisted before it is broadcast, so what participants receive is exactly what
 * the transcript holds - including the generated id and server timestamp.
 *
 * <p>Rejections are sent back to the offending client alone, on
 * {@code /user/queue/errors}, rather than broadcast to the session.
 */
@Controller
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private final ChatService chatService;

	private final SimpMessagingTemplate messagingTemplate;

	public ChatController(ChatService chatService, SimpMessagingTemplate messagingTemplate) {
		this.chatService = chatService;
		this.messagingTemplate = messagingTemplate;
	}

	@MessageMapping("/chat/{sessionId}")
	public void handle(@DestinationVariable Long sessionId, @Payload ChatMessageRequest request,
			Principal principal) {
		ChatMessageResponse saved = chatService.post(principal.getName(), sessionId, request);
		messagingTemplate.convertAndSend("/topic/session/" + sessionId, saved);
	}

	@MessageExceptionHandler
	@SendToUser(destinations = "/queue/errors", broadcast = false)
	public String handleFailure(Exception ex) {
		log.warn("Rejected chat frame: {}", ex.getMessage());
		return ex.getMessage();
	}

}
