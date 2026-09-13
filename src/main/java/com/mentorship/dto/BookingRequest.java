package com.mentorship.dto;

import jakarta.validation.constraints.NotNull;

// The candidate comes from the authenticated principal, never from the payload.
public record BookingRequest(@NotNull Long availabilityId) {
}
