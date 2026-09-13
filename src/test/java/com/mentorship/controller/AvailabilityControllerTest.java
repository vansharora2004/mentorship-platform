package com.mentorship.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentorship.config.SecurityConfig;
import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Role;
import com.mentorship.exception.AvailabilityConflictException;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.GlobalExceptionHandler;
import com.mentorship.exception.InvalidAvailabilityException;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.AvailabilityService;

@WebMvcTest(controllers = AvailabilityController.class)
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + AvailabilityControllerTest.SECRET,
		"app.jwt.expiration-ms=3600000" })
class AvailabilityControllerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");

	private static final Instant END = Instant.parse("2030-01-01T11:00:00Z");

	private static final String VALID_BODY = """
			{"startTime":"2030-01-01T10:00:00Z","endTime":"2030-01-01T11:00:00Z"}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private AvailabilityService availabilityService;

	private String mentor() {
		return "Bearer " + jwtService.generateToken("mentor@example.com", Role.MENTOR);
	}

	private String candidate() {
		return "Bearer " + jwtService.generateToken("candidate@example.com", Role.CANDIDATE);
	}

	private AvailabilityResponse slot() {
		return new AvailabilityResponse(1L, 7L, START, END, AvailabilityStatus.AVAILABLE, Instant.now());
	}

	@Test
	void mentorCreatesSlot() throws Exception {
		given(availabilityService.create(eq("mentor@example.com"), any())).willReturn(slot());

		mockMvc.perform(post("/api/availability").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.mentorId").value(7))
				.andExpect(jsonPath("$.status").value("AVAILABLE"));
	}

	@Test
	void candidateCannotCreateSlot() throws Exception {
		mockMvc.perform(post("/api/availability").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());

		verify(availabilityService, never()).create(any(), any());
	}

	@Test
	void anonymousCannotCreateSlot() throws Exception {
		mockMvc.perform(post("/api/availability").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void startAfterEndIsRejectedByValidation() throws Exception {
		mockMvc.perform(post("/api/availability").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"startTime":"2030-01-01T11:00:00Z","endTime":"2030-01-01T10:00:00Z"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.validRange").exists());

		verify(availabilityService, never()).create(any(), any());
	}

	@Test
	void missingTimesAreRejectedByValidation() throws Exception {
		mockMvc.perform(post("/api/availability").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.startTime").exists())
				.andExpect(jsonPath("$.fields.endTime").exists());
	}

	@Test
	void pastSlotIsRejected() throws Exception {
		given(availabilityService.create(any(), any()))
				.willThrow(new InvalidAvailabilityException("Availability cannot start in the past"));

		mockMvc.perform(post("/api/availability").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest());
	}

	@Test
	void overlappingSlotIsRejectedWithConflict() throws Exception {
		given(availabilityService.create(any(), any())).willThrow(AvailabilityConflictException.overlapping());

		mockMvc.perform(post("/api/availability").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict());
	}

	@Test
	void mentorListsOwnSlots() throws Exception {
		given(availabilityService.listOwn("mentor@example.com")).willReturn(List.of(slot()));

		mockMvc.perform(get("/api/availability").header("Authorization", mentor()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(1));
	}

	@Test
	void candidateCannotListTheOwnSlotsEndpoint() throws Exception {
		mockMvc.perform(get("/api/availability").header("Authorization", candidate()))
				.andExpect(status().isForbidden());
	}

	@Test
	void mentorUpdatesSlot() throws Exception {
		given(availabilityService.update(eq("mentor@example.com"), eq(1L), any())).willReturn(slot());

		mockMvc.perform(put("/api/availability/1").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	void updatingAMissingSlotReturnsNotFound() throws Exception {
		given(availabilityService.update(any(), eq(404L), any())).willThrow(new AvailabilityNotFoundException(404L));

		mockMvc.perform(put("/api/availability/404").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isNotFound());
	}

	@Test
	void updatingAnotherMentorsSlotReturnsForbidden() throws Exception {
		given(availabilityService.update(any(), eq(1L), any()))
				.willThrow(new AccessDeniedException("You may only modify your own availability"));

		mockMvc.perform(put("/api/availability/1").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());
	}

	@Test
	void candidateCannotUpdateSlot() throws Exception {
		mockMvc.perform(put("/api/availability/1").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());

		verify(availabilityService, never()).update(any(), any(), any());
	}

	@Test
	void mentorDeletesSlot() throws Exception {
		mockMvc.perform(delete("/api/availability/1").header("Authorization", mentor()))
				.andExpect(status().isNoContent());

		verify(availabilityService).delete("mentor@example.com", 1L);
	}

	@Test
	void deletingAMissingSlotReturnsNotFound() throws Exception {
		willThrow(new AvailabilityNotFoundException(404L)).given(availabilityService).delete(any(), eq(404L));

		mockMvc.perform(delete("/api/availability/404").header("Authorization", mentor()))
				.andExpect(status().isNotFound());
	}

	@Test
	void deletingAnotherMentorsSlotReturnsForbidden() throws Exception {
		willThrow(new AccessDeniedException("nope")).given(availabilityService).delete(any(), eq(1L));

		mockMvc.perform(delete("/api/availability/1").header("Authorization", mentor()))
				.andExpect(status().isForbidden());
	}

	@Test
	void candidateCannotDeleteSlot() throws Exception {
		mockMvc.perform(delete("/api/availability/1").header("Authorization", candidate()))
				.andExpect(status().isForbidden());

		verify(availabilityService, never()).delete(any(), any());
	}

}
