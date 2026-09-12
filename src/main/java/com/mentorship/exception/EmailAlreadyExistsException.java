package com.mentorship.exception;

public class EmailAlreadyExistsException extends RuntimeException {

	public EmailAlreadyExistsException(String email) {
		super("A user is already registered with email " + email);
	}

}
