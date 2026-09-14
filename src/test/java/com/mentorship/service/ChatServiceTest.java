package com.mentorship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.mentorship.dto.ChatMessageRequest;
import com.mentorship.dto.ChatMessageResponse;
import com.mentorship.entity.Booking;
import com.mentorship.entity.ChatMessage;
import com.mentorship.entity.Role;
import com.mentorship.entity.Session;
import com.mentorship.entity.SessionStatus;
import com.mentorship.entity.User;
import com.mentorship.exception.InvalidChatMessageException;
import com.mentorship.exception.SessionNotFoundException;
import com.mentorship.repository.ChatMessageRepository;
import com.mentorship.repository.SessionRepository;
import com.mentorship.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

	private static final Long SESSION_ID = 55L;

	private static final String CANDIDATE = "candidate@example.com";

	private static final String MENTOR = "mentor@example.com";

	private static final String STRANGER = "stranger@example.com";

	@Mock
	private ChatMessageRepository chatMessageRepository;

	@Mock
	private SessionRepository sessionRepository;

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private ChatService chatService;

	@Test
	void storesAMessageSentByAParticipant() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));
		given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(call -> call.getArgument(0));

		var response = chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("Here is a useful link"));

		assertThat(response.content()).isEqualTo("Here is a useful link");
		assertThat(response.senderId()).isEqualTo(10L);
		assertThat(response.sessionId()).isEqualTo(SESSION_ID);
	}

	@Test
	void mentorMayAlsoSend() {
		givenSession(SessionStatus.SCHEDULED);
		given(userRepository.findByEmail(MENTOR)).willReturn(Optional.of(user(20L, MENTOR, Role.MENTOR)));
		given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(call -> call.getArgument(0));

		assertThat(chatService.post(MENTOR, SESSION_ID, new ChatMessageRequest("Welcome")).senderId()).isEqualTo(20L);
	}

	@Test
	void rejectsAUserWhoIsNotPartOfTheSession() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(STRANGER)).willReturn(Optional.of(user(99L, STRANGER, Role.CANDIDATE)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> chatService.post(STRANGER, SESSION_ID, new ChatMessageRequest("Let me in")));

		verify(chatMessageRepository, never()).save(any());
	}

	@Test
	void rejectsAnUnknownSession() {
		given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

		assertThatExceptionOfType(SessionNotFoundException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("Anyone there")));
	}

	@Test
	void rejectsMessagesOnACompletedSession() {
		givenSession(SessionStatus.COMPLETED);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));

		assertThatExceptionOfType(InvalidChatMessageException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("Too late")));

		verify(chatMessageRepository, never()).save(any());
	}

	@Test
	void rejectsMessagesOnACancelledSession() {
		givenSession(SessionStatus.CANCELLED);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));

		assertThatExceptionOfType(InvalidChatMessageException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("Hello")));
	}

	@Test
	void rejectsBlankContent() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));

		assertThatExceptionOfType(InvalidChatMessageException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("   ")));
	}

	@Test
	void rejectsOversizedContent() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));
		String huge = "x".repeat(ChatMessage.MAX_LENGTH + 1);

		assertThatExceptionOfType(InvalidChatMessageException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest(huge)));

		verify(chatMessageRepository, never()).save(any());
	}

	@Test
	void rejectsAMissingPayload() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));

		assertThatExceptionOfType(InvalidChatMessageException.class)
				.isThrownBy(() -> chatService.post(CANDIDATE, SESSION_ID, null));
	}

	@Test
	void trimsSurroundingWhitespace() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));
		given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(call -> call.getArgument(0));

		assertThat(chatService.post(CANDIDATE, SESSION_ID, new ChatMessageRequest("  spaced  ")).content())
				.isEqualTo("spaced");
	}

	@Test
	void historyReturnsTheStoredTranscriptForAParticipant() {
		givenSession(SessionStatus.COMPLETED);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));
		given(chatMessageRepository.findBySessionIdOrderBySentAtAsc(SESSION_ID))
				.willReturn(List.of(storedMessage("first"), storedMessage("second")));

		// A completed session still exposes its transcript, even though it rejects new messages.
		assertThat(chatService.history(CANDIDATE, SESSION_ID))
				.extracting(ChatMessageResponse::content)
				.containsExactly("first", "second");
	}

	@Test
	void historyIsRefusedToANonParticipant() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(STRANGER)).willReturn(Optional.of(user(99L, STRANGER, Role.CANDIDATE)));

		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> chatService.history(STRANGER, SESSION_ID));

		verify(chatMessageRepository, never()).findBySessionIdOrderBySentAtAsc(any());
	}

	@Test
	void historyOfAnUnknownSessionIsReported() {
		given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

		assertThatExceptionOfType(SessionNotFoundException.class)
				.isThrownBy(() -> chatService.history(CANDIDATE, SESSION_ID));
	}

	@Test
	void participantCheckIsTrueForBothSidesAndFalseForAnyoneElse() {
		givenSession(SessionStatus.ACTIVE);
		given(userRepository.findByEmail(CANDIDATE)).willReturn(Optional.of(user(10L, CANDIDATE, Role.CANDIDATE)));

		assertThat(chatService.isParticipant(SESSION_ID, CANDIDATE)).isTrue();
	}

	@Test
	void participantCheckIsFalseForAnUnknownSession() {
		given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

		assertThat(chatService.isParticipant(SESSION_ID, CANDIDATE)).isFalse();
	}

	private void givenSession(SessionStatus status) {
		given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session(status)));
	}

	private ChatMessage storedMessage(String content) {
		ChatMessage message = new ChatMessage();
		message.setId(1L);
		message.setSession(session(SessionStatus.ACTIVE));
		message.setSender(user(10L, CANDIDATE, Role.CANDIDATE));
		message.setContent(content);
		message.setSentAt(Instant.now());
		return message;
	}

	private Session session(SessionStatus status) {
		Booking booking = new Booking();
		booking.setId(500L);
		booking.setCandidate(user(10L, CANDIDATE, Role.CANDIDATE));
		booking.setMentor(user(20L, MENTOR, Role.MENTOR));

		Session session = new Session();
		session.setId(SESSION_ID);
		session.setBooking(booking);
		session.setStatus(status);
		return session;
	}

	private User user(Long id, String email, Role role) {
		User user = new User();
		user.setId(id);
		user.setEmail(email);
		user.setName(email.substring(0, email.indexOf('@')));
		user.setRole(role);
		return user;
	}

}
