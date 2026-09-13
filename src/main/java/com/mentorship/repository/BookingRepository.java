package com.mentorship.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mentorship.entity.Booking;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	List<Booking> findByCandidateIdOrderByStartTimeDesc(Long candidateId);

	List<Booking> findByMentorIdOrderByStartTimeDesc(Long mentorId);

}
