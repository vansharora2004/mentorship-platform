package com.mentorship.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mentorship.entity.Session;

public interface SessionRepository extends JpaRepository<Session, Long> {

	Optional<Session> findByBookingId(Long bookingId);

}
