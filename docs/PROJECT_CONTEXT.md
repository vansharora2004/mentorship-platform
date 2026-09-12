# Mentorship Platform — Project Context

This document captures the complete project specification distilled from `docs/Project Statement.md`, `docs/Architecture.md`, `docs/Implementation.md`, `docs/Edge Cases.md`, and `docs/Evaluation.md`.

It is a working context file for implementation. It does not replace the five source specification documents.

---

## Project overview

The Mentorship Platform is a **backend-focused Spring Boot application** that connects candidates with industry mentors for one-to-one mentorship sessions.

Candidates register, discover mentors by industry and expertise, view profiles and available time slots, and book sessions. Mentors create and manage professional profiles, define availability, and conduct sessions. During an active session, both sides communicate in real time through WebSocket-based chat (text, URLs, learning resources).

The core user journey:

```text
Register
   ↓
Login
   ↓
Discover Mentor
   ↓
View Profile
   ↓
View Availability
   ↓
Book Slot
   ↓
Receive Confirmation
   ↓
Join Session
   ↓
Real-Time Chat
   ↓
Complete Session
```

Problem this solves: finding suitable mentors, coordinating schedules, preventing double bookings, sending timely notifications, and enabling in-session communication.

The final system should be runnable locally and expose documented APIs testable with Postman or a similar client.

---

## Roles and capabilities

The system has two primary roles: `CANDIDATE` and `MENTOR`.

| Candidate | Mentor |
|---|---|
| Register and log in | Register and log in |
| View mentor profiles | Create and update a professional profile |
| Search and filter mentors (industry, expertise, availability, optional keyword) | Specify industry and expertise |
| View mentor availability | Define available time slots |
| Book mentorship sessions | View upcoming bookings |
| View upcoming and previous bookings | Accept/manage sessions where applicable |
| Cancel eligible bookings | Join mentorship sessions |
| Join mentorship sessions | Participate in real-time chat |
| Participate in real-time chat | Share links and resources |
| Share links and resources | |

Protected APIs require a valid JWT:

```text
Authorization: Bearer <JWT_TOKEN>
```

---

## Data model

**PostgreSQL is the source of truth** for transactional data.

### Conceptual relationships

```text
User
  │
  ├───────────────┐
  │               │
  ▼               ▼
Candidate       Mentor
                  │
                  ▼
             Availability
                  │
                  ▼
               Booking
                  │
                  ▼
                Session
```

### User

```text
User
----------------
id
name
email
passwordHash
role
createdAt
updatedAt
```

Roles: `CANDIDATE`, `MENTOR`.

### MentorProfile

```text
MentorProfile
----------------
id
userId
industry
expertise
experience
bio
createdAt
updatedAt
```

Relationship: `User 1 ───── 1 MentorProfile`.

### Availability

```text
Availability
----------------
id
mentorId
startTime
endTime
status
createdAt
```

Potential status: `AVAILABLE`, `BOOKED`, `BLOCKED`.

The final model may use a separate booking relation instead of storing a simple `BOOKED` status.

### Booking

```text
Booking
----------------
id
mentorId
candidateId
availabilityId
startTime
endTime
status
createdAt
updatedAt
```

Possible booking states: `PENDING`, `CONFIRMED`, `CANCELLED`, `COMPLETED`. The exact state model may be refined during implementation.

The booking is the transactional core of the application.

### Session

```text
Session
----------------
id
bookingId
startTime
endTime
status
createdAt
```

Possible states: `SCHEDULED`, `ACTIVE`, `COMPLETED`, `CANCELLED`.

A session represents the actual mentorship meeting. Booking creation and session creation belong in the **same transaction**.

### ChatMessage

```text
ChatMessage
----------------
id
sessionId
senderId
message
timestamp
```

Real-time delivery is handled by WebSocket. Persistent chat history can optionally be stored in PostgreSQL.

### Time

Store timestamps in **UTC**. Convert to the user's local timezone for display.

---

## Architecture

The backend follows a layered Spring Boot architecture:

```text
Controller
     ↓
Service
     ↓
Repository
     ↓
Database
```

High-level system:

```text
                        ┌──────────────────────┐
                        │      Client          │
                        │ Web / Mobile /        │
                        │ Postman               │
                        └──────────┬───────────┘
                                   │
                         HTTPS / WebSocket
                                   │
                                   ▼
                        ┌──────────────────────┐
                        │     Spring Boot      │
                        │      Backend         │
                        └──────────┬───────────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          │                        │                        │
          ▼                        ▼                        ▼
   REST Controllers          WebSocket Layer          Scheduler
          │                        │                        │
          ▼                        ▼                        ▼
       Services                STOMP Broker            Scheduled Jobs
          │                        │
          └──────────────┬─────────┘
                         │
             ┌───────────┼────────────┐
             │           │            │
             ▼           ▼            ▼
       PostgreSQL      Redis       RabbitMQ
       Database       Cache       Message Broker
                                      │
                                      ▼
                               Notification Worker
```

