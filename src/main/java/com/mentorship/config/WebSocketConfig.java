package com.mentorship.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.mentorship.security.StompAuthChannelInterceptor;

/**
 * STOMP over WebSocket, using the destinations named in the specification:
 *
 * <pre>
 * connect   /ws
 * send      /app/chat/{sessionId}
 * subscribe /topic/session/{sessionId}
 * </pre>
 *
 * <p>The architecture document also mentions {@code /topic/sessions/{id}/chat} as an alternative;
 * {@code /topic/session/{sessionId}} is used consistently here, as the context document asks.
 *
 * <p>A simple in-memory broker is enough for a single instance. Running several instances would
 * need a real broker relay, which is recorded as future work.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final StompAuthChannelInterceptor authChannelInterceptor;

	public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor) {
		this.authChannelInterceptor = authChannelInterceptor;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// /queue carries per-user replies (rejections); /topic carries session broadcasts.
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		// Authenticates CONNECT and authorises SUBSCRIBE before a frame reaches any controller.
		registration.interceptors(authChannelInterceptor);
	}

}
