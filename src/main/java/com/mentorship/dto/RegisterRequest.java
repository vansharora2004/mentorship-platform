package com.mentorship.dto;

import com.mentorship.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

		@NotBlank @Size(min = 2, max = 100) String name,

		@NotBlank @Email @Size(max = 255) String email,

		// BCrypt silently truncates beyond 72 bytes, so cap the input there.
		@NotBlank @Size(min = 8, max = 72) String password,

		@NotNull Role role) {
}
