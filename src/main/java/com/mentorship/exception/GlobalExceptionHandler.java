package com.mentorship.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(EmailAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
		return build(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(MentorProfileNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleMentorProfileNotFound(MentorProfileNotFoundException ex) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(MentorProfileAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleMentorProfileAlreadyExists(MentorProfileAlreadyExistsException ex) {
		return build(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(AvailabilityNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleAvailabilityNotFound(AvailabilityNotFoundException ex) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(AvailabilityConflictException.class)
	public ResponseEntity<ErrorResponse> handleAvailabilityConflict(AvailabilityConflictException ex) {
		return build(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(InvalidAvailabilityException.class)
	public ResponseEntity<ErrorResponse> handleInvalidAvailability(InvalidAvailabilityException ex) {
		return build(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(BookingNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(BookingConflictException.class)
	public ResponseEntity<ErrorResponse> handleBookingConflict(BookingConflictException ex) {
		return build(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(InvalidBookingException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBooking(InvalidBookingException ex) {
		return build(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(SessionNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(InvalidChatMessageException.class)
	public ResponseEntity<ErrorResponse> handleInvalidChatMessage(InvalidChatMessageException ex) {
		return build(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	// Concurrent registrations that pass the pre-check are stopped by the unique constraint.
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		return build(HttpStatus.CONFLICT, "The request conflicts with existing data");
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
		return build(HttpStatus.UNAUTHORIZED, "Invalid email or password");
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return build(HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new HashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));

		ErrorResponse body = new ErrorResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(),
				HttpStatus.BAD_REQUEST.getReasonPhrase(), "Request validation failed", fields);

		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
		return ResponseEntity.status(status)
				.body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
	}

}
