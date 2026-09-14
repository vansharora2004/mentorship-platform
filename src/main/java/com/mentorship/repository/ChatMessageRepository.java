package com.mentorship.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mentorship.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findBySessionIdOrderBySentAtAsc(Long sessionId);

}
