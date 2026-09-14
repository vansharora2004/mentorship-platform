package com.mentorship.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mentorship.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	boolean existsByEventIdAndRecipientId(String eventId, Long recipientId);

	List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

	List<Notification> findByEventId(String eventId);

}
