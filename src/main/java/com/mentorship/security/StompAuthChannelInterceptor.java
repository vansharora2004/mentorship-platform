package com.mentorship.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.mentorship.service.ChatService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * Applies authentication and authorisation to the STOMP frames the REST filter chain never sees.
 *
 * <p>The HTTP handshake carries no Authorization header from a browser WebSocket, so the token
 * travels on the STOMP CONNECT frame instead:
 *
 * <ul>
 * <li><b>CONNECT</b> - the JWT is validated and the resulting principal is attached to the
 * session. An absent or invalid token fails the connection outright.</li>
 * <li><b>SUBSCRIBE</b> - the session id in the destination is checked against the subscriber's
 * membership of that session. This is what stops someone reading another pair's conversation by
 * editing the session id, which a destination-pattern check alone would allow.</li>
 * </ul>
 *
 * <p>SEND frames are authorised in {@link ChatService} instead, because the same ownership rules
 * apply there and duplicating them would risk the two drifting apart.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

	private static final String AUTHORIZATION = "Authorization";

	private static final String BEARER = "Bearer ";

	/** Mirrors the broker destination prefix configured in WebSocketConfig. */
	private static final Pattern SESSION_TOPIC = Pattern.compile("^/topic/session/(\\d+)$");

	private final JwtService jwtService;

	private final ChatService chatService;

	public StompAuthChannelInterceptor(JwtService jwtService, ChatService chatService) {
		this.jwtService = jwtService;
		this.chatService = chatService;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}

		if (StompCommand.CONNECT.equals(accessor.getCommand())) {
			authenticate(accessor);
		}
		else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
			authoriseSubscription(accessor);
		}

		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String token = bearerToken(accessor);
		if (token == null) {
			throw new BadCredentialsException("Missing bearer token on STOMP CONNECT");
		}

		try {
			Claims claims = jwtService.parseClaims(token);
			String email = claims.getSubject();
			String role = claims.get(JwtService.ROLE_CLAIM, String.class);

			var authentication = new UsernamePasswordAuthenticationToken(email, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + role)));
			accessor.setUser(authentication);
			log.debug("STOMP CONNECT authenticated for {}", email);
		}
		catch (JwtException | IllegalArgumentException ex) {
			throw new BadCredentialsException("Invalid bearer token on STOMP CONNECT", ex);
		}
	}

	private void authoriseSubscription(StompHeaderAccessor accessor) {
		String destination = accessor.getDestination();
		if (destination == null) {
			return;
		}

		Matcher matcher = SESSION_TOPIC.matcher(destination);
		if (!matcher.matches()) {
			// Not a session topic; nothing session-specific to authorise.
			return;
		}

		if (accessor.getUser() == null) {
			throw new AccessDeniedException("Cannot subscribe without an authenticated session");
		}

		Long sessionId = Long.valueOf(matcher.group(1));
		String email = accessor.getUser().getName();

		if (!chatService.isParticipant(sessionId, email)) {
			log.warn("Rejected subscription by {} to session {}", email, sessionId);
			throw new AccessDeniedException("You are not a participant of session " + sessionId);
		}
	}

	private String bearerToken(StompHeaderAccessor accessor) {
		List<String> values = accessor.getNativeHeader(AUTHORIZATION);
		if (values == null || values.isEmpty()) {
			return null;
		}

		String header = values.getFirst();
		return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
	}

}
