package com.mentorship.exception;

public class MentorProfileAlreadyExistsException extends RuntimeException {

	public MentorProfileAlreadyExistsException() {
		super("A mentor profile already exists for this account");
	}

}
