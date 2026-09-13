package com.mentorship.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mentorship.entity.Availability;

public interface AvailabilityRepository extends JpaRepository<Availability, Long> {

	List<Availability> findByMentorProfileIdOrderByStartTimeAsc(Long mentorProfileId);

	List<Availability> findByMentorProfileUserIdOrderByStartTimeAsc(Long userId);

	// Half-open intervals: [start, end) overlaps when existing.start < end AND existing.end > start,
	// so slots that merely touch at a boundary are not returned.
	List<Availability> findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(Long mentorProfileId,
			Instant endTime, Instant startTime);

}
