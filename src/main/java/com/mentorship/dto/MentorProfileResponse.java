package com.mentorship.dto;

import java.time.Instant;

import com.mentorship.entity.MentorProfile;

public record MentorProfileResponse(Long mentorId, String name, String industry, String expertise, Integer experience,
		String bio, Instant createdAt, Instant updatedAt) {

	public static MentorProfileResponse from(MentorProfile profile) {
		return new MentorProfileResponse(
				profile.getUser().getId(),
				profile.getUser().getName(),
				profile.getIndustry(),
				profile.getExpertise(),
				profile.getExperience(),
				profile.getBio(),
				profile.getCreatedAt(),
				profile.getUpdatedAt());
	}

}
