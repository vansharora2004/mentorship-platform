package com.mentorship.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.mentorship.entity.BookingStatus;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;

@SpringBootTest
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class SessionStatusJobTest extends SchedulerFixture {

	@Autowired
	private SessionStatusJob job;

	@Test
	void activatesASessionWhoseStartTimeHasPassed() {
		Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		job.advanceSessionStates();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.ACTIVE);
	}

	@Test
	void leavesAFutureSessionScheduled() {
		Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		job.advanceSessionStates();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.SCHEDULED);
	}

	@Test
	void completesASessionWhoseEndTimeHasPassed() {
		Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.ACTIVE);

		job.advanceSessionStates();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.COMPLETED);
	}

	@Test
	void completesTheBookingAlongsideTheSession() {
		Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.ACTIVE);
		Long bookingId = session.getBooking().getId();

		job.advanceSessionStates();

		assertThat(reloadBooking(bookingId).getStatus()).isEqualTo(BookingStatus.COMPLETED);
	}

	@Test
	void carriesAMissedSessionStraightToCompleted() {
		// The application was down across both transitions; one run must still settle the state.
		Instant start = Instant.now().minus(4, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		job.advanceSessionStates();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.COMPLETED);
	}

	@Test
	void neverResurrectsACancelledSession() {
		Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.CANCELLED,
				BookingStatus.CANCELLED);

		job.advanceSessionStates();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.CANCELLED);
		assertThat(reloadBooking(session.getBooking().getId()).getStatus()).isEqualTo(BookingStatus.CANCELLED);
	}

	@Test
	void isIdempotentAcrossRepeatedRuns() {
		// Re-running after a restart, or on a second instance, must not change settled state again.
		Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
		Session session = createSession(start, start.plus(1, ChronoUnit.HOURS), SessionStatus.SCHEDULED);

		job.advanceSessionStates();
		assertThat(job.advanceSessionStates()).isZero();

		assertThat(reload(session.getId()).getStatus()).isEqualTo(SessionStatus.COMPLETED);
	}

}
