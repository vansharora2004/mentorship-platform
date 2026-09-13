package com.mentorship.exception;

public class AvailabilityConflictException extends RuntimeException {

	public AvailabilityConflictException(String message) {
		super(message);
	}

	public static AvailabilityConflictException overlapping() {
		return new AvailabilityConflictException("The slot overlaps an existing availability slot");
	}

	public static AvailabilityConflictException notModifiable() {
		return new AvailabilityConflictException("Only an AVAILABLE slot can be modified");
	}

}
