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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.mentorship.dto.BookingResponse;
import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Role;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.BookingConflictException;
import com.mentorship.exception.BookingNotFoundException;
import com.mentorship.exception.GlobalExceptionHandler;
import com.mentorship.exception.InvalidBookingException;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.BookingService;

@WebMvcTest(controllers = BookingController.class)
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + BookingControllerTest.SECRET,
		"app.jwt.expiration-ms=3600000" })
class BookingControllerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private static final String VALID_BODY = """
			{"availabilityId":10}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private BookingService bookingService;

	private String candidate() {
		return "Bearer " + jwtService.generateToken("candidate@example.com", Role.CANDIDATE);
	}

	private String mentor() {
		return "Bearer " + jwtService.generateToken("mentor@example.com", Role.MENTOR);
	}

	private BookingResponse booking() {
		return new BookingResponse(1L, 1L, "Mentor Mentorson", 2L, "Candy Date", 10L,
				Instant.parse("2030-01-01T10:00:00Z"), Instant.parse("2030-01-01T11:00:00Z"),
				BookingStatus.CONFIRMED, Instant.now(), Instant.now());
	}

	@Test
	void candidateCreatesBooking() throws Exception {
		given(bookingService.create(eq("candidate@example.com"), any())).willReturn(booking());

		mockMvc.perform(post("/api/bookings").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.availabilityId").value(10));
	}

	@Test
	void mentorCannotCreateBooking() throws Exception {
		mockMvc.perform(post("/api/bookings").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());

		verify(bookingService, never()).create(any(), any());
	}

	@Test
	void anonymousCannotCreateBooking() throws Exception {
		mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void missingAvailabilityIdIsRejected() throws Exception {
		mockMvc.perform(post("/api/bookings").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.availabilityId").exists());

		verify(bookingService, never()).create(any(), any());
	}

	@Test
	void unknownSlotReturnsNotFound() throws Exception {
		given(bookingService.create(any(), any())).willThrow(new AvailabilityNotFoundException(10L));

		mockMvc.perform(post("/api/bookings").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isNotFound());
	}

	@Test
	void unavailableSlotReturnsConflict() throws Exception {
		given(bookingService.create(any(), any())).willThrow(BookingConflictException.slotUnavailable());

		mockMvc.perform(post("/api/bookings").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict());
	}

	@Test
	void pastSlotReturnsBadRequest() throws Exception {
		given(bookingService.create(any(), any()))
				.willThrow(new InvalidBookingException("Cannot book a slot that has already started"));

		mockMvc.perform(post("/api/bookings").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest());
	}

	@Test
	void candidateListsBookings() throws Exception {
		given(bookingService.listForUser("candidate@example.com")).willReturn(List.of(booking()));

		mockMvc.perform(get("/api/bookings").header("Authorization", candidate()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(1));
	}

	@Test
	void mentorListsBookings() throws Exception {
		given(bookingService.listForUser("mentor@example.com")).willReturn(List.of(booking()));

		mockMvc.perform(get("/api/bookings").header("Authorization", mentor()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].mentorId").value(1));
	}

	@Test
	void anonymousCannotListBookings() throws Exception {
		mockMvc.perform(get("/api/bookings"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void participantReadsSingleBooking() throws Exception {
		given(bookingService.getById("mentor@example.com", 1L)).willReturn(booking());

		mockMvc.perform(get("/api/bookings/1").header("Authorization", mentor()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidateName").value("Candy Date"));
	}

	@Test
	void nonParticipantIsForbiddenFromReadingABooking() throws Exception {
		given(bookingService.getById(any(), eq(1L))).willThrow(new AccessDeniedException("nope"));

		mockMvc.perform(get("/api/bookings/1").header("Authorization", candidate()))
				.andExpect(status().isForbidden());
	}

	@Test
	void unknownBookingReturnsNotFound() throws Exception {
		given(bookingService.getById(any(), eq(404L))).willThrow(new BookingNotFoundException(404L));

		mockMvc.perform(get("/api/bookings/404").header("Authorization", candidate()))
				.andExpect(status().isNotFound());
	}

	@Test
	void candidateCancelsBooking() throws Exception {
		mockMvc.perform(delete("/api/bookings/1").header("Authorization", candidate()))
				.andExpect(status().isNoContent());

		verify(bookingService).cancel("candidate@example.com", 1L);
	}

	@Test
	void mentorCannotCancelBooking() throws Exception {
		mockMvc.perform(delete("/api/bookings/1").header("Authorization", mentor()))
				.andExpect(status().isForbidden());

		verify(bookingService, never()).cancel(any(), any());
	}

	@Test
	void cancellingAMissingBookingReturnsNotFound() throws Exception {
		willThrow(new BookingNotFoundException(404L)).given(bookingService).cancel(any(), eq(404L));

		mockMvc.perform(delete("/api/bookings/404").header("Authorization", candidate()))
				.andExpect(status().isNotFound());
	}

	@Test
	void cancellingAnAlreadyCancelledBookingReturnsConflict() throws Exception {
		willThrow(BookingConflictException.notCancellable()).given(bookingService).cancel(any(), eq(1L));

		mockMvc.perform(delete("/api/bookings/1").header("Authorization", candidate()))
				.andExpect(status().isConflict());
	}

}
