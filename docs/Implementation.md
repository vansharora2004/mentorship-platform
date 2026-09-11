# Mentorship Platform — Implementation Plan

## Overview

The project will be implemented incrementally.

Each phase should produce a working and testable result before moving to the next phase.

The recommended implementation order is:

```text
Phase 1  → Project Setup
Phase 2  → Database + JPA
Phase 3  → Authentication
Phase 4  → Mentor Management
Phase 5  → Availability
Phase 6  → Booking
Phase 7  → Concurrency Protection
Phase 8  → Redis
Phase 9  → RabbitMQ
Phase 10 → Scheduler
Phase 11 → WebSocket Chat
Phase 12 → Testing
Phase 13 → Documentation
Phase 14 → GitHub
```

---

# Phase 1 — Project Setup

## Goal

Create a basic Spring Boot application that runs successfully.

## Tasks

1. Install Java.
2. Install Maven.
3. Install VS Code.
4. Configure Cursor.
5. Install Git.
6. Create a GitHub repository.
7. Generate Spring Boot project.
8. Configure Maven.
9. Create package structure.
10. Run the application.

Initial dependencies:

```text
Spring Web
Spring Data JPA
PostgreSQL Driver
Spring Security
Validation
Lombok
```

Additional dependencies will be added when their phases begin.

## Expected Result

The application should start successfully.

Example:

```text
http://localhost:8080
```

---

# Phase 2 — Database and JPA

## Goal

Connect Spring Boot to PostgreSQL and create the core data model.

## Entities

Start with:

```text
User
MentorProfile
Availability
Booking
Session
ChatMessage
```

## Tasks

1. Configure PostgreSQL.
2. Configure datasource.
3. Create entities.
4. Add primary keys.
5. Define relationships.
6. Create repositories.
7. Test CRUD operations.

Example:

```text
User
 ↓
MentorProfile
 ↓
Availability
 ↓
Booking
 ↓
Session
```

## Expected Result

Application can persist and retrieve entities from PostgreSQL.

---

# Phase 3 — Authentication and Authorization

## Goal

Secure the application using Spring Security and JWT.

## Tasks

1. Create registration endpoint.
2. Hash passwords.
3. Create login endpoint.
4. Authenticate credentials.
5. Generate JWT.
6. Implement JWT authentication filter.
7. Validate JWT.
8. Configure protected endpoints.
9. Implement role-based authorization.

Example:

```text
POST /api/auth/register

POST /api/auth/login
```

Roles:

```text
CANDIDATE
MENTOR
```

## Expected Result

Unauthenticated users cannot access protected APIs.

---

# Phase 4 — Mentor Management

## Goal

Implement mentor profiles and mentor discovery.

## APIs

Example:

```text
GET    /api/mentors
GET    /api/mentors/{id}
POST   /api/mentors/profile
PUT    /api/mentors/profile
```

## Features

- Mentor profile creation
- Profile update
- Mentor retrieval
- Industry filtering
- Expertise filtering
- Pagination

Example:

```text
GET /api/mentors?industry=FinTech&expertise=Java&page=0&size=10
```

## Expected Result

Candidates can discover mentors using filters.

---

# Phase 5 — Availability Management

## Goal

Allow mentors to define available time slots.

## APIs

```text
POST   /api/availability
GET    /api/mentors/{id}/availability
PUT    /api/availability/{id}
DELETE /api/availability/{id}
```

## Validation

The system should verify:

- Start time < end time
- Valid date/time
- No invalid overlaps
- Mentor owns the availability
- Slot is not already booked

## Expected Result

Mentors can manage their available slots.

---

# Phase 6 — Booking System

## Goal

Allow candidates to book available slots.

## API

```text
POST /api/bookings
GET /api/bookings
GET /api/bookings/{id}
DELETE /api/bookings/{id}
```

## Booking Flow

```text
Candidate
   ↓
Request booking
   ↓
Authenticate
   ↓
Validate mentor
   ↓
Validate availability
   ↓
Create booking
   ↓
Create session
```

## Expected Result

A candidate can successfully book an available slot.

---

# Phase 7 — Concurrency and Double-Booking Protection

## Goal

Guarantee that a slot cannot be booked twice.

## Problem

Two candidates can send requests concurrently.

```text
A ──► Check slot ──► Available
B ──► Check slot ──► Available

A ──► Book
B ──► Book
```

This causes double booking.

## Solution

Use:

```text
@Transactional
+
Pessimistic Lock
+
Database constraints
```

Conceptually:

```text
Request A
   ↓
Lock row
   ↓
Check
   ↓
Book
   ↓
Commit
   ↓
Unlock

Request B
   ↓
Wait
   ↓
Check
   ↓
Reject
```

## Testing

Create concurrent booking requests against the same slot.

Expected result:

