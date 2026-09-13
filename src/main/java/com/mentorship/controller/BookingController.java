package com.mentorship.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentorship.dto.BookingRequest;
import com.mentorship.dto.BookingResponse;
import com.mentorship.service.BookingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

	private final BookingService bookingService;

	public BookingController(BookingService bookingService) {
		this.bookingService = bookingService;
	}

	@GetMapping
	public List<BookingResponse> list(Principal principal) {
		return bookingService.listForUser(principal.getName());
	}

	@GetMapping("/{bookingId}")
	public BookingResponse getById(@PathVariable Long bookingId, Principal principal) {
		return bookingService.getById(principal.getName(), bookingId);
	}

	@PostMapping
	public ResponseEntity<BookingResponse> create(@Valid @RequestBody BookingRequest request, Principal principal) {
		BookingResponse created = bookingService.create(principal.getName(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@DeleteMapping("/{bookingId}")
	public ResponseEntity<Void> cancel(@PathVariable Long bookingId, Principal principal) {
		bookingService.cancel(principal.getName(), bookingId);
		return ResponseEntity.noContent().build();
	}

}