### Layers

- **Controller** — receive HTTP requests, validate DTOs, extract authenticated user, call services, return HTTP responses. No core business logic.
- **Service** — business rules (`MentorService`, `BookingService`, `AuthService`, `AvailabilityService`, `NotificationService`, `SessionService`).
- **Repository** — Spring Data JPA persistence (`UserRepository`, `MentorRepository`, `AvailabilityRepository`, `BookingRepository`, `SessionRepository`).

Example controllers: `MentorController`, `BookingController`, `AuthController`, `AvailabilityController`, `SessionController`.

### REST vs WebSocket

REST (request → response) is used for login, mentor search, profile management, availability, and booking.

WebSocket (persistent connection) is used for live chat, real-time events, and session communication.

### Reliability principles

1. PostgreSQL is the source of truth for transactional data.
2. Redis should not be trusted for final booking decisions.
3. Booking operations must be transactional.
4. Concurrent booking must use appropriate locking.
5. Notifications should be asynchronous.
6. Failed messages should be recoverable.
7. APIs should validate input.
8. Authentication should be enforced before protected operations.
9. WebSocket connections should be authorized.
10. Database operations should use appropriate indexes and pagination.

### Local deployment

Spring Boot runs locally and connects to PostgreSQL, Redis, and RabbitMQ. Docker Compose can later run infrastructure consistently.

The backend should remain **stateless** (JWT) so additional instances can be added later behind a load balancer, sharing PostgreSQL, Redis, and RabbitMQ.

### Expected package structure

```text
controller/
service/
repository/
entity/
dto/
security/
config/
exception/
scheduler/
messaging/
websocket/
```

---

## Technology stack

| Technology | Purpose |
|---|---|
| Java | Backend programming language |
| Spring Boot | Backend framework |
| Spring MVC | REST APIs |
| Spring Security | Authentication and authorization |
| JWT | Stateless authentication |
| JPA | Persistence API |
| Hibernate | ORM implementation |
| PostgreSQL | Primary relational database |
| Redis | Caching |
| RabbitMQ | Asynchronous messaging |
| Spring Scheduler | Scheduled background tasks |
| Spring WebSocket | Real-time communication |
| STOMP | Messaging protocol over WebSocket |
| Maven | Dependency and build management |
| JUnit | Testing |
| Mockito | Unit-test mocking |
| Git | Version control |
| GitHub | Source-code hosting |

Initial Maven dependencies (Phase 1): Spring Web, Spring Data JPA, PostgreSQL Driver, Spring Security, Validation, Lombok. Additional dependencies are added when their phases begin.

---

## Booking concurrency / double-booking strategy

One of the most important requirements: two candidates must not book the same mentor slot.

Without locking:

```text
A → check → available
B → check → available
A → book
B → book
```

With pessimistic locking:

```text
A → acquire row lock
A → check
A → book
A → commit
A → release lock

B → waits
B → acquire lock
B → check
B → unavailable
B → reject
```

The booking operation must use:

- Database transactions (`@Transactional`)
- Pessimistic row locking through JPA
- Appropriate database constraints
- Availability validation

The second conflicting request receives **HTTP 409 Conflict**.

End-to-end booking flow:

```text
POST /api/bookings
   ↓
JWT Authentication
   ↓
BookingController
   ↓
BookingService
   ↓
Begin Transaction
   ↓
Lock Availability Row
   ↓
Validate Slot
   ├──── No ────► 409 Conflict
   ↓
Create Booking
   ↓
Create Session
   ↓
Commit Transaction
   ↓
Publish Booking Event
   ↓
RabbitMQ → Notification Consumer → Confirmation
```

Booking + session creation are atomic. If session creation fails, the booking must roll back. There must never be a persisted booking without its intended session when both are in the same transaction.

PostgreSQL remains authoritative. Redis must not decide booking correctness.

---

## Redis responsibilities

Redis sits beside PostgreSQL as a **caching layer for frequently accessed reads**.

Potential cached data:

```text
mentor:{id}
mentor:search:{filters}
availability:{mentorId}:{date}
```

Read flow: check Redis → HIT return / MISS PostgreSQL then populate Redis.

