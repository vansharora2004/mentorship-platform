package com.mentorship.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.service.AvailabilityService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

	private final AvailabilityService availabilityService;

	public AvailabilityController(AvailabilityService availabilityService) {
		this.availabilityService = availabilityService;
	}

	@GetMapping
	public List<AvailabilityResponse> listOwn(Principal principal) {
		return availabilityService.listOwn(principal.getName());
	}

	@PostMapping
	public ResponseEntity<AvailabilityResponse> create(@Valid @RequestBody AvailabilityRequest request,
			Principal principal) {
		AvailabilityResponse created = availabilityService.create(principal.getName(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@PutMapping("/{availabilityId}")
	public AvailabilityResponse update(@PathVariable Long availabilityId,
			@Valid @RequestBody AvailabilityRequest request, Principal principal) {
		return availabilityService.update(principal.getName(), availabilityId, request);
	}

	@DeleteMapping("/{availabilityId}")
	public ResponseEntity<Void> delete(@PathVariable Long availabilityId, Principal principal) {
		availabilityService.delete(principal.getName(), availabilityId);
		return ResponseEntity.noContent().build();
	}

}
