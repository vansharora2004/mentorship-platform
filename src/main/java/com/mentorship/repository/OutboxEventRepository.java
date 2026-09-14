package com.mentorship.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mentorship.entity.OutboxEvent;
import com.mentorship.entity.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	Optional<OutboxEvent> findByEventId(String eventId);

	List<OutboxEvent> findByStatus(OutboxStatus status);

	/**
	 * Unsent events old enough that the after-commit publish has already had its chance. The grace
	 * period keeps the retry from racing a publish that is still in flight; oldest first so a
	 * backlog drains in the order the events happened.
	 */
	@Query("""
			select o from OutboxEvent o
			where o.status = :status
			  and o.createdAt <= :olderThan
			order by o.createdAt asc
			""")
	List<OutboxEvent> findRetryable(@Param("status") OutboxStatus status, @Param("olderThan") Instant olderThan,
			Pageable pageable);

}
