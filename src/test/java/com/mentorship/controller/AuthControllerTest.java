package com.mentorship.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentorship.config.SecurityConfig;
import com.mentorship.dto.AuthResponse;
import com.mentorship.dto.UserResponse;
import com.mentorship.entity.Role;
import com.mentorship.exception.EmailAlreadyExistsException;
import com.mentorship.exception.GlobalExceptionHandler;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.AuthService;

@WebMvcTest(controllers = { AuthController.class, HealthController.class })
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + AuthControllerTest.SECRET, "app.jwt.expiration-ms=3600000" })
class AuthControllerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private AuthService authService;

	private String bearer(String email, Role role) {
		return "Bearer " + jwtService.generateToken(email, role);
	}

	@Test
	void healthEndpointStaysPublic() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(content().string("UP"));
	}

	@Test
	void registersCandidateAndReturnsToken() throws Exception {
		given(authService.register(any()))
				.willReturn(new AuthResponse("issued-token", "candidate@example.com", "Cand", Role.CANDIDATE));

		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Cand","email":"candidate@example.com","password":"Sup3rSecret!","role":"CANDIDATE"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").value("issued-token"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.role").value("CANDIDATE"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void registersMentor() throws Exception {
		given(authService.register(any()))
				.willReturn(new AuthResponse("issued-token", "mentor@example.com", "Ment", Role.MENTOR));

		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Ment","email":"mentor@example.com","password":"Sup3rSecret!","role":"MENTOR"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("MENTOR"));
	}

	@Test
	void rejectsInvalidRegistrationPayload() throws Exception {
		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"","email":"not-an-email","password":"short","role":"CANDIDATE"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.email").exists())
				.andExpect(jsonPath("$.fields.password").exists());
	}

	@Test
	void rejectsDuplicateEmailWithConflict() throws Exception {
		given(authService.register(any())).willThrow(new EmailAlreadyExistsException("candidate@example.com"));

		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Cand","email":"candidate@example.com","password":"Sup3rSecret!","role":"CANDIDATE"}"""))
				.andExpect(status().isConflict());
	}

	@Test
	void loginReturnsToken() throws Exception {
		given(authService.login(any()))
				.willReturn(new AuthResponse("issued-token", "candidate@example.com", "Cand", Role.CANDIDATE));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"candidate@example.com","password":"Sup3rSecret!"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").value("issued-token"));
	}

	@Test
	void loginWithBadCredentialsReturnsUnauthorized() throws Exception {
		given(authService.login(any())).willThrow(new BadCredentialsException("bad"));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"candidate@example.com","password":"wrong"}"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointRejectsMissingToken() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointAcceptsValidToken() throws Exception {
		given(authService.getByEmail(eq("candidate@example.com")))
				.willReturn(new UserResponse(1L, "Cand", "candidate@example.com", Role.CANDIDATE));

		mockMvc.perform(get("/api/auth/me").header("Authorization", bearer("candidate@example.com", Role.CANDIDATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("candidate@example.com"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void protectedEndpointRejectsTamperedToken() throws Exception {
		String tampered = jwtService.generateToken("candidate@example.com", Role.CANDIDATE) + "x";

		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void protectedEndpointRejectsExpiredToken() throws Exception {
		String expired = new JwtService(SECRET, -1_000L).generateToken("candidate@example.com", Role.CANDIDATE);

		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void candidateIsForbiddenFromMentorOnlyPath() throws Exception {
		mockMvc.perform(get("/api/availability/1").header("Authorization", bearer("c@example.com", Role.CANDIDATE)))
				.andExpect(status().isForbidden());
	}

	@Test
	void mentorIsForbiddenFromCandidateOnlyPath() throws Exception {
		mockMvc.perform(post("/api/bookings").header("Authorization", bearer("m@example.com", Role.MENTOR))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void mentorPassesAuthorizationOnMentorOnlyPath() throws Exception {
		// Phase 5 has no handler yet, so clearing authorization surfaces as 404 rather than 403.
		mockMvc.perform(get("/api/availability/1").header("Authorization", bearer("m@example.com", Role.MENTOR)))
				.andExpect(status().isNotFound());
	}

}
