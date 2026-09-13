package com.mentorship.dto;

import java.time.Instant;

import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;

public record AvailabilityResponse(Long id, Long mentorId, Instant startTime, Instant endTime,
		AvailabilityStatus status, Instant createdAt) {

	public static AvailabilityResponse from(Availability availability) {
		return new AvailabilityResponse(
				availability.getId(),
				availability.getMentorProfile().getUser().getId(),
				availability.getStartTime(),
				availability.getEndTime(),
				availability.getStatus(),
				availability.getCreatedAt());
	}

}
