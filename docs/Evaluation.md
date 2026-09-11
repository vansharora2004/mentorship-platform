# Mentorship Platform — Evaluation Criteria

## 1. Purpose

The evaluation document defines how the completed project will be assessed.

The goal is not only to verify that APIs work, but also to evaluate:

- Correctness
- Architecture
- Security
- Concurrency
- Performance
- Reliability
- Testing
- Code quality
- Git/GitHub practices

---

# 2. Functional Evaluation

## Authentication

Verify:

- Candidate registration works.
- Mentor registration works.
- Login works.
- JWT is generated.
- Protected APIs reject unauthenticated requests.
- Role-based authorization works.

---

# 3. Mentor Evaluation

Verify:

- Mentor profile can be created.
- Mentor profile can be updated.
- Mentor profile can be retrieved.
- Candidates can search mentors.
- Industry filtering works.
- Expertise filtering works.
- Pagination works.

Example:

```text
GET /api/mentors?industry=FinTech&expertise=Java
```

---

# 4. Availability Evaluation

Verify:

- Mentor can create availability.
- Mentor can update availability.
- Mentor can delete availability.
- Candidates can view availability.
- Invalid time ranges are rejected.
- Overlapping availability is handled.
- Past slots are handled correctly.

---

# 5. Booking Evaluation

Verify:

- Candidate can book an available slot.
- Candidate cannot book unavailable slots.
- Candidate cannot book past slots.
- Booking information is persisted.
- Session is created correctly.
- Cancellation works according to business rules.

---

# 6. Concurrency Evaluation

This is a critical evaluation category.

Send multiple simultaneous requests for the same slot.

Example:

```text
100 concurrent requests
        ↓
Same mentor
        ↓
Same availability
```

Expected:

```text
Exactly 1 successful booking
Remaining requests → Conflict
```

There must never be multiple confirmed bookings for the same exclusive slot.

---

# 7. Transaction Evaluation

Verify that booking operations are atomic.

Example:

```text
Create booking
+
Create session
```

If an operation fails midway, the transaction should roll back according to the defined transaction boundary.

---

# 8. Redis Evaluation

Verify:

- Cache is populated.
- Repeated reads can use Redis.
- Cache invalidates after updates.
- Application handles Redis failure appropriately.
- Booking correctness does not depend solely on stale Redis data.

---

# 9. RabbitMQ Evaluation

Verify:

```text
Booking
   ↓
Event
   ↓
RabbitMQ
   ↓
Consumer
```

Check:

- Message is published.
- Consumer receives message.
- Notification processing occurs asynchronously.
- Failed messages can be retried/recovered.
- Duplicate processing is handled safely.

---

# 10. Scheduler Evaluation

Verify:

- Scheduled job executes at the configured interval.
- Upcoming sessions are detected.
- Reminder event is generated.
- RabbitMQ receives reminder event.
- Notification consumer processes it.

---

# 11. WebSocket Evaluation

Verify:

- Client can connect.
- Authentication is enforced.
- User can join an authorized session.
- User can send messages.
- Other session participant receives messages in real time.
- Unauthorized users cannot access private session chat.
- Disconnections are handled correctly.

---

# 12. API Performance

Measure:

- Average response time
- Database query count
- Cache hit/miss behavior
- Concurrent request handling

Important endpoints:

```text
GET /mentors
GET /mentors/{id}
GET /availability
POST /bookings
```

---

# 13. Database Performance

Evaluate:

- Proper indexes
- Efficient queries
- Pagination
- Avoidance of unnecessary queries
- Correct relationships
- Transaction boundaries

Potential indexes:

```text
users.email
mentor_profile.industry
mentor_profile.expertise
availability.mentor_id
booking.mentor_id
booking.candidate_id
booking.start_time
```

Indexes should be added based on actual query patterns rather than indiscriminately.

---

# 14. Security Evaluation

Verify:

