# Phases 9–11 — A Practical Guide

A plain-language explanation of the asynchronous messaging, scheduling, and real-time chat layers of
the Mentorship Platform, written for interview preparation.

Everything here describes what this project **actually implements**. Where something was
deliberately left out, it says so explicitly.

---

## Table of contents

- [The one-paragraph summary](#the-one-paragraph-summary)
- [A. Combined architecture (Phases 1–11)](#a-combined-architecture-phases-111)
- [Phase 9 — RabbitMQ and asynchronous notifications](#phase-9--rabbitmq-and-asynchronous-notifications)
- [Phase 10 — Spring Scheduler](#phase-10--spring-scheduler)
- [Phase 11 — WebSocket and STOMP chat](#phase-11--websocket-and-stomp-chat)
- [B. Data-flow diagrams](#b-data-flow-diagrams)
- [C. The Big Picture — REST vs RabbitMQ vs Scheduler vs WebSocket](#c-the-big-picture)
- [D. What happens when something fails?](#d-what-happens-when-something-fails)
- [E. Interview cheat sheet](#e-interview-cheat-sheet)

---

## The one-paragraph summary

Phases 1–8 built a normal REST application: register, log in, find a mentor, see their free slots,
book one. That all happens **inside** a single HTTP request, and PostgreSQL is the source of truth.

Phases 9–11 add the three things that *don't* fit inside a single request:

| Phase | Adds | Because |
|---|---|---|
| **9** | RabbitMQ | Some work should happen **after** the response is sent |
| **10** | Spring Scheduler | Some work has **no request at all** — it happens because time passed |
| **11** | WebSocket | Some data must be **pushed to the user**, not pulled by them |

---

## A. Combined architecture (Phases 1–11)

```
                            ┌──────────────────────────────┐
                            │          Client              │
                            │   (Postman / browser / app)  │
                            └───────┬──────────────┬───────┘
                                    │              │
                        HTTP + JWT  │              │  WebSocket + STOMP
                                    │              │  (Phase 11)
                                    ▼              ▼
   ┌────────────────────────────────────────────────────────────────────────┐
   │                          SPRING BOOT APPLICATION                       │
   │                                                                        │
   │  ┌──────────────────────────┐        ┌────────────────────────────┐    │
   │  │  Security filter chain   │        │  StompAuthChannelInterceptor│   │
   │  │  JwtAuthenticationFilter │        │  CONNECT  → validate JWT    │   │
   │  │        (Phase 3)         │        │  SUBSCRIBE→ check membership│   │
   │  └────────────┬─────────────┘        └──────────────┬─────────────┘    │
   │               │                                     │                  │
   │               ▼                                     ▼                  │
   │  ┌────────────────────────┐            ┌───────────────────────┐       │
   │  │      Controllers       │            │    ChatController     │       │
   │  │ Auth / Mentor /        │            │  /app/chat/{id}       │       │
   │  │ Availability / Booking │            └───────────┬───────────┘       │
   │  │ ChatHistory (P11)      │                        │                   │
   │  └───────────┬────────────┘                        ▼                   │
   │              │                          ┌───────────────────────┐      │
   │              ▼                          │     ChatService       │      │
   │  ┌────────────────────────┐             │ validate + persist    │      │
   │  │       Services         │             └───────────┬───────────┘      │
   │  │ Mentor / Availability  │                         │ broadcast        │
   │  │ Booking  (P4–P7)       │                         ▼                  │
   │  └───────┬────────┬───────┘            /topic/session/{sessionId}      │
   │          │        │                                 │                  │
   │          │        │ publishEvent                    ▼                  │
   │          │        ▼                          both participants         │
   │          │   ┌──────────────────────────┐                              │
   │          │   │  NotificationEventRelay  │   ◀── Phase 9 ──             │
   │          │   │ BEFORE_COMMIT → outbox   │                              │
   │          │   │ AFTER_COMMIT  → publish  │                              │
   │          │   └────────┬─────────┬───────┘                              │
   │          │            │         │                                      │
   │  ┌───────▼────────────▼───┐     │       ┌──────────────────────────┐   │
   │  │      Repositories      │     │       │   Scheduler (Phase 10)   │   │
   │  │       (JPA)            │     │       │  SessionReminderJob      │   │
   │  └───────┬────────────────┘     │       │  SessionStatusJob        │   │
   │          │                      │       │  OutboxRetryJob          │   │
   │          │                      │       └────────┬─────────────────┘   │
   └──────────┼──────────────────────┼────────────────┼───────────────────-─┘
              │                      │                │
              ▼                      ▼                ▼
   ┌────────────────────┐   ┌──────────────────────────────────┐
   │    PostgreSQL      │   │            RabbitMQ              │
   │  SOURCE OF TRUTH   │   │                                  │
   │                    │   │   mentorship.events (topic)      │
   │  users             │   │            │                     │
   │  mentor_profiles   │   │            ▼                     │
   │  availability      │   │   notification.queue             │
   │  bookings          │   │            │                     │
   │  sessions          │   │            ▼                     │
   │  notifications     │◀──┼─── NotificationConsumer          │
   │  chat_messages     │   │            │ (fails 3x)          │
   │  outbox_events     │   │            ▼                     │
   └────────────────────┘   │   mentorship.events.dlx          │
              ▲             │            │                     │
              │             │            ▼                     │
   ┌──────────┴─────────┐   │   notification.dlq               │
   │       Redis        │   └──────────────────────────────────┘
   │   CACHE ONLY       │
   │  mentor:{id}       │    Redis never decides a booking.
   │  availability:{id} │    RabbitMQ never decides a booking.
   │    (Phase 8)       │    PostgreSQL decides.
   └────────────────────┘
```

**The single most important line in that diagram:** PostgreSQL is the source of truth. Redis is a
cache that can be wiped at any moment, and RabbitMQ carries notifications. Neither is ever consulted
to decide whether a booking is allowed.

---

## Phase 9 — RabbitMQ and asynchronous notifications

### 1. What are we implementing?

When a booking is created or cancelled, both people should be notified. The naive way is to send the
notification inside the booking request. This phase moves that work **out** of the request, so the
API answers as soon as the booking is safely stored.

### 2. Technology used

| Technology | Role here |
|---|---|
| **RabbitMQ** | Message broker — holds events until a consumer processes them |
| **Spring AMQP** | Spring's RabbitMQ integration (`RabbitTemplate`, `@RabbitListener`) |
| **Spring application events** | In-process events, so services don't import RabbitMQ types |
| **`@TransactionalEventListener`** | Lets us hook exactly into "before commit" and "after commit" |
| **PostgreSQL** | Stores the outbox and the resulting notifications |

### 3. Why each technology?

**Why a message broker at all?** Two reasons: **speed** (the user doesn't wait for notification
work) and **decoupling** (booking logic doesn't need to know how notifications are delivered — add
email later and `BookingService` never changes).

**Why Spring application events in the middle?** So `BookingService` stays clean. It calls
`eventPublisher.publishEvent(...)` and knows nothing about exchanges or routing keys. That also
means its unit tests need no broker.

**Why `@TransactionalEventListener` instead of a plain listener?** Because *when* we publish decides
whether the system is correct. More on this below — it's the best interview answer in this phase.

### 4. What problem does it solve?

Without it:

```
Booking request → save booking → send notification → wait... → respond
```

If notifications get slow, every booking gets slow. If notification delivery throws, you might roll
back a perfectly good booking. With RabbitMQ:

```
Booking request → save booking → respond          (fast)
                       ↓
                  RabbitMQ → consumer → notification   (separately)
```

### 5. How it works internally

**Exchange, queue, routing key — the three words to know.**

- A **queue** is a list of messages waiting to be processed.
- An **exchange** is the "post office". Publishers never write to a queue directly; they hand the
  message to an exchange.
- A **routing key** is the address on the envelope. The exchange uses it to decide which queues get
  a copy.

This project uses a **topic exchange**, which matches routing keys by pattern:

```
Publisher
    │  routing key: "notification.booking_created"
    ▼
mentorship.events  (topic exchange)
    │  binding pattern: "notification.*"   ← matches
    ▼
notification.queue
    │
    ▼
NotificationConsumer
```

Why a topic exchange rather than sending straight to a queue? **Future flexibility.** Today one
queue takes everything. Tomorrow you could bind an `email.queue` to `notification.booking_*` only,
and publishers wouldn't change at all.

**The dead-letter queue (DLQ).** If the consumer keeps failing, the message must go *somewhere* —
otherwise it either loops forever or vanishes. Configuration: 3 attempts with backoff, then
`default-requeue-rejected=false` pushes it out to `mentorship.events.dlx` → `notification.dlq`,
where a human can inspect it. A poison message can't block the queue.

**AFTER_COMMIT — the heart of the design.** A database transaction can still roll back at the last
moment. If we published *during* the transaction, this could happen:

```
save booking → publish "booking created" → transaction ROLLS BACK
                                            ↓
                    notification sent for a booking that does not exist
```

`@TransactionalEventListener(phase = AFTER_COMMIT)` only fires once the transaction has genuinely
committed. If the booking rolls back, nothing is published.

**The transactional outbox — and why AFTER_COMMIT alone isn't enough.** AFTER_COMMIT fixes one
direction but opens another:

```
transaction commits ✓ → publish to RabbitMQ → RabbitMQ is DOWN → notification lost forever
```

The booking is fine, but the notification silently disappeared. The project's spec
(Edge Cases §10) requires that *"the notification event should be recoverable"*. So publishing
happens in **two steps**:

| Step | When | What |
|---|---|---|
| **Record** | `BEFORE_COMMIT` | Write the event to `outbox_events` as **PENDING**, *inside* the booking transaction |
| **Publish** | `AFTER_COMMIT` | Send to RabbitMQ; on success mark **SENT**, on failure leave **PENDING** |
| **Retry** | Every minute | `OutboxRetryJob` republishes anything still PENDING |

Because the outbox row is written in the *same transaction* as the booking, they commit together or
not at all. A broker outage cannot lose the event, because the event was already safely in
PostgreSQL before RabbitMQ was ever contacted.

```
PENDING  ──── published successfully ────▶  SENT
   ▲                                         (terminal — never republished)
   └── broker unreachable: stays PENDING, retried every minute, forever
```

There is **no attempt limit**. The count is recorded for visibility, but the job never gives up —
the requirement is that events stay recoverable.

**Consumer idempotency.** Messaging systems guarantee *at-least-once* delivery, so the same message
can legitimately arrive twice. The consumer must therefore be safe to run twice. Two mechanisms:

1. **Derived event IDs.** Instead of a random UUID, the ID is built from the event type and subject:
   `BOOKING_CREATED:1001`, `SESSION_REMINDER:42`. Redelivery produces the *same* ID.
2. **A unique constraint** on `(event_id, recipient_id)` in `notifications`. A duplicate hits the
   constraint instead of creating a second notification.

The key is `(eventId, recipientId)` and not `eventId` alone because **one event notifies two
people** — the candidate and the mentor each get their own row.

### 6. Complete data flow

```
1. POST /api/bookings
2. BookingService: lock slot, validate, save booking + session      ← Phase 6/7
3. publishEvent(NotificationEvent.bookingCreated(...))
4. BEFORE_COMMIT → outbox_events row written as PENDING
5. ===== COMMIT ===== (booking + session + outbox row, all atomic)
6. HTTP 201 returned to the client                                   ← user is done here
7. AFTER_COMMIT → publish to mentorship.events
8. RabbitMQ routes to notification.queue
9. NotificationConsumer reads it
10. Writes one notification row per participant (skipping duplicates)
11. Outbox row marked SENT
```

Steps 7–11 happen *after* the user has their response.

### 7. Architecture — how components connect

```
BookingService ──publishEvent──▶ NotificationEventRelay
                                       │
              ┌────────────────────────┼────────────────────────┐
              │ BEFORE_COMMIT          │ AFTER_COMMIT           │
              ▼                        ▼                        │
        NotificationOutbox      NotificationPublisher           │
              │                        │                        │
              ▼                        ▼                        │
        outbox_events            RabbitMQ                       │
        (PENDING)                     │                         │
              ▲                       ▼                         │
              │                NotificationConsumer             │
              │                       │                         │
              │                       ▼                         │
              │                  notifications                  │
              │                                                 │
        OutboxRetryJob ◀──── every minute ────────────────────-─┘
```

### 8. Important classes

| Class | Responsibility |
|---|---|
| `NotificationEvent` | The payload (a record). Carries **only IDs**, never entity objects |
| `NotificationEventType` | `BOOKING_CREATED`, `BOOKING_CANCELLED`, `SESSION_REMINDER` |
| `RabbitConfig` | Declares exchange, queue, DLQ, bindings, JSON converter |
| `NotificationEventRelay` | The two-phase bridge: record before commit, publish after |
| `NotificationOutbox` | Writes/updates outbox rows (`record`, `markSent`, `markAttemptFailed`) |
| `NotificationPublisher` | Sends to RabbitMQ; returns `true`/`false`, never throws |
| `NotificationConsumer` | `@RabbitListener` — writes notifications, idempotently |
| `OutboxEvent` / `OutboxStatus` | The durable record and its PENDING/SENT state |
| `Notification` | The delivered notification (one row per recipient) |

**Why does `NotificationEvent` carry only IDs?** Because a message might be processed seconds or
hours later. Embedding a copy of the booking would risk acting on stale data. Carrying
`bookingId` means the consumer can always re-read current state from PostgreSQL.

### 9. How it connects to previous phases

- **Phase 6/7 (booking)** — the trigger. The locking and transaction logic was **not changed**;
  only two `publishEvent` lines were added.
- **Phase 8 (Redis)** — unchanged. Cache eviction and notification publishing are independent.
- **Phase 2 (JPA)** — two new tables, `notifications` and `outbox_events`.

### 10. Failure handling

| Situation | Behaviour |
|---|---|
| RabbitMQ down when booking | Booking succeeds; event sits PENDING; retry delivers it later |
| Booking rolls back | Nothing published, no outbox row — neither listener fires |
| Consumer throws | Retried 3× with backoff, then dead-lettered |
| Same message twice | Second one is a no-op (unique constraint) |
| Malformed message | Rejected immediately to the DLQ, not retried pointlessly |

### 11. Testing strategy

| Test | Proves |
|---|---|
| `NotificationConsumerTest` | Both participants notified; duplicates skipped; bad payloads dead-lettered |
| `NotificationPublisherTest` | Correct routing keys; a broker outage never throws at the caller |
| `NotificationFlowIntegrationTest` | Real end-to-end through a real RabbitMQ |
| `RabbitFailureFallbackTest` | With the broker unreachable: booking still correct **and** event survives as PENDING |
| `NotificationOutboxIntegrationTest` | Retry publishes a stranded event; SENT events are never republished |
| `OutboxRetryJobTest` | Retry selection rules and each success/failure outcome |

`RabbitFailureFallbackTest` points Spring at a **closed port** — it needs no broker, which is
exactly the point.

### 12. Interview questions

**"Why not just send the notification inside the booking method?"**
It couples booking latency to notification latency, and a notification failure could roll back a
valid booking.

**"How do you avoid notifying about a booking that rolled back?"**
Publish on `AFTER_COMMIT`, so publishing only happens once the transaction genuinely committed.

**"But then what if the broker is down after commit?"**
That's why there's an outbox. The event is written to the database *inside* the booking
transaction, before RabbitMQ is contacted. It stays PENDING until a scheduled retry delivers it.

**"How do you handle duplicate messages?"**
Make the consumer idempotent. Derived (not random) event IDs plus a unique
`(event_id, recipient_id)` constraint mean a redelivery writes nothing new.

**"What's a DLQ for?"**
A message that can never succeed would otherwise loop forever or be lost. After 3 attempts it's
parked on the dead-letter queue for inspection, and the main queue keeps flowing.

---

## Phase 10 — Spring Scheduler

### 1. What are we implementing?

Work that no user requests. Nobody clicks "remind me" — the reminder happens *because time passed*.

Three jobs, each running every minute:

| Job | What it does |
|---|---|
| `SessionReminderJob` | Finds sessions starting within 15 minutes, emits `SESSION_REMINDER` |
| `SessionStatusJob` | Moves sessions SCHEDULED → ACTIVE → COMPLETED as time passes |
| `OutboxRetryJob` | Republishes notification events RabbitMQ never acknowledged (Phase 9) |

### 2. Technology used

**Spring Scheduler** (`@EnableScheduling` + `@Scheduled`) — built into Spring, no extra
infrastructure. It runs a method on a timer inside the application.

### 3. Why this technology?

It's the simplest thing that works for a single instance. Nothing to install, nothing to operate.
The trade-off appears when you run several instances — see the deferred items below.

### 4. What problem does it solve?

Some state changes have no triggering request. A session that ended an hour ago should be
`COMPLETED`, but no one is going to call an API to say so. Without a scheduler, session status would
be wrong until somebody happened to touch that row.

### 5. How it works internally

`@Scheduled(fixedDelayString = "...")` tells Spring to invoke the method repeatedly. **fixedDelay**
measures from the *end* of the previous run, so runs never overlap even if one is slow.

Every job follows the same shape, and it's worth learning as a pattern:

```
1. Query rows that still need work   (never rows already finished)
2. Limit the batch                   (a backlog can't blow up memory)
3. Do the work
4. Record the result so the row isn't picked up again
```

**Idempotency — why these jobs are safe to re-run.** Each job's *query* excludes already-processed
rows:

- `SessionReminderJob` requires `reminderSentAt IS NULL` → a session is reminded once.
- `SessionStatusJob` selects only SCHEDULED/ACTIVE → a COMPLETED session is never touched again.
- `OutboxRetryJob` selects only PENDING → a SENT event is never republished.

This is what makes a missed run self-healing. If the application was down for three hours, the next
run simply picks up everything whose time has since passed. A session whose entire window elapsed
during the outage goes **straight to COMPLETED** — the job doesn't need to have observed the
intermediate ACTIVE state.

### 6. Complete reminder flow

```
Every minute
     │
     ▼
Find sessions where:
   status = SCHEDULED
   reminderSentAt IS NULL          ← the idempotency guard
   startTime between now and now+15min
     │
     ▼
For each: stamp reminderSentAt + publishEvent(SESSION_REMINDER)
     │
     ▼
=== COMMIT ===   (the stamp and the outbox row commit together)
     │
     ▼
AFTER_COMMIT → RabbitMQ → NotificationConsumer → notification rows
```

Notice the reminder flows through **exactly the same Phase 9 machinery** as a booking. The scheduler
doesn't talk to RabbitMQ directly; it raises an application event, and the outbox and relay handle
the rest. That means a reminder raised while RabbitMQ is down is recoverable too.

### 7. Architecture

```
   ┌──────────────────────┐
   │  Spring Scheduler    │  (@EnableScheduling, gated by app.scheduler.enabled)
   └──────────┬───────────┘
              │ every minute
    ┌─────────┼──────────────────────┐
    ▼         ▼                      ▼
SessionReminderJob  SessionStatusJob   OutboxRetryJob
    │                    │                   │
    │ publishEvent       │ updates           │ publishes directly
    ▼                    ▼                   ▼
 Phase 9 outbox      sessions/bookings    RabbitMQ
   + RabbitMQ           tables
```

### 8. Important classes

| Class | Responsibility |
|---|---|
| `SchedulingConfig` | Enables scheduling; gated on `app.scheduler.enabled` (default on) |
| `SessionReminderJob` | Finds due sessions, stamps `reminderSentAt`, raises the event |
| `SessionStatusJob` | Advances session state; completes the CONFIRMED booking too |
| `OutboxRetryJob` | Drains PENDING outbox rows, oldest first |

### 9. How it connects to previous phases

- **Sessions** come from Phase 6 — created in the same transaction as the booking.
- **Phase 9** is the delivery path for reminders; `OutboxRetryJob` completes Phase 9's guarantee.
- `Session.reminderSentAt` was **added** — a new column, exposed by no API or DTO.

### 10. Failure handling

| Situation | Behaviour |
|---|---|
| Application restarts | Jobs resume; queries find whatever is now due |
| A run is missed entirely | Next run catches up — state is derived from time, not from ticks |
| Job throws mid-batch | Its transaction rolls back; rows stay unprocessed and are retried |
| Two instances run the same job | Possible duplicate publish, but the idempotent consumer means still exactly one notification per person |

### 11. Testing strategy

The timer is **disabled during tests** (`app.scheduler.enabled=false` via surefire) and the job
methods are called directly. This is deliberate and worth being able to defend:

- Waiting for a real timer would mean `Thread.sleep` — slow and flaky.
- A background job firing mid-test would corrupt other tests' data.

Calling the method directly tests the same logic *deterministically*.

| Test | Proves |
|---|---|
| `SessionReminderJobTest` | Reminds inside the window; never twice; ignores far-future, started, and cancelled sessions; reaches both participants through the real broker |
| `SessionStatusJobTest` | Each transition; completes the booking; never resurrects a CANCELLED session; a fully-missed window goes straight to COMPLETED; repeat runs change nothing |
| `OutboxRetryJobTest` | Selection rules and every success/failure branch |

### 12. Interview questions

**"fixedDelay vs fixedRate?"**
`fixedRate` measures start-to-start and can overlap if a run is slow. `fixedDelay` measures
end-to-start, so runs never overlap. This project uses `fixedDelay`.

**"What happens if the app was down when a job should have run?"**
Nothing is lost, because the jobs query for *state*, not for missed ticks. The next run finds
everything now due.

**"What breaks if you run two instances?"**
Both schedulers fire, so a job can run twice. Here that's tolerable because the consumer is
idempotent. The proper fix is a distributed lock (e.g. ShedLock) — **deliberately not implemented**,
and recorded as future work.

**"How do you test a scheduled job without waiting?"**
Disable the timer and call the method directly. The timer is Spring's responsibility; the logic is
yours.

---

## Phase 11 — WebSocket and STOMP chat

### 1. What are we implementing?

Live chat between a mentor and a candidate during their session. Messages are persisted, and a
participant who reconnects can fetch the transcript.

### 2. Technology used

| Technology | Role |
|---|---|
| **WebSocket** | A connection that stays open, allowing the **server to push** |
| **STOMP** | A simple messaging protocol *on top of* WebSocket |
| **Spring's simple broker** | In-memory broker that routes to subscribers |
| **JWT** | Same tokens as REST, validated on the STOMP CONNECT frame |
| **PostgreSQL** | Stores `chat_messages` |

### 3. Why each technology?

**Why WebSocket instead of REST?** HTTP is one-way: the client asks, the server answers. The server
can't start a conversation. For chat you'd have to poll ("any new messages?" every second) — wasteful
and still laggy. A WebSocket stays open, so the server pushes the instant a message arrives.

**Why STOMP on top?** Raw WebSocket just moves bytes — no concept of "topics" or "subscriptions".
You'd invent your own. STOMP gives you `CONNECT`, `SUBSCRIBE`, `SEND` and destination addresses for
free, and Spring routes them to `@MessageMapping` methods much like `@GetMapping`.

### 4. What problem does it solve?

Two people in a live session need to exchange messages and links with sub-second latency, and
neither should be able to read anyone else's conversation.

### 5. How it works internally

**The three STOMP frames:**

| Frame | Destination | Meaning |
|---|---|---|
| `CONNECT` | `/ws` | "Open a session." Carries the JWT |
| `SUBSCRIBE` | `/topic/session/{sessionId}` | "Send me everything posted here" |
| `SEND` | `/app/chat/{sessionId}` | "Here is a message" |

**Why two different prefixes?** This trips people up, and it's a great question to be ready for:

- `/app/**` → routed to **your code** (a `@MessageMapping` controller method).
- `/topic/**` → routed to the **broker**, which fans it out to subscribers.

You **send** to `/app` so the server can validate and store the message. The server then **publishes**
to `/topic`, which is what subscribers actually receive. If clients could write straight to `/topic`,
they'd bypass all validation.

**Authentication — why it's not in the normal filter chain.** A browser's WebSocket API **cannot set
custom headers**, so the `Authorization: Bearer ...` header can't ride on the HTTP handshake. The
token is put on the STOMP `CONNECT` frame instead, and `StompAuthChannelInterceptor` validates it
there. That's why `/ws/**` is `permitAll()` in `SecurityConfig` — the check **moved**, it didn't
disappear.

**Authorization — the subtle part.** Authenticating the user is not enough. Destinations contain a
session ID:

```
/topic/session/42      ← Alice's session
/topic/session/43      ← someone else's session
```

A logged-in user could simply change the number. So the interceptor also inspects **SUBSCRIBE**
frames: it extracts the ID from the destination and verifies that this user is actually one of the
two participants. Without that check, any authenticated user could read any conversation.

### 6. Complete chat data flow

```
1. Client opens WebSocket to /ws
2. CONNECT frame, header: Authorization: Bearer <JWT>
        → StompAuthChannelInterceptor validates the token, attaches the principal
        → invalid/missing token: connection refused
3. SUBSCRIBE /topic/session/42
        → interceptor checks: is this user a participant of session 42?
        → no: rejected
4. SEND /app/chat/42  {"content": "https://example.com/guide"}
        → ChatController receives it
        → ChatService validates:
             session exists
             sender is a participant
             session is SCHEDULED or ACTIVE
             content not blank, ≤ 2000 chars
        → saves to chat_messages
5. Server publishes the SAVED message to /topic/session/42
6. Both subscribers receive it in real time
```

Note the message is **persisted before it's broadcast**, so what participants see is exactly what the
transcript holds — same ID, same server timestamp.

### 7. Architecture

```
  Candidate                                  Mentor
      │                                        │
      │ SEND /app/chat/42                      │ SUBSCRIBE /topic/session/42
      ▼                                        ▲
┌──────────────────────────────────────────────┴──────────┐
│           StompAuthChannelInterceptor                    │
│   CONNECT   → validate JWT, attach principal             │
│   SUBSCRIBE → verify session membership                  │
└─────────────────────────┬────────────────────────────────┘
                          ▼
                   ChatController  (@MessageMapping)
                          │
                          ▼
                     ChatService      ─── validate ───▶ reject → /user/queue/errors
                          │
                          ▼
                    chat_messages (PostgreSQL)
                          │
                          ▼
              broker → /topic/session/42 → all subscribers
```

### 8. Important classes

| Class | Responsibility |
|---|---|
| `WebSocketConfig` | Registers `/ws`, prefixes `/app` and `/topic`, installs the interceptor |
| `StompAuthChannelInterceptor` | CONNECT authentication + SUBSCRIBE authorization |
| `ChatController` | `@MessageMapping("/chat/{sessionId}")`, then broadcasts |
| `ChatService` | All the rules; kept out of the controller so it's unit-testable without a broker |
| `ChatMessage` | The persisted message (max 2000 chars) |
| `ChatHistoryController` | `GET /api/sessions/{sessionId}/messages` — plain REST |

**Why is the history endpoint REST and not WebSocket?** Fetching past messages is a normal
request/response — you ask once and get an answer. WebSocket is for data arriving *unpredictably*.
Using the right tool for each is itself a good interview point.

### 9. How it connects to previous phases

- **Phase 3 (JWT)** — the *same* `JwtService` validates REST requests and STOMP CONNECT frames.
- **Phase 6 (sessions)** — a chat exists only for a session, which exists only for a booking.
  Membership is derived from the booking's candidate and mentor.
- **Phase 10** — `SessionStatusJob` moves a session to COMPLETED, which is what eventually closes
  chat to new messages.

### 10. Failure handling and edge cases

| Situation | Behaviour |
|---|---|
| No token / invalid token | CONNECT refused |
| Non-participant subscribes | SUBSCRIBE rejected — can't read others' chats |
| Non-participant sends | Rejected; nothing broadcast, nothing stored |
| Empty or whitespace-only | Rejected |
| Over 2000 characters | Rejected |
| Unknown session | Rejected, and the connection stays usable |
| COMPLETED / CANCELLED session | New messages rejected; **history still readable** |
| Client disconnects | Handled gracefully; the other participant is unaffected |

Rejections go to `/user/queue/errors` — back to the offending client only, never broadcast to the
session.

### 11. Testing strategy

`ChatWebSocketIntegrationTest` drives a **real STOMP client over a real WebSocket** against the
running app — handshake, authentication, subscription, controller, and broadcast all exercised
together.

Worth knowing for interviews: the negative tests include **control assertions**. For "a stranger
can't subscribe", it isn't enough to assert the stranger received nothing — that would also pass if
the whole send path were broken. So the test *also* asserts the legitimate participant **did**
receive the message. The silence is then meaningful.

### 12. Interview questions

**"WebSocket vs HTTP?"**
HTTP is request/response and client-initiated. WebSocket is a persistent, two-way connection where
the server can push.

**"Why STOMP rather than raw WebSocket?"**
Raw WebSocket has no concept of destinations or subscriptions. STOMP provides them, and Spring can
route frames to controller methods.

**"Why `/app` and `/topic`?"**
`/app` goes to your controller (validate, persist); `/topic` goes to the broker (fan-out). Clients
send to `/app` so nothing bypasses validation.

**"How do you authenticate a WebSocket?"**
Browsers can't set headers on the handshake, so the JWT travels on the STOMP CONNECT frame and a
`ChannelInterceptor` validates it.

**"How do you stop someone reading another user's chat?"**
Authorize the **SUBSCRIBE** frame, not just the connection — parse the session ID out of the
destination and verify membership.

---

## B. Data-flow diagrams

### Phase 9 — booking notification

```
  User                Application                PostgreSQL           RabbitMQ
   │                       │                          │                   │
   │ POST /api/bookings    │                          │                   │
   ├──────────────────────▶│                          │                   │
   │                       │ lock slot, save booking  │                   │
   │                       ├─────────────────────────▶│                   │
   │                       │ write outbox (PENDING)   │                   │
   │                       ├─────────────────────────▶│                   │
   │                       │        ══ COMMIT ══      │                   │
   │  201 Created          │                          │                   │
   │◀──────────────────────┤                          │                   │
   │                       │                          │                   │
   │       (user is done)  │ AFTER_COMMIT: publish    │                   │
   │                       ├──────────────────────────┼──────────────────▶│
   │                       │                          │                   │
   │                       │ consumer receives        │◀──────────────────┤
   │                       │ write notifications ×2   │                   │
   │                       ├─────────────────────────▶│                   │
   │                       │ mark outbox SENT         │                   │
   │                       ├─────────────────────────▶│                   │
```

### Phase 10 — session reminder

```
  Timer              SessionReminderJob        PostgreSQL          RabbitMQ
   │                        │                       │                  │
   │ every minute           │                       │                  │
   ├───────────────────────▶│                       │                  │
   │                        │ find sessions due     │                  │
   │                        │ (reminderSentAt NULL) │                  │
   │                        ├──────────────────────▶│                  │
   │                        │ stamp reminderSentAt  │                  │
   │                        │ + outbox row PENDING  │                  │
   │                        ├──────────────────────▶│                  │
   │                        │     ══ COMMIT ══      │                  │
   │                        │ AFTER_COMMIT: publish │                  │
   │                        ├───────────────────────┼─────────────────▶│
   │                        │                       │                  │
   │                        │  consumer → notifications ×2             │
   │                        ├──────────────────────▶│◀─────────────────┤
```

### Phase 11 — chat message

```
 Candidate          Interceptor        ChatController/Service      Mentor
    │                    │                       │                    │
    │ CONNECT + JWT      │                       │                    │
    ├───────────────────▶│ validate token        │                    │
    │        connected   │                       │                    │
    │◀───────────────────┤                       │                    │
    │                    │                       │  SUBSCRIBE         │
    │                    │◀───────────────────────────────────────────┤
    │                    │ verify membership     │     subscribed     │
    │                    ├───────────────────────────────────────────▶│
    │ SEND /app/chat/42  │                       │                    │
    ├────────────────────┼──────────────────────▶│                    │
    │                    │                       │ validate + save    │
    │                    │                       │ to chat_messages   │
    │                    │                       │                    │
    │                    │  broadcast /topic/session/42                │
    │◀───────────────────┼───────────────────────┼───────────────────▶│
```

---

## C. The Big Picture

Four ways of moving work around, and when each is right.

| | **HTTP REST** | **RabbitMQ** | **Scheduler** | **WebSocket** |
|---|---|---|---|---|
| Who starts it | Client | The app, after an event | Time | Either side |
| Timing | Immediate | Soon after | Periodic | Instant, continuous |
| Does the user wait? | Yes | No | No user involved | No |
| Direction | Request → response | One-way | None | Two-way |
| Used here for | Bookings, profiles, availability, chat history | Notifications | Reminders, session status, outbox retry | Live chat |

**How to choose:**

```
Does a user need an answer right now?              → REST
Must it happen, but not while the user waits?      → RabbitMQ
Does it happen because time passed, not a request? → Scheduler
Must the server push without being asked?          → WebSocket
```

**The booking flow uses all four:**

1. **REST** — `POST /api/bookings` returns 201 immediately.
2. **RabbitMQ** — the notification is delivered afterwards.
3. **Scheduler** — 15 minutes before the session, a reminder fires.
4. **WebSocket** — during the session, they chat live.

---

## D. What happens when something fails?

### RabbitMQ unavailable

| | |
|---|---|
| **Booking** | Still succeeds. Publishing happens after commit, so the broker can't roll it back |
| **Event** | Not lost — already committed to `outbox_events` as PENDING before RabbitMQ was contacted |
| **Recovery** | `OutboxRetryJob` republishes every minute until it succeeds. No attempt limit |
| **User impact** | None on booking; the notification simply arrives late |

This is the two-sided guarantee: **RabbitMQ can't break the booking, and the booking can't lose the
notification.**

### Database failure

PostgreSQL is the source of truth, so this one is genuinely fatal — and deliberately so. The
transaction rolls back, the booking doesn't happen, an error is returned, and no outbox row exists
so nothing is published. The system stays *consistent*: it would rather do nothing than record a
booking it can't stand behind.

Contrast with **Redis** (Phase 8) failing: reads fall back to PostgreSQL and everything still works,
because Redis is only a cache.

### Application restart

| Data | Survives? |
|---|---|
| Bookings, sessions, chat messages, notifications | ✅ PostgreSQL |
| PENDING outbox events | ✅ picked up by the retry job on restart |
| Unconsumed RabbitMQ messages | ✅ queues are durable |
| Redis cache | ❌ but harmless — it repopulates from PostgreSQL |
| WebSocket connections | ❌ clients must reconnect; history is readable over REST |

Scheduled jobs need no catch-up logic, because they query for state rather than for missed ticks.

### Scheduler missed a run

Nothing is lost. Each job asks "what is due *now*?", so the next run handles everything that became
due meanwhile. A session whose whole window elapsed during downtime goes straight to COMPLETED.

### Duplicate event or message

Expected, not exceptional — messaging is at-least-once. Two defences:

1. **Derived event IDs** — a redelivery has the same ID as the original.
2. **Unique `(event_id, recipient_id)`** — the duplicate hits the constraint and writes nothing.

So the same message can arrive five times and each person still gets exactly one notification.

### WebSocket disconnect

The server handles it gracefully and the other participant is unaffected. On reconnect the client
re-authenticates, re-subscribes, and can fetch anything it missed from
`GET /api/sessions/{id}/messages` — which is precisely why messages are persisted rather than only
broadcast.

---

## E. Interview cheat sheet

### One-line answers

| Question | Answer |
|---|---|
| Why RabbitMQ? | So slow notification work doesn't delay the booking response, and the two stay decoupled |
| Why AFTER_COMMIT? | So a rolled-back booking never sends a notification about a booking that doesn't exist |
| Why an outbox? | AFTER_COMMIT alone loses the event if the broker is down. The outbox commits it to the DB first |
| Why derived event IDs? | They make redelivery detectable — the same logical event always has the same ID |
| Why `(eventId, recipientId)`? | One event notifies two people, so uniqueness must be per recipient |
| Why a DLQ? | A message that can never succeed would loop forever or vanish; park it for inspection |
| Why fixedDelay? | Measures from the end of the last run, so slow runs never overlap |
| Why is the scheduler off in tests? | Timer-based tests need sleeps and corrupt other tests; calling jobs directly is deterministic |
| Why STOMP? | Raw WebSocket has no destinations or subscriptions; STOMP adds them |
| Why `/app` vs `/topic`? | `/app` → your controller (validate + persist); `/topic` → broker fan-out |
| Why JWT on CONNECT? | Browsers can't set headers on the WebSocket handshake |
| Why authorize SUBSCRIBE? | Otherwise any logged-in user reads any chat by editing the session ID |
| Why persist chat? | So a reconnecting user can recover what they missed |

### Key terms

- **Exchange** — the post office; publishers hand messages here, never to a queue directly.
- **Routing key** — the address; the exchange uses it to pick queues.
- **Topic exchange** — matches routing keys by pattern (`notification.*`).
- **DLQ** — where messages go after repeated failure.
- **At-least-once delivery** — a message may arrive more than once; consumers must be idempotent.
- **Idempotent** — running it twice has the same effect as running it once.
- **Transactional outbox** — write the event to the DB in the same transaction as the business data,
  publish separately, retry until delivered.
- **STOMP** — a simple text messaging protocol over WebSocket.

### The three guarantees to be able to state

1. **PostgreSQL is the source of truth.** Redis is a cache; RabbitMQ carries notifications. Neither
   decides whether a booking is allowed — that's a pessimistic row lock plus a partial unique index
   (Phase 7).
2. **A booking is never damaged by a notification failure**, and **a notification is never lost by a
   booking succeeding.** Outbox before commit, publish after.
3. **Everything that can run twice is safe to run twice** — consumers, scheduled jobs, and retries.

### Implemented vs deferred

Know the difference; being able to say "we deliberately didn't do X, and here's why" is a strong
signal.

**Implemented (required):**

- RabbitMQ topology with topic exchange, queue, DLQ, retries
- Asynchronous notifications, decoupled from the booking transaction
- Transactional outbox with PENDING/SENT and unlimited scheduled retry
- Idempotent consumer
- Session reminder job and session status job
- WebSocket/STOMP chat with JWT auth, subscription authorization, persistence, and history API

**Deliberately deferred (genuinely optional in the specs):**

| Deferred | Why |
|---|---|
| Availability cleanup job | The docs list it only under *"other possible scheduled tasks"*. Implementing it would need a new `AvailabilityStatus` value that appears in the Phase 5 API response and would make past slots non-editable |
| Distributed scheduler locking | The docs treat multi-instance as a later concern. Safe today because the consumer is idempotent |
| STOMP broker relay | The in-memory broker is fine for one instance; a relay is needed before scaling out |
| Purging SENT outbox rows | Retained as an audit trail |

---

*This guide describes the system as implemented. For the formal as-built record with requirement
classifications, see `PROJECT_CONTEXT.md`.*
