package com.mentorship.exception;

public class SessionNotFoundException extends RuntimeException {

	public SessionNotFoundException(Long sessionId) {
		super("Session " + sessionId + " was not found");
	}

}
