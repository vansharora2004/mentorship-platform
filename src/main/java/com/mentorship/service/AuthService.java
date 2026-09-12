package com.mentorship.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.AuthResponse;
import com.mentorship.dto.LoginRequest;
import com.mentorship.dto.RegisterRequest;
import com.mentorship.dto.UserResponse;
import com.mentorship.entity.User;
import com.mentorship.exception.EmailAlreadyExistsException;
import com.mentorship.repository.UserRepository;
import com.mentorship.security.JwtService;

@Service
public class AuthService {

	private final UserRepository userRepository;

	private final PasswordEncoder passwordEncoder;

	private final JwtService jwtService;

	private final AuthenticationManager authenticationManager;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
			AuthenticationManager authenticationManager) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.authenticationManager = authenticationManager;
	}

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		if (userRepository.findByEmail(request.email()).isPresent()) {
			throw new EmailAlreadyExistsException(request.email());
		}

		User user = new User();
		user.setName(request.name());
		user.setEmail(request.email());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setRole(request.role());

		User saved = userRepository.save(user);

		return toAuthResponse(saved);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password()));

		User user = userRepository.findByEmail(request.email()).orElseThrow();

		return toAuthResponse(user);
	}

	@Transactional(readOnly = true)
	public UserResponse getByEmail(String email) {
		User user = userRepository.findByEmail(email).orElseThrow();
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
	}

	private AuthResponse toAuthResponse(User user) {
		String token = jwtService.generateToken(user.getEmail(), user.getRole());
		return new AuthResponse(token, user.getEmail(), user.getName(), user.getRole());
	}

}
