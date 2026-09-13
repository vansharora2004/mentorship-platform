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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentorship.config.SecurityConfig;
import com.mentorship.dto.MentorProfileResponse;
import com.mentorship.entity.Role;
import com.mentorship.exception.MentorProfileAlreadyExistsException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.exception.GlobalExceptionHandler;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;
import com.mentorship.service.MentorService;

@WebMvcTest(controllers = MentorController.class)
@Import({ SecurityConfig.class, JwtService.class, SecurityErrorHandler.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = { "app.jwt.secret=" + MentorControllerTest.SECRET,
		"app.jwt.expiration-ms=3600000" })
class MentorControllerTest {

	static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private static final String VALID_BODY = """
			{"industry":"FinTech","expertise":"Java","experience":8,"bio":"Payments engineer"}""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private MentorService mentorService;

	private String mentor() {
		return "Bearer " + jwtService.generateToken("mentor@example.com", Role.MENTOR);
	}

	private String candidate() {
		return "Bearer " + jwtService.generateToken("candidate@example.com", Role.CANDIDATE);
	}

	private MentorProfileResponse profile() {
		return new MentorProfileResponse(7L, "Mentor Mentorson", "FinTech", "Java", 8, "Payments engineer",
				Instant.now(), Instant.now());
	}

	@Test
	void mentorCreatesProfile() throws Exception {
		given(mentorService.createProfile(eq("mentor@example.com"), any())).willReturn(profile());

		mockMvc.perform(post("/api/mentors/profile").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mentorId").value(7))
				.andExpect(jsonPath("$.industry").value("FinTech"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void candidateCannotCreateProfile() throws Exception {
		mockMvc.perform(post("/api/mentors/profile").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());

		verify(mentorService, never()).createProfile(any(), any());
	}

	@Test
	void anonymousCannotCreateProfile() throws Exception {
		mockMvc.perform(post("/api/mentors/profile").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateProfileIsRejected() throws Exception {
		given(mentorService.createProfile(any(), any())).willThrow(new MentorProfileAlreadyExistsException());

		mockMvc.perform(post("/api/mentors/profile").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict());
	}

	@Test
	void invalidProfilePayloadIsRejected() throws Exception {
		mockMvc.perform(post("/api/mentors/profile").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"industry":"","expertise":"","experience":-4,"bio":"x"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.industry").exists())
				.andExpect(jsonPath("$.fields.expertise").exists())
				.andExpect(jsonPath("$.fields.experience").exists());

		verify(mentorService, never()).createProfile(any(), any());
	}

	@Test
	void mentorReadsOwnProfile() throws Exception {
		given(mentorService.getOwnProfile("mentor@example.com")).willReturn(profile());

		mockMvc.perform(get("/api/mentors/profile").header("Authorization", mentor()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorId").value(7));
	}

	@Test
	void candidateCannotReadTheOwnProfileEndpoint() throws Exception {
		mockMvc.perform(get("/api/mentors/profile").header("Authorization", candidate()))
				.andExpect(status().isForbidden());
	}

	@Test
	void mentorUpdatesProfile() throws Exception {
		given(mentorService.updateProfile(eq("mentor@example.com"), any())).willReturn(profile());

		mockMvc.perform(put("/api/mentors/profile").header("Authorization", mentor())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expertise").value("Java"));
	}

	@Test
	void candidateCannotUpdateProfile() throws Exception {
		mockMvc.perform(put("/api/mentors/profile").header("Authorization", candidate())
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden());

		verify(mentorService, never()).updateProfile(any(), any());
	}

	@Test
	void mentorDeletesProfile() throws Exception {
		mockMvc.perform(delete("/api/mentors/profile").header("Authorization", mentor()))
				.andExpect(status().isNoContent());

		verify(mentorService).deleteProfile("mentor@example.com");
	}

	@Test
	void candidateCannotDeleteProfile() throws Exception {
		mockMvc.perform(delete("/api/mentors/profile").header("Authorization", candidate()))
				.andExpect(status().isForbidden());

		verify(mentorService, never()).deleteProfile(any());
	}

	@Test
	void deletingAMissingProfileIsReportedAsNotFound() throws Exception {
		willThrow(MentorProfileNotFoundException.forCurrentUser()).given(mentorService).deleteProfile(any());

		mockMvc.perform(delete("/api/mentors/profile").header("Authorization", mentor()))
				.andExpect(status().isNotFound());
	}

	@Test
	void candidateCanViewASingleMentor() throws Exception {
		given(mentorService.getByMentorId(7L)).willReturn(profile());

		mockMvc.perform(get("/api/mentors/7").header("Authorization", candidate()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Mentor Mentorson"));
	}

	@Test
	void unknownMentorReturnsNotFound() throws Exception {
		given(mentorService.getByMentorId(404L)).willThrow(MentorProfileNotFoundException.forMentorId(404L));

		mockMvc.perform(get("/api/mentors/404").header("Authorization", candidate()))
				.andExpect(status().isNotFound());
	}

	@Test
	void candidateCanSearchMentorsWithFilters() throws Exception {
		Pageable pageable = PageRequest.of(0, 10);
		given(mentorService.search(eq("FinTech"), eq("Java"), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(profile()), pageable, 1));

		mockMvc.perform(get("/api/mentors").param("industry", "FinTech").param("expertise", "Java")
				.header("Authorization", candidate()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].mentorId").value(7))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.page").value(0));
	}

	@Test
	void searchWithoutFiltersPassesNulls() throws Exception {
		given(mentorService.search(eq(null), eq(null), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(get("/api/mentors").header("Authorization", candidate()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void anonymousCannotSearchMentors() throws Exception {
		mockMvc.perform(get("/api/mentors"))
				.andExpect(status().isUnauthorized());
	}

}
