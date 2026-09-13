package com.mentorship.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.mentorship.entity.MentorProfile;

public interface MentorProfileRepository
		extends JpaRepository<MentorProfile, Long>, JpaSpecificationExecutor<MentorProfile> {

	Optional<MentorProfile> findByUserId(Long userId);

	Optional<MentorProfile> findByUserEmail(String email);

	boolean existsByUserId(Long userId);

}
