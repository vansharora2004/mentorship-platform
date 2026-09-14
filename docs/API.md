# API Reference

Complete REST and WebSocket contract for the Mentorship Platform.

Verified against the controllers, `SecurityConfig`, and the DTO definitions in the codebase.

**Base URL:** `http://localhost:8081`

---

## Contents

- [Authentication](#authentication)
- [Common conventions](#common-conventions)
- [Error format](#error-format)
- [Auth endpoints](#auth-endpoints)
- [Mentor endpoints](#mentor-endpoints)
- [Availability endpoints](#availability-endpoints)
- [Booking endpoints](#booking-endpoints)
- [Session chat history](#session-chat-history)
- [Health](#health)
- [WebSocket chat](#websocket-chat)
- [Endpoint summary](#endpoint-summary)

---

## Authentication

All endpoints except registration, login, and health require a JWT:

```
Authorization: Bearer <token>
```

Tokens are issued by `POST /api/auth/register` and `POST /api/auth/login`, and expire after 1 hour
(`app.jwt.expiration-ms`). Authentication is stateless — there is no server-side session.

Two roles exist: `CANDIDATE` and `MENTOR`. Some endpoints are restricted by role, and several are
additionally restricted by **ownership** — being a mentor is not enough to edit *another* mentor's
profile.

---

## Common conventions

| Topic | Convention |
|---|---|
| Content type | `application/json` |
| Timestamps | ISO-8601 UTC, e.g. `2026-03-14T10:00:00Z` |
| Identity | Taken from the JWT, never from the request body |
| Pagination | `page` (0-based) and `size` query parameters; default size 10, max 50 |

---

## Error format

Every error returns the same shape:

```json
{
  "timestamp": "2026-03-14T10:00:00.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "This slot is no longer available",
  "fields": null
}
```

`fields` is populated only for validation failures:

```json
{
  "timestamp": "2026-03-14T10:00:00.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "fields": {
    "email": "must be a well-formed email address",
    "password": "size must be between 8 and 72"
  }
}
```

### Status codes

| Status | Meaning |
|---|---|
| `200 OK` | Successful read or update |
| `201 Created` | Registration, profile creation, slot creation, booking |
| `204 No Content` | Successful delete or cancellation |
| `400 Bad Request` | Validation failure, malformed JSON, wrong parameter type |
| `401 Unauthorized` | Missing, expired, or invalid token; bad credentials |
| `403 Forbidden` | Authenticated but not permitted (wrong role or not the owner) |
| `404 Not Found` | Resource does not exist |
| `409 Conflict` | Duplicate email, overlapping slot, slot already booked |
| `500 Internal Server Error` | Unexpected failure — returns a generic message only |

> Unexpected errors deliberately return `"An unexpected error occurred"` and nothing else. Exception
> messages can carry SQL, table names, and file paths, so they are logged server-side rather than
> returned.

---

## Auth endpoints

### `POST /api/auth/register`

Creates an account and returns a token. **No authentication required.**

**Request**

```json
{
  "name": "Ada Lovelace",
  "email": "ada@example.com",
  "password": "password123",
  "role": "CANDIDATE"
}
```

| Field | Rules |
|---|---|
| `name` | Required, 2–100 characters |
| `email` | Required, valid email, max 255, must be unique |
| `password` | Required, 8–72 characters (BCrypt truncates beyond 72 bytes) |
| `role` | Required — `CANDIDATE` or `MENTOR` |

**Response `201 Created`**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "email": "ada@example.com",
  "name": "Ada Lovelace",
  "role": "CANDIDATE"
}
```

**Errors** — `400` validation failure · `409` email already registered

---

### `POST /api/auth/login`

**No authentication required.**

**Request**

```json
{ "email": "ada@example.com", "password": "password123" }
```

**Response `200 OK`** — same body as register.

**Errors** — `400` missing fields · `401` invalid credentials

---

### `GET /api/auth/me`

Returns the authenticated user. **Requires a token.**

**Response `200 OK`**

```json
{ "id": 7, "name": "Ada Lovelace", "email": "ada@example.com", "role": "CANDIDATE" }
```

**Errors** — `401` missing or invalid token

---

## Mentor endpoints

### `GET /api/mentors`

Search and filter mentors. **Any authenticated user.**

| Parameter | Required | Description |
|---|---|---|
| `industry` | No | Exact-match filter |
| `expertise` | No | Exact-match filter |
| `page` | No | 0-based page number, default 0 |
| `size` | No | Page size, default 10, max 50 |

**Example** — `GET /api/mentors?industry=FinTech&expertise=Java&page=0&size=10`

**Response `200 OK`**

```json
{
  "content": [
    {
      "mentorId": 12,
      "name": "Grace Hopper",
      "industry": "FinTech",
      "expertise": "Java",
      "experience": 8,
      "bio": "Backend systems.",
      "createdAt": "2026-03-01T09:00:00Z",
      "updatedAt": "2026-03-01T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

---

### `GET /api/mentors/{mentorId}`

A single mentor profile. **Any authenticated user.** Served from the Redis cache when warm.

**Response `200 OK`** — a `MentorProfileResponse` as above.

**Errors** — `404` no profile for that mentor

---

### `GET /api/mentors/{mentorId}/availability`

A mentor's slots. **Any authenticated user.** Cached.

**Response `200 OK`**

```json
[
  {
    "id": 55,
    "mentorId": 12,
    "startTime": "2026-03-14T10:00:00Z",
    "endTime": "2026-03-14T11:00:00Z",
    "status": "AVAILABLE",
    "createdAt": "2026-03-01T09:00:00Z"
  }
]
```

`status` is `AVAILABLE`, `BOOKED`, or `BLOCKED`.

---

### `GET /api/mentors/profile`

The caller's own profile. **Role `MENTOR`.**

**Errors** — `403` not a mentor · `404` no profile yet

---

### `POST /api/mentors/profile`

Creates the caller's profile. **Role `MENTOR`.** One profile per mentor.

**Request**

```json
{
  "industry": "FinTech",
  "expertise": "Java",
  "experience": 8,
  "bio": "Backend systems and distributed architecture."
}
```

| Field | Rules |
|---|---|
| `industry` | Required, max 100 |
| `expertise` | Required, max 255 |
| `experience` | Required, 0–60 |
| `bio` | Optional, max 2000 |

**Response `201 Created`** — the created profile.

**Errors** — `400` validation · `403` not a mentor · `409` profile already exists

---

### `PUT /api/mentors/profile`

Updates the caller's own profile. **Role `MENTOR`.** Same body as create.

**Response `200 OK`** · **Errors** — `400` · `403` · `404`

---

### `DELETE /api/mentors/profile`

Deletes the caller's own profile. **Role `MENTOR`.**

**Response `204 No Content`** · **Errors** — `403` · `404`

---

## Availability endpoints

All availability writes require **role `MENTOR`** and operate only on the caller's own slots.

### `GET /api/availability`

The caller's own slots. **Role `MENTOR`.**

**Response `200 OK`** — array of `AvailabilityResponse`.

---

### `POST /api/availability`

**Request**

```json
{ "startTime": "2026-03-14T10:00:00Z", "endTime": "2026-03-14T11:00:00Z" }
```

Rules: both required; `startTime` before `endTime`; `startTime` must be in the future; must not
overlap an existing slot. Intervals are half-open, so slots that merely touch at a boundary do not
overlap.

**Response `201 Created`**

**Errors** — `400` invalid range or past start · `403` not a mentor · `409` overlaps an existing slot

---

### `PUT /api/availability/{availabilityId}`

Updates a slot. Only a slot still `AVAILABLE` may be changed.

**Response `200 OK`**

**Errors** — `400` · `403` not the owner · `404` · `409` already booked, or overlaps another slot

---

### `DELETE /api/availability/{availabilityId}`

Deletes a slot. Only a slot still `AVAILABLE` may be deleted.

**Response `204 No Content`** · **Errors** — `403` · `404` · `409` already booked

---

## Booking endpoints

### `POST /api/bookings`

Books a slot. **Role `CANDIDATE`.**

**Request**

```json
{ "availabilityId": 55 }
```

**Response `201 Created`**

```json
{
  "id": 900,
  "mentorId": 12,
  "mentorName": "Grace Hopper",
  "candidateId": 7,
  "candidateName": "Ada Lovelace",
  "availabilityId": 55,
  "startTime": "2026-03-14T10:00:00Z",
  "endTime": "2026-03-14T11:00:00Z",
  "status": "CONFIRMED",
  "createdAt": "2026-03-01T09:05:00Z",
  "updatedAt": "2026-03-01T09:05:00Z"
}
```

Side effects, all in the same transaction: the slot becomes `BOOKED`, a `SCHEDULED` session is
created, and a notification event is recorded. The notification is delivered asynchronously **after**
the response is returned.

**Errors**

| Status | Cause |
|---|---|
| `400` | Missing `availabilityId`, or the slot has already started |
| `403` | Not a candidate |
| `404` | No such slot |
| `409` | Slot already booked — including the loser of two concurrent requests |

> Concurrency: the slot row is locked with `SELECT ... FOR UPDATE` and backed by a partial unique
> index. Exactly one of two simultaneous requests succeeds; the other receives `409`.

---

### `GET /api/bookings`

Bookings for the caller — as candidate or as mentor, depending on role. **Any authenticated user.**

**Response `200 OK`** — array of `BookingResponse`, newest first.

---

### `GET /api/bookings/{bookingId}`

A single booking. **Any authenticated user, but only a participant.**

**Errors** — `403` not a participant · `404` no such booking

---

### `DELETE /api/bookings/{bookingId}`

Cancels a booking. **Role `CANDIDATE`, and only the candidate who made it.**

Sets the booking to `CANCELLED`, releases the slot to `AVAILABLE`, cancels the session, and records a
cancellation notification event.

**Response `204 No Content`**

**Errors**

| Status | Cause |
|---|---|
| `403` | Not the candidate who booked it |
| `404` | No such booking |
| `409` | Already cancelled, or the session has already started |

---

## Session chat history

### `GET /api/sessions/{sessionId}/messages`

The transcript of a session. **Any authenticated user who is a participant.**

Readable even after the session is `COMPLETED` or `CANCELLED` — those states reject *new* messages
but keep history available.

**Response `200 OK`**

```json
[
  {
    "id": 1,
    "sessionId": 300,
    "senderId": 7,
    "senderName": "Ada Lovelace",
    "content": "Here is the guide: https://example.com/guide",
    "sentAt": "2026-03-14T10:02:11Z"
  }
]
```

**Errors** — `401` · `403` not a participant · `404` no such session

---

## Health

### `GET /api/health`

Liveness check. **No authentication required.**

---

## WebSocket chat

Live chat during a session. REST is used for history; WebSocket for real-time delivery.

### Connection

| | |
|---|---|
| Endpoint | `ws://localhost:8081/ws` |
| Protocol | STOMP over WebSocket |

The JWT travels on the **STOMP `CONNECT` frame**, not the HTTP handshake — browsers cannot set
custom headers on a WebSocket handshake:

```
CONNECT
Authorization: Bearer <token>
```

A missing or invalid token fails the connection.

### Destinations

| Action | Destination | Notes |
|---|---|---|
| Subscribe | `/topic/session/{sessionId}` | Authorized — participants only |
| Send | `/app/chat/{sessionId}` | Validated and persisted before broadcast |
| Errors | `/user/queue/errors` | Rejections, delivered only to the offending client |

### Sending

```json
{ "content": "Here is the guide: https://example.com/guide" }
```

### Receiving

Subscribers receive the stored message, including its generated id and server timestamp:

```json
{
  "id": 1,
  "sessionId": 300,
  "senderId": 7,
  "senderName": "Ada Lovelace",
  "content": "Here is the guide: https://example.com/guide",
  "sentAt": "2026-03-14T10:02:11Z"
}
```

### Rules

A message is rejected — not broadcast, not stored — unless all of the following hold:

- the connection is authenticated
- the sender is a participant of the session
- the session is `SCHEDULED` or `ACTIVE`
- content is non-blank after trimming and at most **2000 characters**

Subscribing to `/topic/session/{sessionId}` is authorized per session id, so changing the number in
the destination does not grant access to another pair's conversation.

### Example (JavaScript)

```javascript
const client = new StompJs.Client({
  brokerURL: 'ws://localhost:8081/ws',
  connectHeaders: { Authorization: 'Bearer ' + token }
});

client.onConnect = () => {
  client.subscribe('/topic/session/300', msg => console.log(JSON.parse(msg.body)));
  client.subscribe('/user/queue/errors', err => console.warn('rejected:', err.body));
  client.publish({
    destination: '/app/chat/300',
    body: JSON.stringify({ content: 'Hello' })
  });
};

client.activate();
```

---

## Endpoint summary

| Method | Path | Auth | Role / ownership |
|---|---|---|---|
| POST | `/api/auth/register` | — | — |
| POST | `/api/auth/login` | — | — |
| GET | `/api/auth/me` | JWT | Any |
| GET | `/api/mentors` | JWT | Any |
| GET | `/api/mentors/{mentorId}` | JWT | Any |
| GET | `/api/mentors/{mentorId}/availability` | JWT | Any |
| GET | `/api/mentors/profile` | JWT | `MENTOR`, own |
| POST | `/api/mentors/profile` | JWT | `MENTOR`, own |
| PUT | `/api/mentors/profile` | JWT | `MENTOR`, own |
| DELETE | `/api/mentors/profile` | JWT | `MENTOR`, own |
| GET | `/api/availability` | JWT | `MENTOR`, own |
| POST | `/api/availability` | JWT | `MENTOR`, own |
| PUT | `/api/availability/{availabilityId}` | JWT | `MENTOR`, own |
| DELETE | `/api/availability/{availabilityId}` | JWT | `MENTOR`, own |
| POST | `/api/bookings` | JWT | `CANDIDATE` |
| GET | `/api/bookings` | JWT | Any (own) |
| GET | `/api/bookings/{bookingId}` | JWT | Participant |
| DELETE | `/api/bookings/{bookingId}` | JWT | `CANDIDATE`, own |
| GET | `/api/sessions/{sessionId}/messages` | JWT | Participant |
| GET | `/api/health` | — | — |

**WebSocket:** `/ws` (connect) · `/app/chat/{sessionId}` (send) · `/topic/session/{sessionId}`
(subscribe) · `/user/queue/errors` (rejections)

---

*A Postman collection covering these endpoints is in `docs/postman/`.*
