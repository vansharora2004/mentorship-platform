package com.mentorship.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentorship.config.SecurityConfig;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.entity.Role;
import com.mentorship.exception.GlobalExceptionHandler;
import com.mentorship.exception.SessionNotFoundException;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.ChatService;

@WebMvcTest(controllers = ChatHistoryController.class)
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + ChatHistoryControllerTest.SECRET,
		"app.jwt.expiration-ms=3600000" })
class ChatHistoryControllerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private static final String CANDIDATE = "candidate@example.com";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private ChatService chatService;

	@Test
	void returnsTheTranscriptForAParticipant() throws Exception {
		given(chatService.history(eq(CANDIDATE), eq(55L))).willReturn(List.of(
				new ChatMessageResponse(1L, 55L, 10L, "Candidate", "hello", Instant.parse("2026-01-01T10:00:00Z")),
				new ChatMessageResponse(2L, 55L, 20L, "Mentor", "hi there", Instant.parse("2026-01-01T10:00:05Z"))));

		mockMvc.perform(get("/api/sessions/55/messages").header("Authorization", bearer(CANDIDATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].content").value("hello"))
				.andExpect(jsonPath("$[1].senderName").value("Mentor"));
	}

	@Test
	void rejectsAnUnauthenticatedRequest() throws Exception {
		mockMvc.perform(get("/api/sessions/55/messages"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsANonParticipantWithForbidden() throws Exception {
		willThrow(new AccessDeniedException("not a participant"))
				.given(chatService).history(eq(CANDIDATE), eq(55L));

		mockMvc.perform(get("/api/sessions/55/messages").header("Authorization", bearer(CANDIDATE)))
				.andExpect(status().isForbidden());
	}

	@Test
	void reportsAnUnknownSessionAsNotFound() throws Exception {
		willThrow(new SessionNotFoundException(55L))
				.given(chatService).history(eq(CANDIDATE), eq(55L));

		mockMvc.perform(get("/api/sessions/55/messages").header("Authorization", bearer(CANDIDATE)))
				.andExpect(status().isNotFound());
	}

	private String bearer(String email) {
		return "Bearer " + jwtService.generateToken(email, Role.CANDIDATE);
	}

}
