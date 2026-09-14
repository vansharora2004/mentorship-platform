package com.mentorship.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;

import com.mentorship.entity.Notification;
import com.mentorship.event.NotificationEvent;
import com.mentorship.event.NotificationEventType;
import com.mentorship.repository.NotificationRepository;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

	private static final Long CANDIDATE_ID = 10L;

	private static final Long MENTOR_ID = 20L;

	@Mock
	private NotificationRepository notificationRepository;

	@InjectMocks
	private NotificationConsumer consumer;

	@Test
	void notifiesBothParticipantsOfABooking() {
		consumer.onNotificationEvent(NotificationEvent.bookingCreated(1001L, CANDIDATE_ID, MENTOR_ID));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());

		assertThat(captor.getAllValues())
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(CANDIDATE_ID, MENTOR_ID);
		assertThat(captor.getAllValues())
				.allSatisfy(notification -> {
					assertThat(notification.getEventType()).isEqualTo(NotificationEventType.BOOKING_CREATED);
					assertThat(notification.getBookingId()).isEqualTo(1001L);
					assertThat(notification.getEventId()).isEqualTo("BOOKING_CREATED:1001");
				});
	}

	@Test
	void skipsRecipientsThatAlreadyHaveTheNotification() {
		given(notificationRepository.existsByEventIdAndRecipientId("BOOKING_CREATED:1001", CANDIDATE_ID))
				.willReturn(true);

		consumer.onNotificationEvent(NotificationEvent.bookingCreated(1001L, CANDIDATE_ID, MENTOR_ID));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getRecipientId()).isEqualTo(MENTOR_ID);
	}

	@Test
	void swallowsTheUniqueViolationWhenTwoDeliveriesRace() {
		// Both deliveries pass the exists() pre-check, then the constraint settles it.
		given(notificationRepository.saveAndFlush(any(Notification.class)))
				.willThrow(new DataIntegrityViolationException("duplicate key"));

		consumer.onNotificationEvent(NotificationEvent.bookingCreated(1001L, CANDIDATE_ID, MENTOR_ID));
	}

	@Test
	void deadLettersAnEventWithNoRecipient() {
		NotificationEvent orphan = new NotificationEvent("BOOKING_CREATED:1", NotificationEventType.BOOKING_CREATED,
				1L, null, null, null, Instant.now());

		assertThatExceptionOfType(AmqpRejectAndDontRequeueException.class)
				.isThrownBy(() -> consumer.onNotificationEvent(orphan));

		verify(notificationRepository, never()).saveAndFlush(any());
	}

	@Test
	void deadLettersAMalformedEvent() {
		NotificationEvent malformed = new NotificationEvent(null, null, null, null, CANDIDATE_ID, MENTOR_ID,
				Instant.now());

		assertThatExceptionOfType(AmqpRejectAndDontRequeueException.class)
				.isThrownBy(() -> consumer.onNotificationEvent(malformed));

		verify(notificationRepository, never()).saveAndFlush(any());
	}

	@Test
	void deduplicatesWhenCandidateAndMentorAreTheSamePerson() {
		// Defensive: one recipient means one notification, not two identical rows.
		consumer.onNotificationEvent(NotificationEvent.bookingCreated(1001L, CANDIDATE_ID, CANDIDATE_ID));

		verify(notificationRepository, org.mockito.Mockito.times(1)).saveAndFlush(any(Notification.class));
	}

	@Test
	void describesEachEventTypeDistinctly() {
		List<NotificationEvent> events = List.of(
				NotificationEvent.bookingCreated(1L, CANDIDATE_ID, null),
				NotificationEvent.bookingCancelled(1L, CANDIDATE_ID, null),
				NotificationEvent.sessionReminder(7L, 1L, CANDIDATE_ID, null));

		events.forEach(consumer::onNotificationEvent);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository, org.mockito.Mockito.times(3)).saveAndFlush(captor.capture());
		assertThat(captor.getAllValues()).extracting(Notification::getMessage).doesNotHaveDuplicates();
	}

}
