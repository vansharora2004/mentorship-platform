package com.mentorship.dto;

import java.time.Instant;

import com.mentorship.entity.Booking;
import com.mentorship.entity.BookingStatus;

public record BookingResponse(Long id, Long mentorId, String mentorName, Long candidateId, String candidateName,
		Long availabilityId, Instant startTime, Instant endTime, BookingStatus status, Instant createdAt,
		Instant updatedAt) {

	public static BookingResponse from(Booking booking) {
		return new BookingResponse(
				booking.getId(),
				booking.getMentor().getId(),
				booking.getMentor().getName(),
				booking.getCandidate().getId(),
				booking.getCandidate().getName(),
				booking.getAvailability().getId(),
				booking.getStartTime(),
				booking.getEndTime(),
				booking.getStatus(),
				booking.getCreatedAt(),
				booking.getUpdatedAt());
	}

}
