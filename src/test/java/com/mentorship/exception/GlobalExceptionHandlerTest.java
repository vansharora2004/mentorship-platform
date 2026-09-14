package com.mentorship.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentorship.config.SecurityConfig;
import com.mentorship.controller.BookingController;
import com.mentorship.entity.Role;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.BookingService;

/**
 * Covers the fallback handlers added in Phase 13 - the cases that previously escaped the advice and
 * were rendered by Spring's default error handling instead.
 *
 * <p>The important assertions here are the negative ones: an unexpected failure must not return the
 * exception message, because those routinely carry SQL, table and column names, and file paths.
 * Booking is used as the vehicle simply because it is a representative secured endpoint.
 */
@WebMvcTest(controllers = BookingController.class)
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + GlobalExceptionHandlerTest.SECRET,
		"app.jwt.expiration-ms=3600000" })
class GlobalExceptionHandlerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private static final String CANDIDATE = "candidate@example.com";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private BookingService bookingService;

	@Test
	void anUnexpectedFailureReturnsAGenericMessageInTheStandardShape() throws Exception {
		willThrow(new IllegalStateException("boom")).given(bookingService).create(any(), any());

		mockMvc.perform(post("/api/bookings")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"availabilityId":10}"""))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.message").value("An unexpected error occurred"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	void anUnexpectedFailureLeaksNoInternalDetail() throws Exception {
		// A message shaped like the ones real data-access failures carry.
		willThrow(new IllegalStateException(
				"could not execute statement; SQL [insert into bookings (availability_id) values (?)]; "
						+ "constraint [uq_bookings_active_slot]; at com.mentorship.service.BookingService.create"))
				.given(bookingService).create(any(), any());

		mockMvc.perform(post("/api/bookings")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"availabilityId":10}"""))
				.andExpect(status().isInternalServerError())
				.andExpect(content().string(Matchers.not(Matchers.containsString("SQL"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("bookings"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("uq_bookings_active_slot"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("com.mentorship"))));
	}

	@Test
	void malformedJsonIsRejectedAsBadRequest() throws Exception {
		mockMvc.perform(post("/api/bookings")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"availabilityId\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Malformed request body"));
	}

	@Test
	void aPathVariableOfTheWrongTypeIsRejectedAsBadRequest() throws Exception {
		mockMvc.perform(get("/api/bookings/not-a-number").header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parameter 'bookingId' has an invalid value"));
	}

	@Test
	void theMalformedBodyResponseNamesNoParserInternals() throws Exception {
		mockMvc.perform(post("/api/bookings")
				.header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("not json at all"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(Matchers.not(Matchers.containsString("tools.jackson"))));
	}

	private String bearer() {
		return "Bearer " + jwtService.generateToken(CANDIDATE, Role.CANDIDATE);
	}

}
