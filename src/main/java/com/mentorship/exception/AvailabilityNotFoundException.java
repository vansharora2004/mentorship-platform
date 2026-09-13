package com.mentorship.exception;

public class AvailabilityNotFoundException extends RuntimeException {

	public AvailabilityNotFoundException(Long availabilityId) {
		super("No availability slot found with id " + availabilityId);
	}

}
