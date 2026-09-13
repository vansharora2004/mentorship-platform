package com.mentorship.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Carries no user or mentor id on purpose: ownership comes from the authenticated principal.
public record MentorProfileRequest(

		@NotBlank @Size(max = 100) String industry,

		@NotBlank @Size(max = 255) String expertise,

		@NotNull @Min(0) @Max(60) Integer experience,

		@Size(max = 2000) String bio) {
}
