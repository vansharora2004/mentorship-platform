package com.mentorship.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.mentorship.config.RabbitConfig;
import com.mentorship.event.NotificationEvent;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

	@Mock
	private RabbitTemplate rabbitTemplate;

	@InjectMocks
	private NotificationPublisher publisher;

	@Test
	void publishesToTheNotificationExchangeWithAnEventSpecificRoutingKey() {
		NotificationEvent event = NotificationEvent.bookingCreated(1001L, 10L, 20L);

		publisher.publish(event);

		verify(rabbitTemplate).convertAndSend(RabbitConfig.EXCHANGE, "notification.booking_created", event);
	}

	@Test
	void cancellationAndReminderUseTheirOwnRoutingKeys() {
		publisher.publish(NotificationEvent.bookingCancelled(1001L, 10L, 20L));
		publisher.publish(NotificationEvent.sessionReminder(7L, 1001L, 10L, 20L));

		verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE), eq("notification.booking_cancelled"),
				any(NotificationEvent.class));
		verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE), eq("notification.session_reminder"),
				any(NotificationEvent.class));
	}

	@Test
	void aBrokerOutageNeverEscapesToTheCaller() {
		// Edge Cases "RabbitMQ unavailable": the booking has already committed by the time this
		// runs, so an exception here could only damage an operation that already succeeded.
		willThrow(new AmqpConnectException(new RuntimeException("broker down")))
				.given(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

		assertThatCode(() -> publisher.publish(NotificationEvent.bookingCreated(1001L, 10L, 20L)))
				.doesNotThrowAnyException();
	}

}
