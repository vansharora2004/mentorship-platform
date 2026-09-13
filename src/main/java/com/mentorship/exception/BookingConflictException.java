package com.mentorship.exception;

public class BookingConflictException extends RuntimeException {

	public BookingConflictException(String message) {
		super(message);
	}

	public static BookingConflictException slotUnavailable() {
		return new BookingConflictException("That availability slot is no longer open for booking");
	}

	public static BookingConflictException notCancellable() {
		return new BookingConflictException("Only a confirmed booking can be cancelled");
	}

	public static BookingConflictException alreadyStarted() {
		return new BookingConflictException("A booking cannot be cancelled once its slot has started");
	}

}