```text
Password hashing
JWT validation
Role-based authorization
Input validation
Resource ownership validation
WebSocket authorization
```

Test cases:

```text
Invalid JWT
Expired JWT
Missing JWT
Candidate accessing mentor-only endpoint
User accessing another user's booking
User accessing another session's chat
```

---

# 15. Code Quality

Evaluate:

- Clear package structure
- Single responsibility
- Meaningful class names
- Meaningful method names
- Proper exception handling
- Minimal duplicated code
- Proper DTO usage
- Dependency injection
- Configuration externalization
- Clean service/repository separation

Expected structure:

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

# 16. Testing Evaluation

Minimum tests should include:

## Unit Tests

```text
AuthService
MentorService
AvailabilityService
BookingService
```

## Integration Tests

```text
Authentication
Mentor APIs
Booking APIs
Database integration
```

## Concurrency Tests

```text
Multiple requests → Same slot
```

## WebSocket Tests

```text
Connect
Subscribe
Send
Receive
Disconnect
```

---

# 17. API Documentation Evaluation

README or API documentation should clearly describe:

```text
Endpoint
HTTP Method
Authentication
Request
Response
Possible errors
```

Example:

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

---

# 18. Git Evaluation

The repository should demonstrate real development history.

Good commits:

```text
feat: add JWT authentication
feat: implement mentor search
feat: add availability management
feat: implement transactional booking
fix: prevent duplicate bookings
feat: add Redis caching
feat: add RabbitMQ notifications
feat: add WebSocket chat
test: add booking concurrency tests
docs: update architecture
```

Avoid one giant commit such as:

```text
"final project"
```

---

# 19. GitHub Evaluation

The GitHub repository should contain:

```text
README.md
docs/
src/
pom.xml
.gitignore
docker-compose.yml
```

Do not commit:

```text
Passwords
JWT secrets
Database credentials
API keys
Private certificates
.env files containing secrets
```

Use environment variables.

---

# 20. Interview Evaluation

The project should allow the developer to explain:

### Architecture

```text
Why Spring Boot?
Why PostgreSQL?
Why Redis?
Why RabbitMQ?
Why WebSocket?
```

### Concurrency

```text
How did you prevent double booking?
```

### Security

```text
How does JWT authentication work?
```

### Database

```text
Why JPA/Hibernate?
Why transactions?
Why pessimistic locking?
```

### Performance

```text
Where did you use Redis?
Why pagination?
What indexes did you create?
```

### Messaging

```text
Why asynchronous notifications?
What happens if RabbitMQ fails?
```

### Real-time communication

```text
Why WebSocket?
What is STOMP?
How do you secure chat?
```

---

# 21. Scoring Model

A possible evaluation model:

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

---

# 22. Minimum Viable Project

The minimum working version should contain:

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

---

# 23. Complete Version

The complete version should additionally contain:

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

---

# 24. Final Acceptance Criteria

The project is considered complete when the following workflow works end-to-end:

```text
                    ┌──────────────┐
                    │   Register   │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │    Login     │
                    └──────┬───────┘
                           ↓
                       JWT Token
                           ↓
                    ┌──────────────┐
                    │Find a Mentor │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │View Profile  │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │Availability  │
                    └──────┬───────┘
                           ↓
                    ┌──────────────┐
                    │Book Session  │
                    └──────┬───────┘
                           ↓
                  Pessimistic Lock
                           ↓
                    ┌──────────────┐
                    │   Booking    │
                    └──────┬───────┘
                           ↓
                       RabbitMQ
                           ↓
                     Notification
                           ↓
                    ┌──────────────┐
                    │Join Session  │
                    └──────┬───────┘
                           ↓
                  WebSocket + STOMP
                           ↓
                     Live Chat
                           ↓
                    Complete Session
```

The final implementation should be demonstrable locally, reproducible from the GitHub repository, and understandable enough that every major architectural decision can be explained during a technical interview.