```text
Request A → SUCCESS

Request B → 409 CONFLICT
```

Only one booking should exist.

---

# Phase 8 — Redis

## Goal

Introduce caching to reduce unnecessary database reads.

## Initial candidates for caching

```text
Mentor profile
Mentor search results
Availability read queries
```

## Flow

```text
Request
   ↓
Redis
   │
   ├── Hit → Return
   │
   └── Miss
        ↓
    PostgreSQL
        ↓
      Redis
        ↓
     Return
```

## Cache Invalidation

Invalidate relevant keys after:

- Profile update
- Availability update
- Availability deletion
- Booking changes

## Important Rule

Redis must not become the final source of truth for booking transactions.

PostgreSQL remains authoritative.

---

# Phase 9 — RabbitMQ

## Goal

Move notification processing outside the main request path.

## Events

Initially implement:

```text
BOOKING_CREATED
BOOKING_CANCELLED
SESSION_REMINDER
```

## Flow

```text
BookingService
     ↓
Publish Event
     ↓
RabbitMQ Exchange
     ↓
Notification Queue
     ↓
Consumer
     ↓
Notification Handler
```

## Expected Result

Booking succeeds without waiting for notification processing.

---

# Phase 10 — Spring Scheduler

## Goal

Automate periodic background operations.

## Initial scheduler

A session reminder scheduler.

Example:

```text
Every minute
     ↓
Find sessions starting soon
     ↓
Create reminder event
     ↓
RabbitMQ
     ↓
Notification Consumer
```

Other possible scheduled tasks:

```text
Expire old availability
Update session states
Process missed sessions
```

## Important Consideration

If multiple backend instances are eventually deployed, scheduled jobs must be designed carefully to avoid duplicate execution.

For a larger deployment, a distributed scheduling/locking mechanism can be introduced.

---

# Phase 11 — WebSocket + STOMP Chat

## Goal

Provide real-time chat during mentorship sessions.

## Connection

Client connects to:

```text
/ws
```

Client subscribes to:

```text
/topic/session/{sessionId}
```

Client sends to:

```text
/app/chat/{sessionId}
```

## Flow

```text
Candidate
    ↓
SEND
    ↓
/app/chat/123
    ↓
WebSocket Controller
    ↓
Validate Session
    ↓
Broadcast
    ↓
/topic/session/123
    ↓
Mentor + Candidate
```

## Security

Verify:

- User is authenticated.
- User belongs to the session.
- Session is active.
- Message content is valid.

---

# Phase 12 — Testing

## Unit Testing

Use:

```text
JUnit
Mockito
```

Test:

- Services
- Booking rules
- Authentication logic
- Validation
- Exception handling

## Integration Testing

Test:

```text
Spring Boot
+
PostgreSQL
```

Test complete API flows.

## API Testing

Use Postman.

Test:

```text
Registration
Login
Mentor search
Availability
Booking
Cancellation
```

## Concurrency Testing

Most important test:

```text
Multiple users
      ↓
Same slot
      ↓
Concurrent booking
```

Expected:

```text
Exactly one successful booking
```

---

# Phase 13 — Error Handling

Implement centralized exception handling using:

```text
@RestControllerAdvice
```

Examples:

```text
UserNotFoundException
MentorNotFoundException
SlotUnavailableException
BookingConflictException
UnauthorizedAccessException
InvalidRequestException
```

Map them to appropriate HTTP responses.

---

# Phase 14 — API Documentation

Document:

- API endpoints
- Request body
- Response body
- Authentication
- Error responses
- Example requests

A tool such as OpenAPI/Swagger can be introduced.

---

# Phase 15 — Git Workflow

Use Git throughout development rather than uploading everything at the end.

Recommended workflow:

```text
Create feature
    ↓
Implement
    ↓
Test
    ↓
git add
    ↓
git commit
    ↓
git push
```

Suggested branches:

```text
main
develop
feature/auth
feature/mentor
feature/booking
feature/redis
feature/rabbitmq
feature/websocket
```

For a smaller project, working directly with `main` is also acceptable, but feature branches provide better interview discussion.

---

# Phase 16 — GitHub README

README should contain:

```text
Project Overview
Features
Architecture
Technology Stack
Setup Instructions
Environment Variables
Database Setup
Redis Setup
RabbitMQ Setup
API Documentation
Testing
Screenshots
Future Improvements
```

---

# Phase 17 — Final Integration

At the end, verify:

```text
Registration
    ↓
Login
    ↓
JWT
    ↓
Mentor Discovery
    ↓
Availability
    ↓
Booking
    ↓
Concurrency Protection
    ↓
RabbitMQ Notification
    ↓
Scheduler
    ↓
Session
    ↓
WebSocket Chat
```

The final project should be runnable locally with clear setup instructions.