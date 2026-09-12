package com.mentorship.dto;

import com.mentorship.entity.Role;

public record UserResponse(Long id, String name, String email, Role role) {
}
