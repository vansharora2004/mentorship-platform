package com.mentorship.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.entity.Role;
import com.mentorship.entity.User;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void savesUserAndRetrievesItByEmail() {
		User saved = userRepository.save(newUser("repo.save@example.com"));
		entityManager.flush();
		entityManager.clear();

		assertThat(saved.getId()).isNotNull();

		User found = userRepository.findByEmail("repo.save@example.com").orElseThrow();
		assertThat(found.getId()).isEqualTo(saved.getId());
		assertThat(found.getName()).isEqualTo("Repository Test User");
		assertThat(found.getPasswordHash()).isEqualTo("test-hash");
		assertThat(found.getRole()).isEqualTo(Role.CANDIDATE);
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	void findByEmailReturnsEmptyWhenNoUserMatches() {
		assertThat(userRepository.findByEmail("repo.absent@example.com")).isEmpty();
	}

	@Test
	void rejectsSecondUserWithDuplicateEmail() {
		userRepository.saveAndFlush(newUser("repo.duplicate@example.com"));

		User duplicate = newUser("repo.duplicate@example.com");

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> userRepository.saveAndFlush(duplicate));
	}

	private User newUser(String email) {
		User user = new User();
		user.setName("Repository Test User");
		user.setEmail(email);
		user.setPasswordHash("test-hash");
		user.setRole(Role.CANDIDATE);
		return user;
	}

}
