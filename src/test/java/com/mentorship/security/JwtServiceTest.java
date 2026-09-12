package com.mentorship.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import com.mentorship.entity.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;

class JwtServiceTest {

	private static final String SECRET = "test-secret-key-that-is-long-enough-for-hs256";

	private final JwtService jwtService = new JwtService(SECRET, 3_600_000L);

	@Test
	void generatesTokenCarryingEmailAndRole() {
		String token = jwtService.generateToken("mentor@example.com", Role.MENTOR);

		Claims claims = jwtService.parseClaims(token);

		assertThat(claims.getSubject()).isEqualTo("mentor@example.com");
		assertThat(claims.get("role", String.class)).isEqualTo("MENTOR");
		assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
	}

	@Test
	void tokenCarriesNoPasswordOrSensitiveClaims() {
		String token = jwtService.generateToken("candidate@example.com", Role.CANDIDATE);

		Claims claims = jwtService.parseClaims(token);

		assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "iat", "exp");
		assertThat(claims.values()).doesNotContain("temporary-test-hash");
	}

	@Test
	void acceptsFreshlyIssuedToken() {
		String token = jwtService.generateToken("candidate@example.com", Role.CANDIDATE);

		assertThat(jwtService.isValid(token)).isTrue();
	}

	@Test
	void rejectsExpiredToken() {
		JwtService alreadyExpired = new JwtService(SECRET, -1_000L);

		String token = alreadyExpired.generateToken("candidate@example.com", Role.CANDIDATE);

		assertThat(jwtService.isValid(token)).isFalse();
		assertThatExceptionOfType(ExpiredJwtException.class).isThrownBy(() -> jwtService.parseClaims(token));
	}

	@Test
	void rejectsTokenSignedWithADifferentSecret() {
		JwtService attacker = new JwtService("a-completely-different-secret-key-value", 3_600_000L);

		String forged = attacker.generateToken("candidate@example.com", Role.MENTOR);

		assertThat(jwtService.isValid(forged)).isFalse();
		assertThatExceptionOfType(SignatureException.class).isThrownBy(() -> jwtService.parseClaims(forged));
	}

	@Test
	void rejectsMalformedToken() {
		assertThat(jwtService.isValid("not-a-jwt")).isFalse();
	}

}
