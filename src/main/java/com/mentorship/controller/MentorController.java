package com.mentorship.controller;

import java.security.Principal;
import java.util.List;

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

import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.dto.MentorProfileResponse;
import com.mentorship.dto.PageResponse;
import com.mentorship.service.AvailabilityService;
import com.mentorship.service.MentorService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/mentors")
public class MentorController {

	private final MentorService mentorService;

	private final AvailabilityService availabilityService;

	public MentorController(MentorService mentorService, AvailabilityService availabilityService) {
		this.mentorService = mentorService;
		this.availabilityService = availabilityService;
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

	@GetMapping("/{mentorId}/availability")
	public List<AvailabilityResponse> getAvailability(@PathVariable Long mentorId) {
		return availabilityService.listForMentor(mentorId);
	}

}
