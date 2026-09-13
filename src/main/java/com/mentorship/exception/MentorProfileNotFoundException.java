package com.mentorship.exception;

public class MentorProfileNotFoundException extends RuntimeException {

	public MentorProfileNotFoundException(String message) {
		super(message);
	}

	public static MentorProfileNotFoundException forMentorId(Long mentorId) {
		return new MentorProfileNotFoundException("No mentor profile found for mentor " + mentorId);
	}

	public static MentorProfileNotFoundException forCurrentUser() {
		return new MentorProfileNotFoundException("You do not have a mentor profile yet");
	}

}
