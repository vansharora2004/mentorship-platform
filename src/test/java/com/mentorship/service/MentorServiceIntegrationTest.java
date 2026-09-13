package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.MentorProfileAlreadyExistsException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.UserRepository;

// Exercises the real search Specification against PostgreSQL, which mocks cannot verify.
@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class MentorServiceIntegrationTest {

	@Autowired
	private MentorService mentorService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MentorProfileRepository mentorProfileRepository;

	private User mentor(String email, String name) {
		User user = new User();
		user.setName(name);
		user.setEmail(email);
		user.setPasswordHash("hashed");
		user.setRole(Role.MENTOR);
		return userRepository.saveAndFlush(user);
	}

	@Test
	void createsAndReadsBackAProfile() {
		User user = mentor("svc.create@example.com", "Create Mentor");

		var created = mentorService.createProfile("svc.create@example.com",
				new MentorProfileRequest("FinTech", "Java", 8, "Payments"));

		assertThat(created.mentorId()).isEqualTo(user.getId());
		assertThat(mentorService.getByMentorId(user.getId()).industry()).isEqualTo("FinTech");
		assertThat(mentorProfileRepository.findByUserEmail("svc.create@example.com")).isPresent();
	}

	@Test
	void rejectsDuplicateProfileForTheSameMentor() {
		mentor("svc.duplicate@example.com", "Dup Mentor");
		MentorProfileRequest request = new MentorProfileRequest("FinTech", "Java", 8, null);

		mentorService.createProfile("svc.duplicate@example.com", request);

		assertThatExceptionOfType(MentorProfileAlreadyExistsException.class)
				.isThrownBy(() -> mentorService.createProfile("svc.duplicate@example.com", request));
	}

	@Test
	void updatesAndDeletesOwnProfile() {
		User user = mentor("svc.update@example.com", "Update Mentor");
		mentorService.createProfile("svc.update@example.com", new MentorProfileRequest("FinTech", "Java", 8, null));

		var updated = mentorService.updateProfile("svc.update@example.com",
				new MentorProfileRequest("HealthTech", "Kotlin", 3, "New bio"));

		assertThat(updated.industry()).isEqualTo("HealthTech");
		assertThat(updated.experience()).isEqualTo(3);

		mentorService.deleteProfile("svc.update@example.com");

		assertThat(mentorProfileRepository.findByUserId(user.getId())).isEmpty();
		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> mentorService.getByMentorId(user.getId()));
	}

	@Test
	void filtersByIndustryCaseInsensitively() {
		mentor("svc.fin@example.com", "Fin Mentor");
		mentor("svc.health@example.com", "Health Mentor");
		mentorService.createProfile("svc.fin@example.com", new MentorProfileRequest("FinTech", "Java", 8, null));
		mentorService.createProfile("svc.health@example.com", new MentorProfileRequest("HealthTech", "Java", 4, null));

		var page = mentorService.search("fintech", null, PageRequest.of(0, 10));

		assertThat(page.getContent()).extracting("industry").containsOnly("FinTech");
	}

	@Test
	void filtersByExpertiseSubstring() {
		mentor("svc.java@example.com", "Java Mentor");
		mentor("svc.python@example.com", "Python Mentor");
		mentorService.createProfile("svc.java@example.com",
				new MentorProfileRequest("FinTech", "Java, Spring Boot", 8, null));
		mentorService.createProfile("svc.python@example.com",
				new MentorProfileRequest("FinTech", "Python, Django", 5, null));

		var page = mentorService.search(null, "spring", PageRequest.of(0, 10));

		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().getFirst().expertise()).contains("Spring Boot");
	}

	@Test
	void searchWithoutFiltersReturnsAPage() {
		mentor("svc.any@example.com", "Any Mentor");
		mentorService.createProfile("svc.any@example.com", new MentorProfileRequest("FinTech", "Java", 8, null));

		var page = mentorService.search(null, null, PageRequest.of(0, 10));

		assertThat(page.getTotalElements()).isPositive();
	}

}
