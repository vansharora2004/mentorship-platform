package com.mentorship.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.mentorship.security.JwtAuthenticationFilter;
import com.mentorship.security.JwtService;
import com.mentorship.security.SecurityErrorHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtService jwtService;

	private final SecurityErrorHandler securityErrorHandler;

	public SecurityConfig(JwtService jwtService, SecurityErrorHandler securityErrorHandler) {
		this.jwtService = jwtService;
		this.securityErrorHandler = securityErrorHandler;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				// Safe to disable: no cookies or server-side session are used.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/register", "/api/auth/login", "/api/health").permitAll()
						// Must precede the GET rule below, which would otherwise match /api/mentors/profile.
						.requestMatchers("/api/mentors/profile").hasRole("MENTOR")
						.requestMatchers(HttpMethod.GET, "/api/mentors", "/api/mentors/*",
								"/api/mentors/*/availability").authenticated()
						.requestMatchers("/api/availability/**").hasRole("MENTOR")
						// Mentors read bookings made against their slots; only candidates create or cancel.
						.requestMatchers(HttpMethod.GET, "/api/bookings", "/api/bookings/*").authenticated()
						.requestMatchers("/api/bookings/**").hasRole("CANDIDATE")
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(securityErrorHandler)
						.accessDeniedHandler(securityErrorHandler))
				.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
				.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

}
