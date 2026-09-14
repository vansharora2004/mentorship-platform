package com.mentorship.entity;

public enum OutboxStatus {

	/** Recorded in the booking transaction, not yet acknowledged by RabbitMQ. */
	PENDING,

	/** Handed to RabbitMQ successfully; no further publishing is required. */
	SENT

}
