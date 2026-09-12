package com.mentorship.security;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Deliberately not a bean: as a @Component, Boot would also register it as a top-level
// servlet filter, where it runs before SecurityContextHolderFilter wipes the context and
// OncePerRequestFilter then suppresses the in-chain run, leaving every request anonymous.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {

		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith(BEARER_PREFIX)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			authenticate(request, header.substring(BEARER_PREFIX.length()));
		}

		filterChain.doFilter(request, response);
	}

	// A rejected token leaves the context empty; the entry point then returns 401.
	private void authenticate(HttpServletRequest request, String token) {
		try {
			Claims claims = jwtService.parseClaims(token);
			String role = claims.get(JwtService.ROLE_CLAIM, String.class);
			if (role == null) {
				return;
			}

			var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
			var authentication = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);
			authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		catch (JwtException | IllegalArgumentException ex) {
			SecurityContextHolder.clearContext();
		}
	}

}
