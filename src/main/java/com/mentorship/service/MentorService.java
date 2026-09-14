package com.mentorship.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.mentorship.config.CacheEvictor;
import com.mentorship.dto.MentorProfileRequest;
import com.mentorship.dto.MentorProfileResponse;
import com.mentorship.entity.MentorProfile;
import com.mentorship.entity.User;
import com.mentorship.exception.MentorProfileAlreadyExistsException;
import com.mentorship.exception.MentorProfileNotFoundException;
import com.mentorship.repository.MentorProfileRepository;
import com.mentorship.repository.UserRepository;

import jakarta.persistence.criteria.Predicate;

@Service
public class MentorService {

	private final MentorProfileRepository mentorProfileRepository;

	private final UserRepository userRepository;

	private final CacheEvictor cacheEvictor;

	public MentorService(MentorProfileRepository mentorProfileRepository, UserRepository userRepository,
			CacheEvictor cacheEvictor) {
		this.mentorProfileRepository = mentorProfileRepository;
		this.userRepository = userRepository;
		this.cacheEvictor = cacheEvictor;
	}

	@CacheEvict(cacheNames = CacheEvictor.MENTOR_CACHE, key = "#result.mentorId()")
	@Transactional
	public MentorProfileResponse createProfile(String email, MentorProfileRequest request) {
		User mentor = userRepository.findByEmail(email).orElseThrow();

		if (mentorProfileRepository.existsByUserId(mentor.getId())) {
			throw new MentorProfileAlreadyExistsException();
		}

		MentorProfile profile = new MentorProfile();
		profile.setUser(mentor);
		apply(profile, request);

		return MentorProfileResponse.from(mentorProfileRepository.save(profile));
	}

	@CacheEvict(cacheNames = CacheEvictor.MENTOR_CACHE, key = "#result.mentorId()")
	@Transactional
	public MentorProfileResponse updateProfile(String email, MentorProfileRequest request) {
		MentorProfile profile = ownedProfile(email);
		apply(profile, request);

		return MentorProfileResponse.from(mentorProfileRepository.save(profile));
	}

	@Transactional
	public void deleteProfile(String email) {
		MentorProfile profile = ownedProfile(email);
		Long mentorId = profile.getUser().getId();

		mentorProfileRepository.delete(profile);

		cacheEvictor.evictMentor(mentorId);
	}

	@Transactional(readOnly = true)
	public MentorProfileResponse getOwnProfile(String email) {
		return MentorProfileResponse.from(ownedProfile(email));
	}

	@Cacheable(cacheNames = CacheEvictor.MENTOR_CACHE, key = "#mentorId")
	@Transactional(readOnly = true)
	public MentorProfileResponse getByMentorId(Long mentorId) {
		return mentorProfileRepository.findByUserId(mentorId)
				.map(MentorProfileResponse::from)
				.orElseThrow(() -> MentorProfileNotFoundException.forMentorId(mentorId));
	}

	@Transactional(readOnly = true)
	public Page<MentorProfileResponse> search(String industry, String expertise, Pageable pageable) {
		return mentorProfileRepository.findAll(filterBy(industry, expertise), pageable)
				.map(MentorProfileResponse::from);
	}

	// Resolves the profile from the authenticated email only, so a client-supplied id can never widen access.
	private MentorProfile ownedProfile(String email) {
		return mentorProfileRepository.findByUserEmail(email)
				.orElseThrow(MentorProfileNotFoundException::forCurrentUser);
	}

	private void apply(MentorProfile profile, MentorProfileRequest request) {
		profile.setIndustry(request.industry());
		profile.setExpertise(request.expertise());
		profile.setExperience(request.experience());
		profile.setBio(request.bio());
	}

	private static Specification<MentorProfile> filterBy(String industry, String expertise) {
		return (root, query, builder) -> {
			List<Predicate> predicates = new ArrayList<>();

			if (StringUtils.hasText(industry)) {
				predicates.add(builder.equal(builder.lower(root.get("industry")),
						industry.strip().toLowerCase()));
			}
			if (StringUtils.hasText(expertise)) {
				predicates.add(builder.like(builder.lower(root.get("expertise")),
						"%" + expertise.strip().toLowerCase() + "%"));
			}

			return builder.and(predicates.toArray(new Predicate[0]));
		};
	}

}
