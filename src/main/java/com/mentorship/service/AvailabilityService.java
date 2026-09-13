package com.mentorship.service;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.MentorProfile;
import com.mentorship.exception.AvailabilityConflictException;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.InvalidAvailabilityException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.MentorProfileRepository;

@Service
public class AvailabilityService {

	private final AvailabilityRepository availabilityRepository;

	private final MentorProfileRepository mentorProfileRepository;

	public AvailabilityService(AvailabilityRepository availabilityRepository,
			MentorProfileRepository mentorProfileRepository) {
		this.availabilityRepository = availabilityRepository;
		this.mentorProfileRepository = mentorProfileRepository;
	}

	@Transactional
	public AvailabilityResponse create(String email, AvailabilityRequest request) {
		MentorProfile profile = ownProfile(email);

		validateRange(request);
		validateNoOverlap(profile.getId(), request, null);

		Availability slot = new Availability();
		slot.setMentorProfile(profile);
		slot.setStartTime(request.startTime());
		slot.setEndTime(request.endTime());
		slot.setStatus(AvailabilityStatus.AVAILABLE);

		return AvailabilityResponse.from(availabilityRepository.save(slot));
	}

	@Transactional(readOnly = true)
	public List<AvailabilityResponse> listOwn(String email) {
		MentorProfile profile = ownProfile(email);

		return availabilityRepository.findByMentorProfileIdOrderByStartTimeAsc(profile.getId())
				.stream()
				.map(AvailabilityResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<AvailabilityResponse> listForMentor(Long mentorId) {
		return availabilityRepository.findByMentorProfileUserIdOrderByStartTimeAsc(mentorId)
				.stream()
				.map(AvailabilityResponse::from)
				.toList();
	}

	@Transactional
	public AvailabilityResponse update(String email, Long availabilityId, AvailabilityRequest request) {
		Availability slot = ownedSlot(email, availabilityId);

		if (slot.getStatus() != AvailabilityStatus.AVAILABLE) {
			throw AvailabilityConflictException.notModifiable();
		}

		validateRange(request);
		validateNoOverlap(slot.getMentorProfile().getId(), request, slot.getId());

		slot.setStartTime(request.startTime());
		slot.setEndTime(request.endTime());

		return AvailabilityResponse.from(availabilityRepository.save(slot));
	}

	@Transactional
	public void delete(String email, Long availabilityId) {
		Availability slot = ownedSlot(email, availabilityId);

		if (slot.getStatus() != AvailabilityStatus.AVAILABLE) {
			throw AvailabilityConflictException.notModifiable();
		}

		availabilityRepository.delete(slot);
	}

	private MentorProfile ownProfile(String email) {
		return mentorProfileRepository.findByUserEmail(email)
				.orElseThrow(MentorProfileNotFoundException::forCurrentUser);
	}

	// Ownership is derived from the slot's own mentor, never from anything the caller sent.
	private Availability ownedSlot(String email, Long availabilityId) {
		Availability slot = availabilityRepository.findById(availabilityId)
				.orElseThrow(() -> new AvailabilityNotFoundException(availabilityId));

		if (!slot.getMentorProfile().getUser().getEmail().equals(email)) {
			throw new AccessDeniedException("You may only modify your own availability");
		}

		return slot;
	}

	private void validateRange(AvailabilityRequest request) {
		if (!request.startTime().isBefore(request.endTime())) {
			throw new InvalidAvailabilityException("startTime must be before endTime");
		}
		if (request.startTime().isBefore(Instant.now())) {
			throw new InvalidAvailabilityException("Availability cannot start in the past");
		}
	}

	private void validateNoOverlap(Long mentorProfileId, AvailabilityRequest request, Long excludedSlotId) {
		boolean overlaps = availabilityRepository
				.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(mentorProfileId, request.endTime(),
						request.startTime())
				.stream()
				.anyMatch(existing -> !existing.getId().equals(excludedSlotId));

		if (overlaps) {
			throw AvailabilityConflictException.overlapping();
		}
	}

}
