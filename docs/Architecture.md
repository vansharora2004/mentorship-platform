# Mentorship Platform — System Architecture

## 1. Architecture Overview

The application follows a layered backend architecture built using Spring Boot.

The high-level architecture is:

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

---

# 2. Architectural Style

The backend follows a layered architecture:

```text
Controller
     ↓
Service
     ↓
Repository
     ↓
Database
```

Additional infrastructure components are integrated at appropriate layers:

```text
Redis       → Caching
RabbitMQ    → Asynchronous messaging
Scheduler   → Background processing
WebSocket   → Real-time communication
JWT         → Authentication
```

---

# 3. Application Layers

## 3.1 Controller Layer

Responsibilities:

- Receive HTTP requests
- Validate request DTOs
- Extract authenticated user information
- Call service methods
- Return HTTP responses

Controllers should not contain core business logic.

Example:

```text
MentorController
BookingController
AuthController
AvailabilityController
SessionController
```

---

# 4. Service Layer

The service layer contains business rules.

Examples:

```text
MentorService
BookingService
AuthService
AvailabilityService
NotificationService
SessionService
```

The booking service is responsible for:

- Validating the candidate
- Validating the mentor
- Checking availability
- Acquiring the required database lock
- Creating the booking
- Updating slot state
- Publishing booking events

---

# 5. Repository Layer

Repositories communicate with PostgreSQL through Spring Data JPA.

Example:

```text
UserRepository
MentorRepository
AvailabilityRepository
BookingRepository
SessionRepository
```

Repositories should primarily handle persistence operations rather than business decisions.

---

# 6. Database Architecture

PostgreSQL is the primary source of truth.

Conceptual entities:

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

---

# 7. User Model

A common model is:

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

Role:

```text
CANDIDATE
MENTOR
```

Mentor-specific information can be maintained in a separate profile entity.

---

# 8. Mentor Profile

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

Relationship:

```text
User 1 ───── 1 MentorProfile
```

---

# 9. Availability

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

Potential status:

```text
AVAILABLE
BOOKED
BLOCKED
```

The final data model may choose a separate booking relation instead of storing a simple BOOKED status.

---

# 10. Booking

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

The booking is the transactional core of the application.

---

# 11. Session

A session represents the actual mentorship meeting.

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

Possible states:

```text
SCHEDULED
ACTIVE
COMPLETED
CANCELLED
```

---

# 12. Chat Message

A chat message may contain:

```text
ChatMessage
----------------
id
sessionId
senderId
message
timestamp
```

Real-time delivery is handled by WebSocket.

Persistent chat history can optionally be stored in PostgreSQL.

---

# 13. Authentication Architecture

Authentication uses Spring Security and JWT.

Flow:

```text
Client
  │
  │ POST /auth/login
  ▼
AuthController
  │
  ▼
AuthService
  │
  ▼
AuthenticationManager
  │
  ▼
Validate credentials
  │
  ▼
Generate JWT
  │
  ▼
Return JWT
```

For protected APIs:

```text
Client
  │
  │ Authorization: Bearer JWT
  ▼
JWT Filter
  │
  ▼
Validate token
  │
  ▼
Extract user identity + role
  │
  ▼
SecurityContext
  │
  ▼
Controller
```

---

# 14. Booking Architecture

The booking operation is transactional.

```text
POST /bookings
       │
       ▼
BookingController
       │
       ▼
BookingService
       │
       ▼
@Transactional
       │
       ▼
Find Availability
       │
       ▼
Pessimistic Write Lock
       │
       ▼
Check availability
       │
   ┌───┴────┐
   │        │
Available  Unavailable
   │        │
   ▼        ▼
Create     Reject
Booking
   │
   ▼
Commit
```

---

# 15. Double Booking Protection

The critical concurrency problem is:

```text
Candidate A ──┐
              ├── Same slot
Candidate B ──┘
```

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

This ensures only one booking succeeds.

---

# 16. Redis Architecture

Redis sits beside PostgreSQL as a caching layer.

