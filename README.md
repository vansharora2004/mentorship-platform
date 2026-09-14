# Mentorship Platform

A backend service connecting candidates with industry mentors for one-to-one mentorship sessions.

Candidates discover mentors by industry and expertise, view their availability, and book sessions.
Mentors publish profiles and time slots. Once a session is booked, both sides can chat in real time.

Built with Spring Boot 4 and Java 21, using PostgreSQL as the source of truth, Redis for caching,
RabbitMQ for asynchronous notifications, Spring Scheduler for background jobs, and WebSocket/STOMP
for live chat.

---

## Contents

- [Features](#features)
- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Setup](#setup)
- [Environment variables](#environment-variables)
- [PostgreSQL setup](#postgresql-setup)
- [Redis setup](#redis-setup)
- [RabbitMQ setup](#rabbitmq-setup)
- [Running the application](#running-the-application)
- [API documentation](#api-documentation)
- [Testing](#testing)
- [Screenshots](#screenshots)
- [Project structure](#project-structure)
- [Future improvements](#future-improvements)

---

## Features

**Accounts and security**
- Registration and login for candidates and mentors
- Stateless JWT authentication; BCrypt password hashing
- Role-based authorization plus per-resource ownership checks

**Mentors and availability**
- Mentor profiles with industry, expertise, experience, and bio
- Search and filter mentors with pagination
- Mentors define time slots; overlapping and past slots are rejected

**Booking**
- Candidates book available slots
- **Double-booking protection** — pessimistic row locking (`SELECT ... FOR UPDATE`) plus a partial
  unique index, so exactly one of two concurrent requests succeeds and the other gets `409`
- Cancellation releases the slot for rebooking
- A session is created in the same transaction as its booking

**Performance**
- Redis caches mentor profiles (10 min) and availability (2 min)
- Precise cache eviction on every write path
- If Redis is unavailable, reads fall back to PostgreSQL and the application keeps working

**Asynchronous notifications**
- Booking and cancellation events are published to RabbitMQ after the response is returned
- **Transactional outbox** — events are committed to the database with the booking, so a broker
  outage cannot lose them; a scheduled job retries until delivery succeeds
- Idempotent consumer; failed messages are retried then dead-lettered

**Scheduled jobs**
- Session reminders 15 minutes before start
- Automatic session state transitions (SCHEDULED → ACTIVE → COMPLETED)
- Outbox retry for undelivered notifications

**Real-time chat**
- WebSocket/STOMP chat during a session
- JWT authentication on the STOMP CONNECT frame
- Subscription authorization per session, so a session id cannot be guessed to read another chat
- Messages persisted; transcript available over REST

---

## Architecture

```
                  ┌─────────────────────────────┐
                  │  Client (Postman / app)     │
                  └──────┬───────────────┬──────┘
                 HTTP+JWT│               │WebSocket+STOMP
                         ▼               ▼
      ┌──────────────────────────────────────────────────┐
      │              Spring Boot application             │
      │                                                  │
      │  Security filter chain    STOMP interceptor      │
      │           │                      │               │
      │           ▼                      ▼               │
      │      Controllers            ChatController       │
      │           │                      │               │
      │           ▼                      ▼               │
      │       Services  ──────────►  ChatService         │
      │           │                      │               │
      │           ▼                      ▼               │
      │      Repositories (JPA)                          │
      └───────────┬──────────────┬───────────┬───────────┘
                  │              │           │
                  ▼              ▼           ▼
          ┌──────────────┐ ┌──────────┐ ┌──────────────┐
          │  PostgreSQL  │ │  Redis   │ │   RabbitMQ   │
          │ SOURCE OF    │ │  cache   │ │ notifications│
          │   TRUTH      │ │   only   │ │              │
          └──────────────┘ └──────────┘ └──────────────┘
```

**The rule that governs the design:** PostgreSQL is the source of truth. Redis is only a cache and
RabbitMQ only carries notifications — neither is ever consulted to decide whether a booking is
allowed.

Deeper explanations live in [`docs/`](docs/):

| Document | Contents |
|---|---|
| [`docs/API.md`](docs/API.md) | Full REST and WebSocket reference |
| [`docs/PHASE_9_11_GUIDE.md`](docs/PHASE_9_11_GUIDE.md) | Beginner-friendly guide to messaging, scheduling, and chat |
| [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) | As-built record with requirement classifications |
| [`docs/Architecture.md`](docs/Architecture.md) | System architecture specification |

---

## Technology stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Language |
| Spring Boot | 4.1.1 | Framework |
| Spring MVC | | REST APIs |
| Spring Security | | Authentication and authorization |
| JJWT | 0.12.6 | JWT creation and validation |
| Spring Data JPA / Hibernate | | Persistence |
| PostgreSQL | | Primary database |
| Spring Data Redis | | Caching |
| Spring AMQP | | RabbitMQ integration |
| Spring Scheduler | | Background jobs |
| Spring WebSocket + STOMP | | Real-time chat |
| Maven | | Build |
| JUnit 5, Mockito, AssertJ, Awaitility | | Testing |
| Docker Compose | | Redis and RabbitMQ for local development |

---

## Setup

### Prerequisites

- Java 21
- Docker Desktop (for Redis and RabbitMQ)
- A local PostgreSQL installation

### Quick start

```bash
git clone https://github.com/vansharora2004/mentorship-platform.git
cd mentorship-platform

# 1. Start Redis and RabbitMQ
docker compose up -d

# 2. Create the database (see PostgreSQL setup below)

# 3. Set the required environment variables (see below)

# 4. Run
./mvnw spring-boot:run
```

The application starts on **http://localhost:8081**. Verify with:

```bash
curl http://localhost:8081/api/health
```

---

## Environment variables

Two variables are **required** — the application will not start without them.

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_PASSWORD` | **Yes** | — | Password for the PostgreSQL `postgres` user |
| `JWT_SECRET` | **Yes** | — | HMAC-SHA signing key, **at least 32 characters** |
| `RABBITMQ_PASSWORD` | No | `guest` | RabbitMQ password |

**Never commit these values.** They are read from the environment, and `.gitignore` excludes `.env`
files and key material.

```bash
# macOS / Linux
export DB_PASSWORD="your-postgres-password"
export JWT_SECRET="a-long-random-string-of-at-least-32-characters"

# Windows PowerShell
$env:DB_PASSWORD = "your-postgres-password"
$env:JWT_SECRET  = "a-long-random-string-of-at-least-32-characters"
```

---

## PostgreSQL setup

PostgreSQL runs natively rather than in Docker. Create the database once:

```sql
CREATE DATABASE mentorship_platform;
```

Default connection (override in `src/main/resources/application.properties` if yours differs):

```
url:      jdbc:postgresql://localhost:5432/mentorship_platform
username: postgres
password: ${DB_PASSWORD}
```

**Schema** is created automatically. Hibernate generates the tables (`ddl-auto=update`), and
`schema.sql` then adds the partial unique index that enforces double-booking protection:

```sql
CREATE UNIQUE INDEX uq_bookings_active_slot
    ON bookings (availability_id)
    WHERE status = 'CONFIRMED';
```

It is partial on purpose — a cancelled booking must not block the slot from being rebooked.

Tables: `users`, `mentor_profiles`, `availability`, `bookings`, `sessions`, `notifications`,
`chat_messages`, `outbox_events`.

---

## Redis setup

Redis runs via Docker Compose:

```bash
docker compose up -d redis
docker compose ps                                  # expect "healthy"
docker exec mentorship-redis redis-cli ping        # expect PONG
```

| Cache | Key | TTL |
|---|---|---|
| Mentor profile | `mentor:{mentorId}` | 10 minutes |
| Availability | `availability:{mentorId}` | 2 minutes |

Redis is a **soft dependency** — if it is down, reads fall back to PostgreSQL and every write path
still works. Cache failures are logged, not propagated.

---

## RabbitMQ setup

```bash
docker compose up -d rabbitmq
docker compose ps                                        # expect "healthy"
docker exec mentorship-rabbitmq rabbitmq-diagnostics -q ping
```

Management UI: **http://localhost:15672** (default `guest` / `guest`)

Topology, declared automatically at startup:

```
mentorship.events (topic) ──notification.*──► notification.queue ──► consumer
                                                    │ (3 failed attempts)
                                                    ▼
                                   mentorship.events.dlx ──► notification.dlq
```

RabbitMQ is also a **soft dependency**. Bookings succeed while it is down, and events are held in
the outbox until the retry job delivers them.

---

## Running the application

```bash
./mvnw spring-boot:run          # run
./mvnw clean package            # build a jar
java -jar target/*.jar          # run the jar
```

---

## API documentation

Full reference: **[`docs/API.md`](docs/API.md)** — every endpoint with method, authentication,
request, response, errors, and examples.

Quick reference:

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/register` · `POST /api/auth/login` · `GET /api/auth/me` |
| Mentors | `GET /api/mentors` · `GET /api/mentors/{id}` · `GET|POST|PUT|DELETE /api/mentors/profile` |
| Availability | `GET|POST /api/availability` · `PUT|DELETE /api/availability/{id}` · `GET /api/mentors/{id}/availability` |
| Bookings | `POST /api/bookings` · `GET /api/bookings` · `GET /api/bookings/{id}` · `DELETE /api/bookings/{id}` |
| Chat history | `GET /api/sessions/{id}/messages` |
| WebSocket | connect `/ws` · send `/app/chat/{id}` · subscribe `/topic/session/{id}` |

### Postman

A ready-to-run collection is in [`docs/postman/`](docs/postman/):

1. Import `mentorship-platform.postman_collection.json` and
   `mentorship-platform.postman_environment.json`.
2. Select the **Mentorship Platform - Local** environment.
3. Run the folders in order — tokens and ids are captured automatically as you go.

Example booking request:

```http
POST /api/bookings
Authorization: Bearer <JWT>
Content-Type: application/json

{ "availabilityId": 55 }
```

```
201 Created   booking confirmed
409 Conflict  slot already taken
```

---

## Testing

Redis and RabbitMQ must be running, since the integration tests use real brokers:

```bash
docker compose up -d
./mvnw clean test
```

```
Tests run: 255, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

What the suite covers:

| Kind | Coverage |
|---|---|
| Unit | Auth, Mentor, Availability, Booking, Chat services; JWT; password hashing |
| Integration | Auth, mentor, availability, booking, caching, messaging, scheduling |
| Concurrency | Parallel threads booking the same slot — exactly one succeeds |
| Failure | Redis unreachable; RabbitMQ unreachable — the application stays correct |
| WebSocket | Real STOMP client: connect, subscribe, send, receive, disconnect |
| End-to-end | The complete journey from registration through to live chat |

The scheduler's timer is disabled during tests and the jobs are invoked directly, so the tests are
deterministic and need no sleeping.

---

## Screenshots

<!--
  PLACEHOLDER - no screenshots have been captured yet.

  To add them:
    1. Create a docs/screenshots/ directory.
    2. Capture the images described below.
    3. Replace this comment with the image links.

  Suggested captures:
    - Postman: successful registration and login returning a JWT
    - Postman: successful booking (201) and a double-booking attempt (409)
    - RabbitMQ management UI (http://localhost:15672) showing notification.queue
    - A WebSocket client exchanging chat messages
    - Terminal output of a full green test run

  Example markup once the files exist:
    ![Booking conflict](docs/screenshots/booking-409.png)
-->

_No screenshots have been added yet._

---

## Project structure

```
src/main/java/com/mentorship/
├── config/          Security, Redis cache, RabbitMQ, WebSocket, scheduling
├── controller/      REST controllers and the STOMP chat controller
├── dto/             Request and response records
├── entity/          JPA entities
├── event/           Notification event payloads
├── exception/       Custom exceptions and the global handler
├── messaging/       RabbitMQ publisher, consumer, outbox
├── repository/      Spring Data JPA repositories
├── scheduler/       Reminder, session status, and outbox retry jobs
├── security/        JWT service, filters, STOMP authentication
└── service/         Business logic

docs/                Specifications, API reference, guides, Postman collection
```

---

## Future improvements

Deliberately out of scope for now, with the reasoning recorded in
[`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md):

- **Distributed scheduler locking** — required before running multiple instances, so scheduled jobs
  do not execute twice. Safe today because the notification consumer is idempotent.
- **A STOMP broker relay** (RabbitMQ or ActiveMQ) in place of the in-memory broker, needed before
  scaling chat across instances.
- **Availability cleanup job** to expire past slots — would require a new availability status that
  changes the existing API contract.
- **Outbox archiving** — delivered events are currently retained indefinitely as an audit trail.
- **Notification channels** — notifications are persisted but not yet sent by email or push.
- **OpenAPI/Swagger UI** — the API is documented in Markdown; springdoc's Spring Boot 4 support was
  not confirmed at the time of writing.
- **Mentor search caching** — skipped because caching a paginated result would change the existing
  search response contract.

---

## License

This project was built as a learning exercise.
