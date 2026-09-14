package com.mentorship.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;


@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

	/** Malformed or unparseable JSON. The parser's own message names internal types, so it is not echoed. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return build(HttpStatus.BAD_REQUEST, "Malformed request body");
	}

	/** A path variable or query parameter of the wrong type, such as /api/bookings/abc. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return build(HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' has an invalid value");
	}

	/** Constraint violations raised outside request-body binding. */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
		Map<String, String> fields = new HashMap<>();
		ex.getConstraintViolations()
				.forEach(violation -> fields.put(violation.getPropertyPath().toString(), violation.getMessage()));

		ErrorResponse body = new ErrorResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(),
				HttpStatus.BAD_REQUEST.getReasonPhrase(), "Request validation failed", fields);

		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	/** An unmapped URL, so a wrong path returns the same JSON shape as every other error. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
		return build(HttpStatus.NOT_FOUND, "The requested resource was not found");
	}

	/**
	 * Last resort for anything unmapped.
	 *
	 * <p>The cause is logged in full for operators, and deliberately NOT returned: exception
	 * messages routinely carry SQL fragments, table and column names, file paths, and library
	 * internals. Returning them would hand an attacker a map of the system, which the security
	 * requirement "avoid exposing sensitive information" rules out. Clients get a fixed sentence.
	 *
	 * <p>Declared last and typed to Exception, so every handler above still wins for its own type.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}

	private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
		return ResponseEntity.status(status)
				.body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message));
	}

}
