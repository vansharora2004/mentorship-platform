package com.mentorship.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "availability", indexes = {
		@Index(name = "idx_availability_mentor_profile", columnList = "mentor_profile_id"),
		@Index(name = "idx_availability_start_time", columnList = "start_time")
})
public class Availability {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "mentor_profile_id", nullable = false)
	private MentorProfile mentorProfile;

	@Column(name = "start_time", nullable = false)
	private Instant startTime;

	@Column(name = "end_time", nullable = false)
	private Instant endTime;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AvailabilityStatus status;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void onCreate() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
		if (status == null) {
			status = AvailabilityStatus.AVAILABLE;
		}
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public MentorProfile getMentorProfile() {
		return mentorProfile;
	}

	public void setMentorProfile(MentorProfile mentorProfile) {
		this.mentorProfile = mentorProfile;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public AvailabilityStatus getStatus() {
		return status;
	}

	public void setStatus(AvailabilityStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}