Invalidate relevant keys after:

- Profile update
- Availability update
- Availability deletion
- Booking changes

**Important rule:** Redis must not become the source of truth for booking transactions. If Redis is unavailable, operations that can safely fall back to PostgreSQL should continue. Stale availability in cache must never cause an incorrect booking.

---

## RabbitMQ responsibilities

RabbitMQ handles **asynchronous notifications** so the booking API stays responsive.

Flow:

```text
BookingService
      │
      │ Publish event
      ▼
   Exchange
      │
      ▼
Notification Queue
      │
      ▼
Notification Consumer
      │
      ▼
Send notification
```

Initial events:

```text
BOOKING_CREATED
BOOKING_CANCELLED
SESSION_REMINDER
```

Example event payload:

```json
{
  "eventType": "BOOKING_CONFIRMED",
  "bookingId": 1001,
  "candidateId": 10,
  "mentorId": 20
}
```

Booking success must not wait on notification delivery. If RabbitMQ is unavailable, the booking transaction should not be rolled back solely because notification failed (eventual notification). Failed messages should be retried or routed to a dead-letter queue. Consumers should be **idempotent**.

---

## Scheduler responsibilities

Spring Scheduler performs periodic background tasks.

Possible jobs:

```text
Session Reminder Job
Expired Booking Job
Session Status Job
Availability Cleanup Job
```

Initial scheduler: session reminders.

```text
Every 1 minute
      ↓
Check upcoming sessions
      ↓
Find sessions requiring reminders
      ↓
Publish notification event
      ↓
RabbitMQ
```

Other possible tasks: expire old availability, update session states, process missed sessions.

If multiple backend instances are deployed, scheduled jobs must avoid duplicate execution (distributed scheduling/locking later if needed). Jobs should continue correctly after application restart. Missed executions during downtime should be considered for recovery.

---

## WebSocket / STOMP design

REST is used for normal request/response. WebSocket is used for real-time session chat.

Connection:

```text
/ws
```

Client sends to:

```text
/app/chat/{sessionId}
```

Client subscribes to:

```text
/topic/session/{sessionId}
```

(Architecture also documents `/topic/sessions/{sessionId}/chat` as a destination example. Implementation should pick one consistent destination and use it everywhere.)

Message flow:

```text
Candidate
   │
   │ SEND
   ▼
/app/chat/{sessionId}
   │
   ▼
Spring WebSocket Controller
   │
   ▼
Validate session
   │
   ▼
Broadcast
   │
   ▼
/topic/session/{sessionId}
   │
   ├──────────► Candidate
   │
   └──────────► Mentor
```

Users can share text messages, URLs, learning resources, and other supported textual content.

Security: user must be authenticated, belong to the session, session should be active (or follow defined business rules), and message content must be valid. A user must not subscribe to another private session by guessing `sessionId`. Disconnections must be handled gracefully. Empty, huge, or malformed messages are rejected.

---

## Implementation phases

Implement incrementally. Each phase should produce a working, testable result before the next.

Recommended order from the implementation plan:

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

The same document also specifies additional phases:

- **Phase 13 (error handling)** — `@RestControllerAdvice` with mapped exceptions (`UserNotFoundException`, `MentorNotFoundException`, `SlotUnavailableException`, `BookingConflictException`, `UnauthorizedAccessException`, `InvalidRequestException`).
- **Phase 14 (API documentation)** — OpenAPI/Swagger: endpoints, request/response, auth, errors, examples.
- **Phase 15 (Git workflow)** — feature commits throughout; suggested branches `main`, `develop`, `feature/auth`, `feature/mentor`, `feature/booking`, `feature/redis`, `feature/rabbitmq`, `feature/websocket`. Working on `main` is acceptable for a smaller project.
- **Phase 16 (README)** — overview, features, architecture, stack, setup, env vars, DB/Redis/RabbitMQ setup, APIs, testing, screenshots, future improvements.
- **Phase 17 (final integration)** — full workflow verification.

### Phase 1 — Project Setup

Create a Spring Boot app that starts at `http://localhost:8080`. Java, Maven, Git/GitHub, package structure, initial dependencies listed above.

### Phase 2 — Database and JPA

Connect PostgreSQL. Entities: `User`, `MentorProfile`, `Availability`, `Booking`, `Session`, `ChatMessage`. Repositories and CRUD persistence.

### Phase 3 — Authentication

```text
POST /api/auth/register
POST /api/auth/login
```

Hash passwords, JWT generation and filter, protected endpoints, role-based authorization. Unauthenticated users cannot access protected APIs.

