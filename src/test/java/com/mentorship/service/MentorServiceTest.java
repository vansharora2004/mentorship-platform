package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.dto.MentorProfileResponse;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.MentorProfileAlreadyExistsException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class MentorServiceTest {

	private static final String MENTOR_EMAIL = "mentor@example.com";

	@Mock
	private MentorProfileRepository mentorProfileRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private CacheEvictor cacheEvictor;

	@InjectMocks
	private MentorService mentorService;

	private final MentorProfileRequest request = new MentorProfileRequest("FinTech", "Java", 8, "Payments engineer");

	@Test
	void createsProfileForTheAuthenticatedMentor() {
		User mentor = mentorUser();
		given(userRepository.findByEmail(MENTOR_EMAIL)).willReturn(Optional.of(mentor));
		given(mentorProfileRepository.existsByUserId(7L)).willReturn(false);
		given(mentorProfileRepository.save(any(MentorProfile.class))).willAnswer(call -> call.getArgument(0));

		MentorProfileResponse response = mentorService.createProfile(MENTOR_EMAIL, request);

		ArgumentCaptor<MentorProfile> saved = ArgumentCaptor.forClass(MentorProfile.class);
		verify(mentorProfileRepository).save(saved.capture());

		assertThat(saved.getValue().getUser()).isSameAs(mentor);
		assertThat(saved.getValue().getIndustry()).isEqualTo("FinTech");
		assertThat(saved.getValue().getExperience()).isEqualTo(8);
		assertThat(response.mentorId()).isEqualTo(7L);
		assertThat(response.name()).isEqualTo("Mentor Mentorson");
	}

	@Test
	void rejectsASecondProfileForTheSameMentor() {
		given(userRepository.findByEmail(MENTOR_EMAIL)).willReturn(Optional.of(mentorUser()));
		given(mentorProfileRepository.existsByUserId(7L)).willReturn(true);

		assertThatExceptionOfType(MentorProfileAlreadyExistsException.class)
				.isThrownBy(() -> mentorService.createProfile(MENTOR_EMAIL, request));

		verify(mentorProfileRepository, never()).save(any());
	}

	@Test
	void updateResolvesTheProfileFromTheAuthenticatedEmailOnly() {
		MentorProfile existing = existingProfile();
		given(mentorProfileRepository.findByUserEmail(MENTOR_EMAIL)).willReturn(Optional.of(existing));
		given(mentorProfileRepository.save(any(MentorProfile.class))).willAnswer(call -> call.getArgument(0));

		MentorProfileResponse response = mentorService.updateProfile(MENTOR_EMAIL,
				new MentorProfileRequest("HealthTech", "Kotlin", 3, "Updated bio"));

		verify(mentorProfileRepository).findByUserEmail(MENTOR_EMAIL);
		assertThat(existing.getIndustry()).isEqualTo("HealthTech");
		assertThat(existing.getExpertise()).isEqualTo("Kotlin");
		assertThat(existing.getExperience()).isEqualTo(3);
		assertThat(response.industry()).isEqualTo("HealthTech");
	}

	@Test
	void updateFailsWhenTheCallerHasNoProfile() {
		given(mentorProfileRepository.findByUserEmail(MENTOR_EMAIL)).willReturn(Optional.empty());

		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> mentorService.updateProfile(MENTOR_EMAIL, request));
	}

	@Test
	void deleteRemovesOnlyTheCallersOwnProfile() {
		MentorProfile existing = existingProfile();
		given(mentorProfileRepository.findByUserEmail(MENTOR_EMAIL)).willReturn(Optional.of(existing));

		mentorService.deleteProfile(MENTOR_EMAIL);

		verify(mentorProfileRepository).delete(existing);
		verify(cacheEvictor).evictMentor(7L);
	}

	@Test
	void deleteFailsWhenTheCallerHasNoProfile() {
		given(mentorProfileRepository.findByUserEmail(MENTOR_EMAIL)).willReturn(Optional.empty());

		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> mentorService.deleteProfile(MENTOR_EMAIL));

		verify(mentorProfileRepository, never()).delete(any(MentorProfile.class));
	}

	@Test
	void readsProfileByMentorId() {
		given(mentorProfileRepository.findByUserId(7L)).willReturn(Optional.of(existingProfile()));

		assertThat(mentorService.getByMentorId(7L).mentorId()).isEqualTo(7L);
	}

	@Test
	void missingMentorIdIsReported() {
		given(mentorProfileRepository.findByUserId(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> mentorService.getByMentorId(404L));
	}

	@Test
	void searchMapsResultsToResponses() {
		Pageable pageable = PageRequest.of(0, 10);
		given(mentorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(existingProfile()), pageable, 1));

		var page = mentorService.search("FinTech", "Java", pageable);

		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().getFirst().industry()).isEqualTo("FinTech");
	}

	private User mentorUser() {
		User user = new User();
		user.setId(7L);
		user.setName("Mentor Mentorson");
		user.setEmail(MENTOR_EMAIL);
		user.setPasswordHash("hashed");
		user.setRole(Role.MENTOR);
		return user;
	}

	private MentorProfile existingProfile() {
		MentorProfile profile = new MentorProfile();
		profile.setId(1L);
		profile.setUser(mentorUser());
		profile.setIndustry("FinTech");
		profile.setExpertise("Java");
		profile.setExperience(8);
		profile.setBio("Payments engineer");
		profile.setCreatedAt(Instant.now());
		profile.setUpdatedAt(Instant.now());
		return profile;
	}

}
