package com.mentorship.dto;

import com.mentorship.entity.Role;

public record AuthResponse(String token, String tokenType, String email, String name, Role role) {

	public AuthResponse(String token, String email, String name, Role role) {
		this(token, "Bearer", email, name, role);
	}

}
