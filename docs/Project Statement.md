# Mentorship Platform

## 1. Project Overview

The Mentorship Platform is a backend-focused application designed to connect candidates with industry mentors for one-to-one mentorship sessions.

Candidates can register on the platform, discover mentors based on their industry and areas of expertise, view mentor profiles, check available time slots, and book mentorship sessions.

Mentors can create and manage their profiles, specify their areas of expertise, define their availability, and conduct mentorship sessions with candidates.

The platform also provides real-time communication during an active mentorship session through WebSocket-based chat. Candidates and mentors can share messages, links, and learning resources during the session.

The system is designed around a RESTful backend using Java and Spring Boot, with PostgreSQL as the primary database. Redis is used for caching and fast access to frequently requested data, RabbitMQ is used for asynchronous processing and notifications, Spring Scheduler is used for scheduled background operations, and JWT-based authentication is used to secure APIs.

---

# 2. Problem Statement

Candidates looking for professional guidance often face difficulties finding suitable mentors based on their specific career interests, technical expertise, industry, and availability.

Even after finding a suitable mentor, manually coordinating schedules can lead to:

- Conflicting schedules
- Double bookings
- Missed sessions
- Delayed notifications
- Difficulty tracking availability
- Poor communication during sessions

A centralized platform is required where candidates can discover suitable mentors, view their availability, book sessions, receive notifications, and communicate with mentors in real time.

The system must also handle concurrent booking requests safely so that the same mentor time slot cannot be booked by multiple candidates.

---

# 3. Objectives

The primary objectives of the platform are:

1. Provide secure registration and login for candidates and mentors.
2. Allow mentors to create and manage professional profiles.
3. Allow candidates to search and filter mentors.
4. Allow mentors to define their available time slots.
5. Allow candidates to view mentor availability.
6. Allow candidates to book available mentorship slots.
7. Prevent double booking during concurrent requests.
8. Provide booking confirmation and notifications.
9. Provide real-time chat during mentorship sessions.
10. Maintain persistent data using PostgreSQL.
11. Improve performance using Redis caching.
12. Process notifications asynchronously using RabbitMQ.
13. Automate periodic background operations using Spring Scheduler.
14. Provide a clean and scalable REST API architecture.

---

# 4. Users and Roles

The system contains two primary roles.

## 4.1 Candidate

A candidate can:

- Register and log in.
- View mentor profiles.
- Search mentors.
- Filter mentors by industry.
- Filter mentors by expertise.
- View mentor availability.
- Book mentorship sessions.
- View upcoming and previous bookings.
- Cancel eligible bookings.
- Join mentorship sessions.
- Participate in real-time chat.
- Share links and resources.

## 4.2 Mentor

A mentor can:

- Register and log in.
- Create and update a professional profile.
- Specify industry and expertise.
- Define available time slots.
- View upcoming bookings.
- Accept/manage mentorship sessions where applicable.
- Join mentorship sessions.
- Participate in real-time chat.
- Share links and resources.

---

# 5. Core Functional Requirements

## 5.1 Authentication

The system must provide:

- Candidate registration
- Mentor registration
- Login
- JWT token generation
- JWT token validation
- Role-based authorization

Protected APIs should require a valid JWT.

Example:

```text
Authorization: Bearer <JWT_TOKEN>
```

---

# 6. Mentor Management

Mentors should be able to maintain:

- Name
- Profile information
- Industry
- Areas of expertise
- Experience
- Professional description
- Availability

Candidates should be able to view this information while discovering mentors.

---

# 7. Mentor Discovery

Candidates should be able to search and filter mentors.

Supported filters include:

- Industry
- Expertise
- Availability
- Optional keyword search

Example:

```text
Find mentors where:

Industry = FinTech
Expertise = Java
Available = 10:00 AM
```

The API should support pagination to avoid returning a very large number of records.

---

# 8. Availability Management

Mentors should be able to define the time periods during which they are available.

Example:

```text
Mentor: John

Monday
10:00 - 11:00
14:00 - 15:00

Tuesday
11:00 - 12:00
```

Availability should be represented using appropriate date/time types.

The system must prevent invalid or overlapping availability configurations.

---

# 9. Booking System

Candidates can select an available mentor slot and create a booking.

A booking contains information such as:

- Candidate
- Mentor
- Start time
- End time
- Booking status
- Creation timestamp

Possible booking states:

```text
PENDING
CONFIRMED
CANCELLED
COMPLETED
```

The exact state model may be refined during implementation.

---

# 10. Double Booking Prevention

One of the most important requirements is preventing two candidates from booking the same mentor slot simultaneously.

Example:

```text
Mentor availability:

10:00 AM - 11:00 AM
```

Two candidates send requests at almost the same time:

```text
Candidate A → Book 10:00
Candidate B → Book 10:00
```

The system must ensure that only one request succeeds.

The booking operation should therefore use:

- Database transactions
- Pessimistic row locking through JPA
- Appropriate database constraints
- Availability validation

The second conflicting request should receive an appropriate error response, such as HTTP 409 Conflict.

---

# 11. Notifications

After important events such as successful booking, cancellation, or session reminders, the system should generate notifications.

Notification processing should be asynchronous.

Instead of making the booking request wait for notification processing:

```text
Booking
   ↓
Save booking
   ↓
Publish event
   ↓
RabbitMQ
   ↓
Notification Consumer
```

This keeps the main API responsive.

---

# 12. Real-Time Session Chat

During an active mentorship session, candidates and mentors should be able to communicate using real-time chat.

The system uses:

- Spring WebSocket
- STOMP

Users connect to the WebSocket server and subscribe to a session-specific destination.

Example:

```text
/session/{sessionId}/chat
```

A message sent by one participant can be delivered to the other participant in real time.

Users can share:

- Text messages
- URLs
- Learning resources
- Other supported textual content

---

# 13. Scheduling

The platform requires background operations such as:

- Checking upcoming sessions
- Generating reminders
- Updating session states
- Processing expired availability
- Triggering scheduled notifications

Spring Scheduler will be used for periodic background tasks.

---

# 14. Caching

Frequently accessed information such as mentor profiles and mentor availability may be cached using Redis.

Example:

```text
GET /mentors/101

First request
    ↓
PostgreSQL
    ↓
Redis Cache

Future requests
    ↓
Redis
```

Cache expiration and invalidation strategies must be implemented so that stale information does not cause incorrect booking decisions.

PostgreSQL remains the source of truth for transactional booking information.

---

# 15. Non-Functional Requirements

## Performance

Frequently accessed read operations should be optimized using:

- Database indexes
- Pagination
- Redis caching
- Efficient queries

## Scalability

The backend should be designed so that additional application instances can be added later.

JWT authentication keeps REST APIs stateless.

Asynchronous processing through RabbitMQ reduces unnecessary coupling.

## Reliability

The system should:

- Preserve booking consistency
- Prevent double booking
- Handle failed asynchronous operations
- Validate all input
- Handle database transaction failures

## Security

The system should:

- Hash passwords
- Use JWT authentication
- Implement role-based authorization
- Validate incoming data
- Avoid exposing sensitive information
- Protect WebSocket connections

---

# 16. Technology Stack

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

---

# 17. Expected Outcome

The final system should provide a functional mentorship workflow:

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

The final project should be runnable locally and should expose documented APIs that can be tested using Postman or a similar API client.