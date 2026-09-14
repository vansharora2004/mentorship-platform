package com.mentorship.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.service.ChatService;

/**
 * Reads a session transcript over REST, so a participant who reconnects can recover the
 * conversation that was exchanged while they were away.
 *
 * <p>New endpoint introduced by Phase 11; no existing contract is altered. Access is limited to the
 * session's own two participants by the same rule the WebSocket path uses.
 */
@RestController
@RequestMapping("/api/sessions")
public class ChatHistoryController {

	private final ChatService chatService;

	public ChatHistoryController(ChatService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/{sessionId}/messages")
	public ResponseEntity<List<ChatMessageResponse>> history(@PathVariable Long sessionId, Principal principal) {
		return ResponseEntity.ok(chatService.history(principal.getName(), sessionId));
	}

}
