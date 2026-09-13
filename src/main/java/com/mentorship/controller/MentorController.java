package com.mentorship.controller;

import java.security.Principal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.dto.MentorProfileResponse;
import com.mentorship.dto.PageResponse;
import com.mentorship.service.MentorService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/mentors")
public class MentorController {

	private final MentorService mentorService;

	public MentorController(MentorService mentorService) {
		this.mentorService = mentorService;
	}

	@GetMapping
	public PageResponse<MentorProfileResponse> search(
			@RequestParam(required = false) String industry,
			@RequestParam(required = false) String expertise,
			@PageableDefault(size = 10) Pageable pageable) {
		return PageResponse.from(mentorService.search(industry, expertise, pageable));
	}

	@GetMapping("/profile")
	public MentorProfileResponse getOwnProfile(Principal principal) {
		return mentorService.getOwnProfile(principal.getName());
	}

	@PostMapping("/profile")
	public ResponseEntity<MentorProfileResponse> create(@Valid @RequestBody MentorProfileRequest request,
			Principal principal) {
		MentorProfileResponse created = mentorService.createProfile(principal.getName(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@PutMapping("/profile")
	public MentorProfileResponse update(@Valid @RequestBody MentorProfileRequest request, Principal principal) {
		return mentorService.updateProfile(principal.getName(), request);
	}

	@DeleteMapping("/profile")
	public ResponseEntity<Void> delete(Principal principal) {
		mentorService.deleteProfile(principal.getName());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{mentorId}")
	public MentorProfileResponse getByMentorId(@PathVariable Long mentorId) {
		return mentorService.getByMentorId(mentorId);
	}

}
