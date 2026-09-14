package com.mentorship.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;

public interface SessionRepository extends JpaRepository<Session, Long> {

	Optional<Session> findByBookingId(Long bookingId);

	/**
	 * Sessions that start within the reminder window and have not been reminded yet. The
	 * {@code reminderSentAt is null} clause is what keeps the job from re-sending on every tick.
	 * Paged so a long outage cannot produce an unbounded batch on the first tick back.
	 */
	@Query("""
			select s from Session s
			where s.status = :status
			  and s.reminderSentAt is null
			  and s.startTime > :now
			  and s.startTime <= :until
			order by s.startTime asc
			""")
	List<Session> findDueForReminder(@Param("status") SessionStatus status, @Param("now") Instant now,
			@Param("until") Instant until, Pageable pageable);

	/** Scheduled sessions whose start time has passed but which have not yet ended. */
	@Query("""
			select s from Session s
			where s.status = :status
			  and s.startTime <= :now
			  and s.endTime > :now
			order by s.startTime asc
			""")
	List<Session> findDueToStart(@Param("status") SessionStatus status, @Param("now") Instant now, Pageable pageable);

	/** Sessions of any still-open status whose end time has passed. */
	@Query("""
			select s from Session s
			where s.status in :statuses
			  and s.endTime <= :now
			order by s.endTime asc
			""")
	List<Session> findDueToComplete(@Param("statuses") List<SessionStatus> statuses, @Param("now") Instant now,
			Pageable pageable);

}