```text
                ┌──────────────┐
                │   Backend    │
                └──────┬───────┘
                       │
                Check Redis
                 /          \
              HIT            MISS
               │              │
               ▼              ▼
            Return       PostgreSQL
                              │
                              ▼
                            Redis
```

Potential cached data:

```text
mentor:{id}
mentor:search:{filters}
availability:{mentorId}:{date}
```

Transactional booking decisions should ultimately rely on PostgreSQL and appropriate locking rather than trusting stale cache data.

---

# 17. Cache Invalidation

Whenever cached mentor information changes:

```text
Update mentor
      │
      ▼
PostgreSQL
      │
      ▼
Invalidate Redis key
```

For example:

```text
mentor:123
```

should be removed or updated after profile changes.

Availability-related caches must also be invalidated after bookings or availability modifications.

---

# 18. RabbitMQ Architecture

RabbitMQ handles asynchronous events.

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

Example event:

```json
{
  "eventType": "BOOKING_CONFIRMED",
  "bookingId": 1001,
  "candidateId": 10,
  "mentorId": 20
}
```

---

# 19. Why Asynchronous Notifications?

Without messaging:

```text
Booking API
   ↓
Save booking
   ↓
Send notification
   ↓
Wait
   ↓
Return response
```

With RabbitMQ:

```text
Booking API
   ↓
Save booking
   ↓
Publish event
   ↓
Return response

RabbitMQ
   ↓
Notification Consumer
   ↓
Send notification
```

This reduces coupling and keeps the booking API responsive.

---

# 20. Scheduler Architecture

Spring Scheduler performs periodic tasks.

Example:

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

Possible jobs:

```text
Session Reminder Job
Expired Booking Job
Session Status Job
Availability Cleanup Job
```

---

# 21. WebSocket Architecture

REST is used for normal request/response operations.

WebSocket is used for real-time communication.

```text
Candidate
     │
     │ WebSocket
     ▼
Spring WebSocket
     │
     │ STOMP
     ▼
Session Destination
     │
     ├──────────► Mentor
     │
     └──────────► Candidate
```

Example destination:

```text
/topic/sessions/{sessionId}/chat
```

---

# 22. Chat Message Flow

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

---

# 23. REST vs WebSocket

REST:

```text
Request → Response
```

Used for:

- Login
- Mentor search
- Profile management
- Availability
- Booking

WebSocket:

```text
Persistent connection
```

Used for:

- Live chat
- Real-time events
- Session communication

---

# 24. Complete End-to-End Booking Flow

```text
Candidate
   │
   ▼
POST /api/bookings
   │
   ▼
JWT Authentication
   │
   ▼
BookingController
   │
   ▼
BookingService
   │
   ▼
Begin Transaction
   │
   ▼
Lock Availability Row
   │
   ▼
Validate Slot
   │
   ├──── No ────► 409 Conflict
   │
   ▼
Create Booking
   │
   ▼
Create Session
   │
   ▼
Commit Transaction
   │
   ▼
Publish Booking Event
   │
   ▼
RabbitMQ
   │
   ▼
Notification Consumer
   │
   ▼
Send Confirmation
```

---

# 25. Deployment Architecture

For local development:

```text
VS Code / Cursor
       │
       ▼
Spring Boot Application
       │
 ┌─────┼─────────┐
 ▼     ▼         ▼
Postgres Redis RabbitMQ
```

Docker Compose can later be used to run infrastructure consistently:

```text
docker-compose
      │
      ├── PostgreSQL
      ├── Redis
      └── RabbitMQ
```

The Spring Boot application can run locally while connecting to these services.

---

# 26. Scalability

The backend should ideally remain stateless.

Multiple instances can run:

```text
                 Load Balancer
                 /     |      \
                /      |       \
           Spring   Spring   Spring
           Instance Instance Instance
               \       |       /
                \      |      /
             Shared Infrastructure
                │      │      │
                ▼      ▼      ▼
             PostgreSQL Redis RabbitMQ
```

JWT authentication helps avoid server-side HTTP session state.

Redis and RabbitMQ provide shared infrastructure for distributed operations.

---

# 27. Reliability Principles

The architecture should follow these principles:

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