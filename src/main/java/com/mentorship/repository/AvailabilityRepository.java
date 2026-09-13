package com.mentorship.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mentorship.entity.Availability;

import jakarta.persistence.LockModeType;

public interface AvailabilityRepository extends JpaRepository<Availability, Long> {

	// SELECT ... FOR UPDATE. Deliberately a separate method so the plain read paths
	// used for browsing availability are never serialised.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Availability a where a.id = :id")
	Optional<Availability> findByIdForUpdate(@Param("id") Long id);

	List<Availability> findByMentorProfileIdOrderByStartTimeAsc(Long mentorProfileId);

	List<Availability> findByMentorProfileUserIdOrderByStartTimeAsc(Long userId);

	// Half-open intervals: [start, end) overlaps when existing.start < end AND existing.end > start,
	// so slots that merely touch at a boundary are not returned.
	List<Availability> findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(Long mentorProfileId,
			Instant endTime, Instant startTime);

}
