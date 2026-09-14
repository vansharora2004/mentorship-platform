package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.entity.Availability;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.AvailabilityConflictException;
import com.mentorship.exception.AvailabilityNotFoundException;
import com.mentorship.exception.InvalidAvailabilityException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.MentorProfileRepository;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

	private static final String OWNER = "mentor@example.com";

	private static final String OTHER = "other@example.com";

	private static final Instant START = Instant.now().plus(1, ChronoUnit.DAYS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Mock
	private AvailabilityRepository availabilityRepository;

	@Mock
	private MentorProfileRepository mentorProfileRepository;

	@Mock
	private CacheEvictor cacheEvictor;

	@InjectMocks
	private AvailabilityService availabilityService;

	private final AvailabilityRequest request = new AvailabilityRequest(START, END);

	@Test
	void createsSlotForTheAuthenticatedMentor() {
		givenOwnProfile();
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(5L, END, START))
				.willReturn(List.of());
		given(availabilityRepository.save(any(Availability.class))).willAnswer(call -> call.getArgument(0));

		AvailabilityResponse response = availabilityService.create(OWNER, request);

		ArgumentCaptor<Availability> saved = ArgumentCaptor.forClass(Availability.class);
		verify(availabilityRepository).save(saved.capture());

		assertThat(saved.getValue().getMentorProfile().getId()).isEqualTo(5L);
		assertThat(saved.getValue().getStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
		assertThat(response.startTime()).isEqualTo(START);
		assertThat(response.mentorId()).isEqualTo(7L);
	}

	@Test
	void overlapIsCheckedWithHalfOpenIntervalArguments() {
		givenOwnProfile();
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(5L, END, START))
				.willReturn(List.of());
		given(availabilityRepository.save(any(Availability.class))).willAnswer(call -> call.getArgument(0));

		availabilityService.create(OWNER, request);

		verify(availabilityRepository).findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(5L, END, START);
	}

	@Test
	void createFailsWhenTheMentorHasNoProfile() {
		given(mentorProfileRepository.findByUserEmail(OWNER)).willReturn(Optional.empty());

		assertThatExceptionOfType(MentorProfileNotFoundException.class)
				.isThrownBy(() -> availabilityService.create(OWNER, request));

		verify(availabilityRepository, never()).save(any());
	}

	@Test
	void createRejectsStartAfterEnd() {
		givenOwnProfile();

		assertThatExceptionOfType(InvalidAvailabilityException.class)
				.isThrownBy(() -> availabilityService.create(OWNER, new AvailabilityRequest(END, START)));

		verify(availabilityRepository, never()).save(any());
	}

	@Test
	void createRejectsZeroLengthSlot() {
		givenOwnProfile();

		assertThatExceptionOfType(InvalidAvailabilityException.class)
				.isThrownBy(() -> availabilityService.create(OWNER, new AvailabilityRequest(START, START)));
	}

	@Test
	void createRejectsSlotStartingInThePast() {
		givenOwnProfile();
		Instant past = Instant.now().minus(2, ChronoUnit.HOURS);

		assertThatExceptionOfType(InvalidAvailabilityException.class)
				.isThrownBy(() -> availabilityService.create(OWNER,
						new AvailabilityRequest(past, past.plus(1, ChronoUnit.HOURS))));
	}

	@Test
	void createRejectsOverlappingSlot() {
		givenOwnProfile();
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(5L, END, START))
				.willReturn(List.of(slot(99L, AvailabilityStatus.AVAILABLE)));

		assertThatExceptionOfType(AvailabilityConflictException.class)
				.isThrownBy(() -> availabilityService.create(OWNER, request));

		verify(availabilityRepository, never()).save(any());
	}

	@Test
	void updatesOwnSlot() {
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(slot(1L, AvailabilityStatus.AVAILABLE)));
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(anyLong(), any(),
				any())).willReturn(List.of());
		given(availabilityRepository.save(any(Availability.class))).willAnswer(call -> call.getArgument(0));

		Instant newStart = START.plus(3, ChronoUnit.HOURS);
		AvailabilityResponse response = availabilityService.update(OWNER, 1L,
				new AvailabilityRequest(newStart, newStart.plus(1, ChronoUnit.HOURS)));

		assertThat(response.startTime()).isEqualTo(newStart);
	}

	@Test
	void updateIgnoresTheSlotBeingEditedWhenDetectingOverlap() {
		Availability existing = slot(1L, AvailabilityStatus.AVAILABLE);
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(existing));
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(anyLong(), any(),
				any())).willReturn(List.of(existing));
		given(availabilityRepository.save(any(Availability.class))).willAnswer(call -> call.getArgument(0));

		assertThatNoException().isThrownBy(() -> availabilityService.update(OWNER, 1L, request));
	}

	@Test
	void updateRejectsOverlapWithADifferentSlot() {
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(slot(1L, AvailabilityStatus.AVAILABLE)));
		given(availabilityRepository.findByMentorProfileIdAndStartTimeLessThanAndEndTimeGreaterThan(anyLong(), any(),
				any())).willReturn(List.of(slot(2L, AvailabilityStatus.AVAILABLE)));

		assertThatExceptionOfType(AvailabilityConflictException.class)
				.isThrownBy(() -> availabilityService.update(OWNER, 1L, request));
	}

	@Test
	void updateFailsForAMissingSlot() {
		given(availabilityRepository.findById(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(AvailabilityNotFoundException.class)
				.isThrownBy(() -> availabilityService.update(OWNER, 404L, request));
	}

	@Test
	void mentorCannotUpdateAnotherMentorsSlot() {
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(slot(1L, AvailabilityStatus.AVAILABLE)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> availabilityService.update(OTHER, 1L, request));

		verify(availabilityRepository, never()).save(any());
	}

	@Test
	void updateRejectsASlotThatIsNotAvailable() {
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(slot(1L, AvailabilityStatus.BLOCKED)));

		assertThatExceptionOfType(AvailabilityConflictException.class)
				.isThrownBy(() -> availabilityService.update(OWNER, 1L, request));
	}

	@Test
	void deletesOwnSlot() {
		Availability existing = slot(1L, AvailabilityStatus.AVAILABLE);
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(existing));

		availabilityService.delete(OWNER, 1L);

		verify(availabilityRepository).delete(existing);
		verify(cacheEvictor).evictAvailability(7L);
	}

	@Test
	void deleteFailsForAMissingSlot() {
		given(availabilityRepository.findById(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(AvailabilityNotFoundException.class)
				.isThrownBy(() -> availabilityService.delete(OWNER, 404L));
	}

	@Test
	void mentorCannotDeleteAnotherMentorsSlot() {
		given(availabilityRepository.findById(1L)).willReturn(Optional.of(slot(1L, AvailabilityStatus.AVAILABLE)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> availabilityService.delete(OTHER, 1L));

		verify(availabilityRepository, never()).delete(any(Availability.class));
	}

	@Test
	void listsOwnSlots() {
		givenOwnProfile();
		given(availabilityRepository.findByMentorProfileIdOrderByStartTimeAsc(5L))
				.willReturn(List.of(slot(1L, AvailabilityStatus.AVAILABLE)));

		assertThat(availabilityService.listOwn(OWNER)).hasSize(1);
	}

	@Test
	void listsSlotsForDiscoveryByMentorId() {
		given(availabilityRepository.findByMentorProfileUserIdOrderByStartTimeAsc(7L))
				.willReturn(List.of(slot(1L, AvailabilityStatus.AVAILABLE)));

		List<AvailabilityResponse> slots = availabilityService.listForMentor(7L);

		assertThat(slots).hasSize(1);
		assertThat(slots.getFirst().mentorId()).isEqualTo(7L);
	}

	private void givenOwnProfile() {
		given(mentorProfileRepository.findByUserEmail(OWNER)).willReturn(Optional.of(profile()));
	}

	private MentorProfile profile() {
		User user = new User();
		user.setId(7L);
		user.setName("Mentor Mentorson");
		user.setEmail(OWNER);
		user.setPasswordHash("hashed");
		user.setRole(Role.MENTOR);

		MentorProfile profile = new MentorProfile();
		profile.setId(5L);
		profile.setUser(user);
		profile.setIndustry("FinTech");
		profile.setExpertise("Java");
		profile.setExperience(8);
		return profile;
	}

	private Availability slot(Long id, AvailabilityStatus status) {
		Availability slot = new Availability();
		slot.setId(id);
		slot.setMentorProfile(profile());
		slot.setStartTime(START);
		slot.setEndTime(END);
		slot.setStatus(status);
		slot.setCreatedAt(Instant.now());
		return slot;
	}

}
