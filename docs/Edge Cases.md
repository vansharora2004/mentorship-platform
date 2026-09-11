# Mentorship Platform — Edge Cases

This document defines edge cases that must be considered during implementation and testing.

---

# 1. Authentication Edge Cases

## Invalid credentials

```text
Wrong email/password
```

Expected:

```text
401 Unauthorized
```

## Duplicate email

A user attempts to register with an existing email.

Expected:

```text
409 Conflict
```

## Missing JWT

Protected endpoint called without token.

Expected:

```text
401 Unauthorized
```

## Expired JWT

Client sends an expired token.

Expected:

```text
401 Unauthorized
```

## Invalid JWT

Tampered or malformed token.

Expected:

```text
401 Unauthorized
```

---

# 2. Authorization Edge Cases

A candidate attempts to update a mentor profile.

Expected:

```text
403 Forbidden
```

A mentor attempts to modify another mentor's availability.

Expected:

```text
403 Forbidden
```

Users must only access resources they are authorized to access.

---

# 3. Mentor Profile Edge Cases

## Mentor does not exist

```text
GET /mentors/999999
```

Expected:

```text
404 Not Found
```

## Invalid profile data

Examples:

```text
Empty name
Invalid industry
Invalid expertise
Invalid experience
```

Expected:

```text
400 Bad Request
```

---

# 4. Availability Edge Cases

## Start time after end time

```text
15:00 → 14:00
```

Reject the request.

## Start time equals end time

```text
15:00 → 15:00
```

Reject.

## Overlapping availability

Example:

```text
10:00 - 12:00
11:00 - 13:00
```

The system should either reject the second slot or merge slots according to the defined business rule.

## Past availability

A mentor should generally not create an availability slot in the past.

## Availability of another mentor

A candidate must not be able to modify availability.

---

# 5. Booking Edge Cases

## Slot already booked

Expected:

```text
409 Conflict
```

## Mentor does not exist

Expected:

```text
404 Not Found
```

## Candidate does not exist

Expected:

```text
404 Not Found
```

## Booking in the past

Reject.

## Booking outside availability

Reject.

## Booking exactly at boundary

Example:

```text
Availability:
10:00 - 11:00

Request:
11:00 - 12:00
```

The business rule must clearly define whether boundary-touching slots are allowed.

---

# 6. Double Booking Edge Case

This is the most important concurrency case.

Two requests arrive simultaneously:

```text
Request A ──┐
            ├── Slot 100
Request B ──┘
```

Expected:

```text
A → Success
B → Conflict
```

Never:

```text
A → Success
B → Success
```

---

# 7. Transaction Failure

Suppose:

```text
Create booking
      ↓
Create session
      ↓
Database error
```

The transaction should roll back.

There should not be:

```text
Booking exists
Session does not exist
```

if both are intended to be part of the same transaction.

---

# 8. Cancellation Edge Cases

## Already cancelled booking

Reject or return idempotent success according to the API contract.

## Completed booking

A completed session should not be cancellable.

## Another user's booking

Reject authorization.

## Cancellation after session start

Reject if business rules don't allow cancellation.

---

# 9. Redis Edge Cases

## Redis unavailable

The application should ideally continue operating for operations that can safely fall back to PostgreSQL.

Example:

```text
Redis
  ↓
Unavailable
  ↓
PostgreSQL
```

## Stale cache

Profile changes should invalidate cached data.

## Cache contains old availability

Booking must not rely solely on Redis.

PostgreSQL remains authoritative.

---

# 10. RabbitMQ Edge Cases

## RabbitMQ unavailable

The booking transaction should not incorrectly be rolled back merely because notification delivery failed, if the system is designed for eventual notification.

The notification event should be recoverable.

## Consumer failure

A failed message should be retried or routed to a dead-letter queue depending on the configured reliability strategy.

## Duplicate message

Consumers should ideally be idempotent.

Example:

```text
BOOKING_CONFIRMED
```

should not result in multiple unwanted notifications if the same message is processed more than once.

---

# 11. Scheduler Edge Cases

## Application restart

Scheduled jobs should continue correctly after restart.

## Duplicate execution

If multiple application instances are running:

```text
Instance A → Scheduler
Instance B → Scheduler
```

both might execute the same job.

The architecture should account for distributed scheduling if deployed horizontally.

## Missed scheduler execution

If the application was down during a scheduled execution, the system should determine whether missed tasks need to be recovered.

---

# 12. WebSocket Edge Cases

## Client disconnects

The server should handle disconnections gracefully.

## User joins invalid session

Reject.

## User not part of session

Reject access.

## Session is completed

New chat messages may be rejected according to business rules.

## Invalid message

Examples:

```text
Empty message
Extremely large message
Malformed content
```

Reject.

---

# 13. Concurrent Profile Updates

Two requests modify the same profile simultaneously.

The system should define how conflicts are handled.

Possible approaches:

```text
Last write wins
```

or optimistic locking:

```text
@Version
```

---

# 14. Database Edge Cases

## Duplicate email

Database unique constraint should enforce uniqueness.

## Foreign key violation

Invalid mentor/candidate references should be rejected.

## Database unavailable

Return an appropriate server error and log the failure.

## Connection pool exhaustion

Monitor and configure connection pool limits appropriately.

---

# 15. Input Validation

Validate:

- Email format
- Password requirements
- Name length
- Expertise
- Industry
- Date/time
- Pagination parameters
- IDs
- Message size

Never trust client-side validation alone.

---

# 16. Pagination Edge Cases

```text
page = -1
size = 0
size = 1000000
```

These should be validated.

A maximum page size should be enforced.

---

# 17. Time Zone Edge Cases

Mentors and candidates may be in different time zones.

The system should establish a clear rule.

Recommended approach:

```text
Store timestamps in UTC
        ↓
Convert to user's local timezone for display
```

This avoids many scheduling inconsistencies.

---

# 18. Daylight Saving Time

If the platform eventually supports users across regions using daylight saving time, recurring availability must be handled carefully.

Use timezone-aware Java date/time APIs rather than manually manipulating offsets.

---

# 19. Chat Security

A user must not be able to subscribe to another private session's chat simply by changing:

```text
sessionId=123
```

Authorization must happen on the server.

---

# 20. Security Edge Cases

Protect against:

- SQL injection
- Unauthorized resource access
- Token tampering
- Brute-force login attempts
- Excessively large requests
- Malicious WebSocket messages
- Sensitive information leakage

Parameterized queries/JPA should be used instead of constructing SQL from raw user input.

---

# 21. Idempotency

Operations such as booking or cancellation can potentially be retried.

The API should consider idempotency where appropriate.

Example:

```text
Client sends booking request
Server processes it
Network fails
Client retries
```

The system should avoid accidentally creating duplicate bookings.

---

# 22. Notification Failure

Booking:

```text
SUCCESS
```

Notification:

```text
FAILED
```

The booking should remain correct.

Notification processing should be independently recoverable.

---

# 23. System Failure During Booking

If the application crashes before transaction commit:

```text
Transaction → rollback
```

If it crashes after commit:

```text
Booking → remains persisted
```

The system should maintain database consistency.

---

# 24. Final Edge Case Priority

Highest priority:

```text
1. Double booking
2. Transaction failure
3. Unauthorized access
4. Invalid JWT
5. Stale availability cache
6. RabbitMQ failure
7. WebSocket authorization
8. Time zone problems
9. Duplicate requests
10. Database failure
```