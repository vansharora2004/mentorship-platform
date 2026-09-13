package com.mentorship.dto;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

// Carries no mentor id on purpose: ownership comes from the authenticated principal.
public record AvailabilityRequest(@NotNull Instant startTime, @NotNull Instant endTime) {

	@AssertTrue(message = "startTime must be before endTime")
	public boolean isValidRange() {
		return startTime == null || endTime == null || startTime.isBefore(endTime);
	}

}
