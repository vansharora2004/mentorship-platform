package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mentorship.dto.LoginRequest;
import com.mentorship.dto.RegisterRequest;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.EmailAlreadyExistsException;
import com.mentorship.repository.UserRepository;
import com.mentorship.security.JwtService;

/**
 * Unit tests for the registration and login rules, with every collaborator mocked.
 *
 * <p>Complements the existing tests rather than repeating them: {@code AuthControllerTest} covers
 * the HTTP layer, {@code JwtServiceTest} the token itself, and {@code PasswordHashingTest} the
 * BCrypt encoder. What is asserted here is the service's own behaviour - that a password is never
 * stored in the clear, that a duplicate email is refused before any write, and that a token is
 * issued only after authentication has actually succeeded.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final String EMAIL = "candidate@example.com";

	private static final String PASSWORD = "plain-text-password";

	private static final String HASH = "$2a$10$hashedvalue";

	private static final String TOKEN = "signed.jwt.token";

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private JwtService jwtService;

	@Mock
	private AuthenticationManager authenticationManager;

	@InjectMocks
	private AuthService authService;

	// ---------- registration ----------

	@Test
	void registerStoresTheUserAndReturnsAToken() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());
		given(passwordEncoder.encode(PASSWORD)).willReturn(HASH);
		given(userRepository.save(any(User.class))).willAnswer(call -> call.getArgument(0));
		given(jwtService.generateToken(EMAIL, Role.CANDIDATE)).willReturn(TOKEN);

		var response = authService.register(new RegisterRequest("Ada", EMAIL, PASSWORD, Role.CANDIDATE));

		assertThat(response.token()).isEqualTo(TOKEN);
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.name()).isEqualTo("Ada");
		assertThat(response.role()).isEqualTo(Role.CANDIDATE);
	}

	@Test
	void registerNeverStoresThePasswordInPlainText() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());
		given(passwordEncoder.encode(PASSWORD)).willReturn(HASH);
		given(userRepository.save(any(User.class))).willAnswer(call -> call.getArgument(0));
		given(jwtService.generateToken(EMAIL, Role.CANDIDATE)).willReturn(TOKEN);

		authService.register(new RegisterRequest("Ada", EMAIL, PASSWORD, Role.CANDIDATE));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		assertThat(captor.getValue().getPasswordHash()).isEqualTo(HASH);
		assertThat(captor.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
	}

	@Test
	void registerHonoursTheRequestedRole() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());
		given(passwordEncoder.encode(PASSWORD)).willReturn(HASH);
		given(userRepository.save(any(User.class))).willAnswer(call -> call.getArgument(0));
		given(jwtService.generateToken(EMAIL, Role.MENTOR)).willReturn(TOKEN);

		authService.register(new RegisterRequest("Grace", EMAIL, PASSWORD, Role.MENTOR));

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).save(captor.capture());
		assertThat(captor.getValue().getRole()).isEqualTo(Role.MENTOR);
	}

	@Test
	void registerRejectsAnEmailThatIsAlreadyTaken() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(existingUser()));

		assertThatExceptionOfType(EmailAlreadyExistsException.class)
				.isThrownBy(() -> authService.register(new RegisterRequest("Ada", EMAIL, PASSWORD, Role.CANDIDATE)));

		// Refused before anything is written or hashed.
		verify(userRepository, never()).save(any());
		verify(passwordEncoder, never()).encode(any());
	}

	// ---------- login ----------

	@Test
	void loginReturnsATokenForValidCredentials() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(existingUser()));
		given(jwtService.generateToken(EMAIL, Role.CANDIDATE)).willReturn(TOKEN);

		var response = authService.login(new LoginRequest(EMAIL, PASSWORD));

		assertThat(response.token()).isEqualTo(TOKEN);
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.role()).isEqualTo(Role.CANDIDATE);
	}

	@Test
	void loginDelegatesTheCredentialCheckToSpringSecurity() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(existingUser()));
		given(jwtService.generateToken(EMAIL, Role.CANDIDATE)).willReturn(TOKEN);

		authService.login(new LoginRequest(EMAIL, PASSWORD));

		// The service must not compare passwords itself; the AuthenticationManager owns that.
		verify(authenticationManager).authenticate(any());
	}

	@Test
	void loginIssuesNoTokenWhenAuthenticationFails() {
		willThrow(new BadCredentialsException("Bad credentials"))
				.given(authenticationManager).authenticate(any());

		assertThatExceptionOfType(BadCredentialsException.class)
				.isThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-password")));

		verify(jwtService, never()).generateToken(any(), any());
	}

	// ---------- current user ----------

	@Test
	void getByEmailReturnsTheUserWithoutAnyCredentialMaterial() {
		given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(existingUser()));

		var response = authService.getByEmail(EMAIL);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.name()).isEqualTo("Ada");
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.role()).isEqualTo(Role.CANDIDATE);
		// UserResponse is a record with no password component at all, so a hash cannot leak here.
		assertThat(response.toString()).doesNotContain(HASH);
	}

	private User existingUser() {
		User user = new User();
		user.setId(7L);
		user.setName("Ada");
		user.setEmail(EMAIL);
		user.setPasswordHash(HASH);
		user.setRole(Role.CANDIDATE);
		return user;
	}

}
