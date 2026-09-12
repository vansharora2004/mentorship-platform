package com.mentorship.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHashingTest {

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	@Test
	void neverStoresThePlaintextPassword() {
		String encoded = passwordEncoder.encode("Sup3rSecret!");

		assertThat(encoded).isNotEqualTo("Sup3rSecret!").doesNotContain("Sup3rSecret!").startsWith("$2");
	}

	@Test
	void matchesTheOriginalPassword() {
		String encoded = passwordEncoder.encode("Sup3rSecret!");

		assertThat(passwordEncoder.matches("Sup3rSecret!", encoded)).isTrue();
		assertThat(passwordEncoder.matches("wrong-password", encoded)).isFalse();
	}

	@Test
	void producesADifferentHashPerCallBecauseOfSalting() {
		assertThat(passwordEncoder.encode("Sup3rSecret!")).isNotEqualTo(passwordEncoder.encode("Sup3rSecret!"));
	}

}
