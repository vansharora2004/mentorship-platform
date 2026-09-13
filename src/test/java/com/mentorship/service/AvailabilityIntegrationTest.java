package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.mentorship.dto.AvailabilityRequest;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.entity.AvailabilityStatus;
import com.mentorship.entity.Role;
import com.mentorship.entity.User;
import com.mentorship.exception.AvailabilityConflictException;
import com.mentorship.repository.AvailabilityRepository;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.UserRepository;

// Verifies the MentorProfile -> Availability mapping and the derived queries against PostgreSQL.
@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.jwt.secret=test-secret-key-that-is-long-enough-for-hs256")
class AvailabilityIntegrationTest {

	private static final Instant START = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

	private static final Instant END = START.plus(1, ChronoUnit.HOURS);

	@Autowired
	private AvailabilityService availabilityService;

	@Autowired
	private MentorService mentorService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MentorProfileRepository mentorProfileRepository;

	@Autowired
	private AvailabilityRepository availabilityRepository;

	private User mentorWithProfile(String email) {
		User user = new User();
		user.setName("Availability Mentor");
		user.setEmail(email);
		user.setPasswordHash("hashed");
		user.setRole(Role.MENTOR);
		User saved = userRepository.saveAndFlush(user);

		mentorService.createProfile(email, new MentorProfileRequest("FinTech", "Java", 8, null));
		return saved;
	}

	@Test
	void persistsSlotAndLinksItToTheMentorProfile() {
		User mentor = mentorWithProfile("avail.create@example.com");

		var created = availabilityService.create("avail.create@example.com", new AvailabilityRequest(START, END));

		assertThat(created.id()).isNotNull();
		assertThat(created.mentorId()).isEqualTo(mentor.getId());
		assertThat(created.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
		assertThat(created.createdAt()).isNotNull();

		var profile = mentorProfileRepository.findByUserEmail("avail.create@example.com").orElseThrow();
		assertThat(availabilityRepository.findByMentorProfileIdOrderByStartTimeAsc(profile.getId())).hasSize(1);
	}

	@Test
	void findsSlotsByMentorUserIdForDiscovery() {
		User mentor = mentorWithProfile("avail.discovery@example.com");
		availabilityService.create("avail.discovery@example.com", new AvailabilityRequest(START, END));

		assertThat(availabilityService.listForMentor(mentor.getId())).hasSize(1);
	}

	@Test
	void returnsSlotsOrderedByStartTime() {
		mentorWithProfile("avail.order@example.com");
		Instant later = START.plus(5, ChronoUnit.HOURS);

		availabilityService.create("avail.order@example.com", new AvailabilityRequest(later, later.plusSeconds(3600)));
		availabilityService.create("avail.order@example.com", new AvailabilityRequest(START, END));

		assertThat(availabilityService.listOwn("avail.order@example.com"))
				.extracting("startTime")
				.containsExactly(START, later);
	}

	@Test
	void rejectsAnOverlappingSlotForTheSameMentor() {
		mentorWithProfile("avail.overlap@example.com");
		availabilityService.create("avail.overlap@example.com", new AvailabilityRequest(START, END));

		assertThatExceptionOfType(AvailabilityConflictException.class)
				.isThrownBy(() -> availabilityService.create("avail.overlap@example.com",
						new AvailabilityRequest(START.plus(30, ChronoUnit.MINUTES), END.plusSeconds(1800))));
	}

	@Test
	void allowsSlotsThatOnlyTouchAtTheBoundary() {
		mentorWithProfile("avail.touch@example.com");
		availabilityService.create("avail.touch@example.com", new AvailabilityRequest(START, END));

		var second = availabilityService.create("avail.touch@example.com",
				new AvailabilityRequest(END, END.plus(1, ChronoUnit.HOURS)));

		assertThat(second.id()).isNotNull();
		assertThat(availabilityService.listOwn("avail.touch@example.com")).hasSize(2);
	}

	@Test
	void allowsIdenticalSlotsForDifferentMentors() {
		mentorWithProfile("avail.first@example.com");
		mentorWithProfile("avail.second@example.com");

		availabilityService.create("avail.first@example.com", new AvailabilityRequest(START, END));
		var other = availabilityService.create("avail.second@example.com", new AvailabilityRequest(START, END));

		assertThat(other.id()).isNotNull();
	}

	@Test
	void mentorCannotModifyAnotherMentorsSlot() {
		mentorWithProfile("avail.owner@example.com");
		mentorWithProfile("avail.intruder@example.com");
		var slot = availabilityService.create("avail.owner@example.com", new AvailabilityRequest(START, END));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> availabilityService.delete("avail.intruder@example.com", slot.id()));
	}

	@Test
	void updatesAndDeletesOwnSlot() {
		mentorWithProfile("avail.edit@example.com");
		var slot = availabilityService.create("avail.edit@example.com", new AvailabilityRequest(START, END));

		Instant moved = START.plus(3, ChronoUnit.HOURS);
		var updated = availabilityService.update("avail.edit@example.com", slot.id(),
				new AvailabilityRequest(moved, moved.plusSeconds(3600)));

		assertThat(updated.startTime()).isEqualTo(moved);

		availabilityService.delete("avail.edit@example.com", slot.id());

		assertThat(availabilityService.listOwn("avail.edit@example.com")).isEmpty();
	}

}
