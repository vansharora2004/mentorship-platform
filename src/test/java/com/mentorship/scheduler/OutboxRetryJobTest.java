package com.mentorship.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.mentorship.entity.OutboxEvent;
import com.mentorship.entity.OutboxStatus;
import com.mentorship.event.NotificationEvent;
import com.mentorship.event.NotificationEventType;
import com.mentorship.messaging.NotificationOutbox;
import com.mentorship.messaging.NotificationPublisher;
import com.mentorship.repository.OutboxEventRepository;

/**
 * Retry semantics in isolation: which rows are selected, what happens to each outcome, and that a
 * reachable or unreachable broker is handled without ever discarding an event.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRetryJobTest {

	private static final Duration RETRY_AFTER = Duration.ofMinutes(1);

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private NotificationPublisher publisher;

	@Mock
	private NotificationOutbox outbox;

	private OutboxRetryJob job;

	@BeforeEach
	void setUp() {
		job = new OutboxRetryJob(outboxEventRepository, publisher, outbox, RETRY_AFTER, 100);
	}

	@Test
	void publishesAPendingEventAndMarksItSent() {
		OutboxEvent pending = pending("BOOKING_CREATED:1001");
		givenRetryable(List.of(pending));
		given(publisher.publish(any(NotificationEvent.class))).willReturn(true);

		assertThat(job.publishPendingEvents()).isEqualTo(1);

		verify(publisher).publish(any(NotificationEvent.class));
		verify(outbox).markSent("BOOKING_CREATED:1001");
		verify(outbox, never()).markAttemptFailed(any());
	}

	@Test
	void republishesTheOriginalPayload() {
		OutboxEvent pending = pending("SESSION_REMINDER:7");
		pending.setSessionId(7L);
		pending.setBookingId(1001L);
		givenRetryable(List.of(pending));
		given(publisher.publish(any(NotificationEvent.class))).willReturn(true);

		job.publishPendingEvents();

		var captor = ArgumentCaptor.forClass(NotificationEvent.class);
		verify(publisher).publish(captor.capture());
		assertThat(captor.getValue().eventId()).isEqualTo("SESSION_REMINDER:7");
		assertThat(captor.getValue().sessionId()).isEqualTo(7L);
		assertThat(captor.getValue().candidateId()).isEqualTo(10L);
		assertThat(captor.getValue().mentorId()).isEqualTo(20L);
	}

	@Test
	void aStillUnreachableBrokerLeavesTheEventPendingForALaterRun() {
		givenRetryable(List.of(pending("BOOKING_CREATED:1001")));
		given(publisher.publish(any(NotificationEvent.class))).willReturn(false);

		assertThat(job.publishPendingEvents()).isZero();

		verify(outbox).markAttemptFailed("BOOKING_CREATED:1001");
		verify(outbox, never()).markSent(any());
	}

	@Test
	void stopsTheBatchOnceTheBrokerIsSeenToBeDown() {
		// Hammering a dead broker with the rest of the batch achieves nothing.
		givenRetryable(List.of(pending("BOOKING_CREATED:1"), pending("BOOKING_CREATED:2"),
				pending("BOOKING_CREATED:3")));
		given(publisher.publish(any(NotificationEvent.class))).willReturn(false);

		job.publishPendingEvents();

		verify(publisher, times(1)).publish(any(NotificationEvent.class));
	}

	@Test
	void drainsABacklogInOneRunWhileTheBrokerHolds() {
		givenRetryable(List.of(pending("BOOKING_CREATED:1"), pending("BOOKING_CREATED:2"),
				pending("BOOKING_CREATED:3")));
		given(publisher.publish(any(NotificationEvent.class))).willReturn(true);

		assertThat(job.publishPendingEvents()).isEqualTo(3);

		verify(outbox).markSent("BOOKING_CREATED:1");
		verify(outbox).markSent("BOOKING_CREATED:2");
		verify(outbox).markSent("BOOKING_CREATED:3");
	}

	@Test
	void doesNothingWhenThereIsNoBacklog() {
		givenRetryable(List.of());

		assertThat(job.publishPendingEvents()).isZero();

		verify(publisher, never()).publish(any());
	}

	@Test
	void selectsOnlyPendingRowsPastTheGracePeriod() {
		// Proves the query arguments: SENT rows are never revisited, and rows younger than the
		// grace period are left to the after-commit publish that is still in flight.
		givenRetryable(List.of());

		Instant before = Instant.now().minus(RETRY_AFTER);
		job.publishPendingEvents();
		Instant after = Instant.now().minus(RETRY_AFTER);

		var statusCaptor = ArgumentCaptor.forClass(OutboxStatus.class);
		var cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
		verify(outboxEventRepository).findRetryable(statusCaptor.capture(), cutoffCaptor.capture(), any());

		assertThat(statusCaptor.getValue()).isEqualTo(OutboxStatus.PENDING);
		assertThat(cutoffCaptor.getValue()).isBetween(before, after);
	}

	private void givenRetryable(List<OutboxEvent> records) {
		given(outboxEventRepository.findRetryable(any(OutboxStatus.class), any(Instant.class), any(Pageable.class)))
				.willReturn(records);
	}

	private OutboxEvent pending(String eventId) {
		OutboxEvent record = OutboxEvent.pending(
				new NotificationEvent(eventId, NotificationEventType.BOOKING_CREATED, 1001L,
						null, 10L, 20L, Instant.now().minus(5, ChronoUnit.MINUTES)));
		record.setStatus(OutboxStatus.PENDING);
		return record;
	}

}
