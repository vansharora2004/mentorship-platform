package com.mentorship.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology for asynchronous notifications.
 *
 * <pre>
 * publisher -&gt; mentorship.events (topic) -&gt; notification.queue -&gt; consumer
 *                                                  |
 *                                            (retries exhausted)
 *                                                  v
 *                              mentorship.events.dlx -&gt; notification.dlq
 * </pre>
 *
 * A message the consumer cannot process is parked on the dead-letter queue rather than being
 * redelivered forever, which Edge Cases "Consumer failure" calls for.
 */
@Configuration
public class RabbitConfig {

	public static final String EXCHANGE = "mentorship.events";

	public static final String DEAD_LETTER_EXCHANGE = "mentorship.events.dlx";

	public static final String NOTIFICATION_QUEUE = "notification.queue";

	public static final String DEAD_LETTER_QUEUE = "notification.dlq";

	/** Every notification routing key is "notification.<event type>". */
	public static final String NOTIFICATION_ROUTING_PATTERN = "notification.*";

	@Bean
	TopicExchange notificationExchange() {
		return new TopicExchange(EXCHANGE, true, false);
	}

	@Bean
	DirectExchange notificationDeadLetterExchange() {
		return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue notificationQueue() {
		return QueueBuilder.durable(NOTIFICATION_QUEUE)
				.deadLetterExchange(DEAD_LETTER_EXCHANGE)
				.deadLetterRoutingKey(DEAD_LETTER_QUEUE)
				.build();
	}

	@Bean
	Queue notificationDeadLetterQueue() {
		return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
	}

	@Bean
	Binding notificationBinding(Queue notificationQueue, TopicExchange notificationExchange) {
		return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(NOTIFICATION_ROUTING_PATTERN);
	}

	@Bean
	Binding notificationDeadLetterBinding(Queue notificationDeadLetterQueue,
			DirectExchange notificationDeadLetterExchange) {
		return BindingBuilder.bind(notificationDeadLetterQueue).to(notificationDeadLetterExchange)
				.with(DEAD_LETTER_QUEUE);
	}

	/** Jackson 3, matching the rest of the application. Records serialize without extra modules. */
	@Bean
	MessageConverter rabbitMessageConverter() {
		return new JacksonJsonMessageConverter();
	}

	@Bean
	RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter rabbitMessageConverter) {
		RabbitTemplate template = new RabbitTemplate(connectionFactory);
		template.setMessageConverter(rabbitMessageConverter);
		template.setExchange(EXCHANGE);
		return template;
	}

}
