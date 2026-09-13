package com.mentorship.exception;

public class BookingNotFoundException extends RuntimeException {

	public BookingNotFoundException(Long bookingId) {
		super("No booking found with id " + bookingId);
	}

}
