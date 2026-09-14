package com.mentorship.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.mentorship.entity.Notification;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.event.NotificationEvent;
import com.mentorship.event.NotificationEventType;
import com.mentorship.messaging.NotificationPublisher;
import com.mentorship.repository.NotificationRepository;
import com.mentorship.repository.SessionRepository;

/**
 * The job is invoked directly rather than waited on, so the assertions are deterministic and need
 * no sleeping; the timer itself is Spring's concern and is disabled during tests.
 *
 * <p>The publisher is spied rather than mocked, so the real path to RabbitMQ still runs. That lets
 * one test follow the documented chain all the way through - scheduler, broker, consumer - while
 * the rest assert only which reminders the job decides to raise.
 *
 * <p>Requires RabbitMQ and Redis: docker compose up -d
 */
@SpringBootTest
@TestPropertySource(properties = {
		"app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256",
		"app.scheduler.reminder-lead-time=15m" })
class SessionReminderJobTest extends SchedulerFixture {

	private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

	@Autowired
	private SessionReminderJob job;

	@Autowired
	private SessionRepository sessionRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@MockitoSpyBean
	private NotificationPublisher notificationPublisher;

	@Test
	void remindsASessionStartingInsideTheLeadTime() {
		Instant start = Instant.now().plus(5, ChronoUnit.MINUTES);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		assertThat(job.sendDueReminders()).isEqualTo(1);

		NotificationEvent published = captureReminder();
		assertThat(published.sessionId()).isEqualTo(session.getId());
		assertThat(published.candidateId()).isEqualTo(candidateId);
		assertThat(published.mentorId()).isEqualTo(mentorId);
		assertThat(sessionRepository.findById(session.getId()).orElseThrow().getReminderSentAt()).isNotNull();
	}

	/** Scheduler to RabbitMQ to consumer, exactly as the architecture document describes it. */
	@Test
	void aReminderReachesBothParticipantsThroughTheBroker() {
		Instant start = Instant.now().plus(5, ChronoUnit.MINUTES);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		job.sendDueReminders();

		String eventId = "SESSION_REMINDER:" + session.getId();
		await().atMost(DELIVERY_TIMEOUT)
				.pollInterval(Duration.ofMillis(100))
				.until(() -> notificationRepository.findByEventId(eventId).size() >= 2);

		assertThat(notificationRepository.findByEventId(eventId))
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(candidateId, mentorId);
		assertThat(notificationRepository.findByEventId(eventId))
				.extracting(Notification::getEventType)
				.containsOnly(NotificationEventType.SESSION_REMINDER);
	}

	@Test
	void doesNotRemindTheSameSessionTwice() {
		Instant start = Instant.now().plus(5, ChronoUnit.MINUTES);
		createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		assertThat(job.sendDueReminders()).isEqualTo(1);
		assertThat(job.sendDueReminders()).isZero();
	}

	@Test
	void ignoresSessionsBeyondTheLeadTime() {
		Instant start = Instant.now().plus(9, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		assertThat(job.sendDueReminders()).isZero();

		assertThat(sessionRepository.findById(session.getId()).orElseThrow().getReminderSentAt()).isNull();
		verify(notificationPublisher, never()).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void ignoresSessionsThatHaveAlreadyStarted() {
		Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
		createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		assertThat(job.sendDueReminders()).isZero();
	}

	@Test
	void ignoresCancelledSessions() {
		Instant start = Instant.now().plus(5, ChronoUnit.MINUTES);
		createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.CANCELLED);

		assertThat(job.sendDueReminders()).isZero();
	}

	private NotificationEvent captureReminder() {
		ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
		verify(notificationPublisher, atLeastOnce()).publish(captor.capture());

		return captor.getAllValues().stream()
				.filter(event -> event.eventType() == NotificationEventType.SESSION_REMINDER)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No SESSION_REMINDER was published"));
	}

}