### Phase 4 — Mentor Management

```text
GET    /api/mentors
GET    /api/mentors/{id}
POST   /api/mentors/profile
PUT    /api/mentors/profile
```

Example search:

```text
GET /api/mentors?industry=FinTech&expertise=Java&page=0&size=10
```

### Phase 5 — Availability

```text
POST   /api/availability
GET    /api/mentors/{id}/availability
PUT    /api/availability/{id}
DELETE /api/availability/{id}
```

Validation: start < end, valid date/time, no invalid overlaps, mentor owns the slot, slot not already booked.

### Phase 6 — Booking

```text
POST   /api/bookings
GET    /api/bookings
GET    /api/bookings/{id}
DELETE /api/bookings/{id}
```

Authenticate → validate mentor → validate availability → create booking → create session.

### Phase 7 — Concurrency

`@Transactional` + pessimistic lock + DB constraints. Concurrent tests: one SUCCESS, one 409 CONFLICT.

### Phases 8–11

Redis caching and invalidation; RabbitMQ events; scheduler reminders; WebSocket/STOMP chat with session authorization.

### Phases 12+

JUnit/Mockito unit tests, Spring Boot + PostgreSQL integration tests, Postman API tests, concurrency tests, WebSocket tests, centralized errors, OpenAPI, incremental Git, README, end-to-end verification.

---

## Edge cases

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

### Authentication / authorization

- Wrong email/password → **401**
- Duplicate email on register → **409**
- Missing, expired, or invalid/tampered JWT → **401**
- Candidate updating mentor profile → **403**
- Mentor modifying another mentor's availability → **403**
- Users may only access resources they own or are authorized for

### Mentor profile

- Missing mentor → **404**
- Invalid profile data (empty name, invalid industry/expertise/experience) → **400**

### Availability

- Start after end, or start equals end → reject
- Overlapping slots → reject or merge per defined business rule
- Past availability generally not allowed
- Candidates cannot modify availability

### Booking

- Slot already booked → **409**
- Mentor or candidate missing → **404**
- Booking in the past → reject
- Booking outside availability → reject
- Boundary-touching slots (e.g. availability 10:00–11:00 vs request 11:00–12:00) need an explicit business rule
- Concurrent same-slot requests: exactly one success, others conflict
- Transaction failure after booking but before session → full rollback
- Already cancelled: reject or idempotent success per API contract
- Completed booking not cancellable
- Another user's booking → authorization reject
- Cancellation after session start → reject if rules disallow it

### Redis / RabbitMQ / scheduler

- Redis down → fall back to PostgreSQL where safe
- Stale cache must not drive bookings
- RabbitMQ down → booking remains valid; notification recoverable
- Duplicate messages → idempotent consumers
- Scheduler after restart, missed ticks, and duplicate jobs across instances

### WebSocket

- Disconnect handled
- Invalid session or user not in session → reject
- Completed session may reject new messages
- Empty / huge / malformed messages → reject
- Cannot join private chat by changing `sessionId`

### Other

- Unique email at database level
- Foreign key violations rejected
- Database unavailable → appropriate server error + log
- Connection pool limits configured
- Validate email, password, name length, expertise, industry, date/time, pagination, IDs, message size
- Pagination: reject `page = -1`, `size = 0`, huge `size`; enforce max page size
- Store UTC; DST-aware Java date/time APIs
- Concurrent profile updates: last-write-wins or `@Version` optimistic locking
- Idempotent booking/cancellation on client retry to avoid duplicate bookings
- Notification failure independent of booking success
- Crash before commit → rollback; after commit → booking remains

Security: parameterized JPA (no raw SQL from user input), JWT validation, ownership checks, brute-force login protection, request size limits, no sensitive leakage.

---

## Evaluation criteria

Assessment covers correctness, architecture, security, concurrency, performance, reliability, testing, code quality, and Git/GitHub practices.

### Scoring model

| Category | Weight |
|---|---:|
| Functional correctness | 20% |
| Architecture | 15% |
| Database/JPA | 10% |
| Authentication/Security | 10% |
| Booking concurrency | 15% |
| Redis | 5% |
| RabbitMQ | 5% |
| WebSocket | 5% |
| Testing | 10% |
| Code quality/GitHub | 5% |
| **Total** | **100%** |

### Functional checks

- Candidate and mentor registration, login, JWT generation
- Protected APIs reject unauthenticated requests
- Role-based authorization
- Mentor profile create/update/retrieve; industry and expertise filters; pagination
- Availability CRUD; candidates can view; invalid ranges and overlaps handled; past slots handled
- Candidate can book available slots; cannot book unavailable or past slots; persistence; session created; cancellation per rules
- Concurrent requests for the same slot: **exactly one successful booking**
- Booking + session atomicity
- Redis populate/hit/invalidate; Redis failure fallback; bookings not based on stale cache
- RabbitMQ publish/consume async; retry/recovery; duplicate-safe
- Scheduler interval, upcoming sessions, reminder events through RabbitMQ
- WebSocket connect, auth, authorized join, send/receive, unauthorized blocked, disconnect handled

### Performance

Measure average response time, query count, cache hit/miss, concurrent handling on `GET /mentors`, `GET /mentors/{id}`, `GET /availability`, `POST /bookings`.

Potential indexes (based on actual query patterns): `users.email`, `mentor_profile.industry`, `mentor_profile.expertise`, `availability.mentor_id`, `booking.mentor_id`, `booking.candidate_id`, `booking.start_time`.

### Security tests

Password hashing, JWT validation, RBAC, input validation, resource ownership, WebSocket authorization. Cases: invalid/expired/missing JWT, candidate on mentor-only endpoints, another user's booking, another session's chat.

### Testing minimum

Unit: `AuthService`, `MentorService`, `AvailabilityService`, `BookingService`.  
Integration: auth, mentor APIs, booking APIs, database.  
Concurrency: multiple requests, same slot.  
WebSocket: connect, subscribe, send, receive, disconnect.

### Git / GitHub

Incremental commits (e.g. `feat: add JWT authentication`, not `"final project"`). Repository should contain `README.md`, `docs/`, `src/`, `pom.xml`, `.gitignore`, `docker-compose.yml`. Do not commit passwords, JWT secrets, DB credentials, API keys, private certificates, or `.env` files with secrets. Use environment variables.

The project should be explainable in an interview: why each technology, how double booking is prevented, how JWT works, why pessimistic locking, Redis/pagination/indexes, async notification failure modes, WebSocket + STOMP + chat security.

---

## MVP and complete-project scope

### Minimum viable project

```text
✓ Registration
✓ Login
✓ JWT
✓ Mentor profiles
✓ Mentor search
✓ Availability
✓ Booking
✓ Double-booking protection
✓ PostgreSQL
```

### Complete version

MVP plus:

```text
✓ Redis caching
✓ RabbitMQ
✓ Async notifications
✓ Scheduler
✓ WebSocket
✓ STOMP
✓ Session management
✓ Automated tests
✓ API documentation
✓ Docker Compose
✓ GitHub README
```

The project is complete when the end-to-end workflow works: register → login → JWT → find mentor → view profile → availability → book (pessimistic lock) → RabbitMQ notification → join session → WebSocket/STOMP live chat → complete session.

The implementation should be demonstrable locally, reproducible from GitHub, and understandable enough that every major architectural decision can be explained in a technical interview.

---

## API contract

Application base (local): `http://localhost:8080`

Authentication header for protected APIs:

```text
Authorization: Bearer <JWT>
```

### Auth

```text
POST /api/auth/register
POST /api/auth/login
```

### Mentors

```text
GET    /api/mentors
GET    /api/mentors/{id}
POST   /api/mentors/profile
PUT    /api/mentors/profile

GET /api/mentors?industry=FinTech&expertise=Java&page=0&size=10
```

### Availability

```text
POST   /api/availability
GET    /api/mentors/{id}/availability
PUT    /api/availability/{id}
DELETE /api/availability/{id}
```

### Bookings

```text
POST   /api/bookings
GET    /api/bookings
GET    /api/bookings/{id}
DELETE /api/bookings/{id}
```

Example booking:

```text
POST /api/bookings

Authorization:
Bearer <JWT>

Request:
{
  "availabilityId": 10
}

Success:
201 Created

Conflict:
409 Conflict
```

### WebSocket

```text
Connect:     /ws
Send:        /app/chat/{sessionId}
Subscribe:   /topic/session/{sessionId}
```

### Typical HTTP status mapping

| Situation | Status |
|---|---|
| Invalid credentials / missing / expired / invalid JWT | 401 Unauthorized |
| Authenticated but not allowed (wrong role or ownership) | 403 Forbidden |
| Mentor, candidate, or resource missing | 404 Not Found |
| Duplicate email; slot already booked; concurrent booking loser | 409 Conflict |
| Invalid input / invalid time range / invalid profile data | 400 Bad Request |
| Booking created | 201 Created |

---

## Current workspace note

At the time this context file was written, the repository contained documentation and git metadata only. Java/Spring Boot implementation had not been started.
