# Seat Reservation at Scale — Paytm Money Backend Engineering

A high-throughput, race-free distributed seat reservation system designed for massive concurrent on-sale ticket stampedes. Built with **Java 21**, **Spring Boot 3**, **PostgreSQL**, **Virtual Threads (Project Loom)**, and **Prometheus Observability**.

---

## 🚀 Key Highlights & Correctness Guarantees

1. **Zero Double-Sell Under Extreme Stampedes:** Pushes the atomic decision to the database via deterministic lexicographical row-level write locks (`SELECT ... FOR UPDATE`). In a 500-user race for seat `A1`, exactly one wins (`201 Created`); the other 499 receive clean domain declines (`409 Conflict`).
2. **Zero 5xx Under Load:** All concurrent races, contention, and limit exceedances are translated to clean 4xx domain outcomes, guaranteeing zero server-side 5xx errors.
3. **Deadlock-Free Multi-Seat Allocation:** Seat identifiers are sorted lexicographically before acquiring locks, mathematically preventing cyclic wait deadlocks.
4. **Exact-Once Idempotency:** Guarantees that retrying with the same key returns the original reservation without duplicate seats or charges. Reusing the same key with altered seat requests is rejected with `409 Conflict`.
5. **Strict Per-User Limit Enforcement:** Users are prevented from holding more than `per_user_limit` (default 4) seats via serialized advisory locking, even when firing 20 concurrent requests.
6. **Token-Derived Security Context:** Authentication is strictly derived from `Authorization: Bearer <token>`. Imposter user IDs in the request body are ignored, and only reservation owners may cancel reservations.
7. **Strict Reconciliation Invariant:** `available + held + confirmed == total_seats` holds to the exact unit at all times.

---

## 🛠 Architecture & Tech Stack

- **Language & Runtime:** Java 21 (LTS) with Virtual Threads (`spring.threads.virtual.enabled=true`)
- **Framework:** Spring Boot 3.4.3 (Spring Data JPA, Hibernate 6, Spring Actuator)
- **Database:** PostgreSQL 16 with HikariCP Connection Pool (sized for high concurrency)
- **Metrics & Observability:** Micrometer + Prometheus scraping endpoint at `/actuator/prometheus` & `/metrics`
- **Tracing:** Correlation Trace ID (`X-Request-Id`) in MDC logs and response headers
- **Containerization:** Multi-stage Dockerfile (Alpine Linux) and Docker Compose

---

## 📋 API Specification

Money values are represented in **paise (integer minor units)** — never floating point numbers.

### 1. Create a Show (Admin)
`POST /shows`
```bash
curl -X POST http://localhost:8080/shows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "coldplay-mumbai",
    "seats": ["A1","A2","A3","A4","B1","B2"],
    "price_paise": 25000,
    "per_user_limit": 4
  }'
```
**Response (201 Created):**
```json
{
  "id": "e9b21f92-563b-4b2a-89a5-a0bcde123456",
  "name": "coldplay-mumbai",
  "price_paise": 25000,
  "total_seats": 6,
  "counts": {
    "available": 6,
    "held": 0,
    "confirmed": 0
  },
  "seats": {
    "A1": "available",
    "A2": "available",
    ...
  }
}
```

### 2. Reserve Seat(s) (Authenticated User)
`POST /shows/{id}/reserve`
- Header: `Authorization: Bearer <user_token>`
- Header or Body: `Idempotency-Key: <unique_key>`
```bash
curl -X POST http://localhost:8080/shows/e9b21f92-563b-4b2a-89a5-a0bcde123456/reserve \
  -H "Authorization: Bearer user_john" \
  -H "Idempotency-Key: idemp-998811" \
  -H "Content-Type: application/json" \
  -d '{
    "seats": ["A1", "A2"]
  }'
```
**Response (201 Created):**
```json
{
  "reservation_id": "7bfa168a-21e3-466d-9be5-96c21e649081",
  "show_id": "e9b21f92-563b-4b2a-89a5-a0bcde123456",
  "user_id": "user_john",
  "seats": ["A1", "A2"],
  "amount_paise": 50000,
  "status": "confirmed"
}
```

### 3. Cancel / Release Reservation (Owner Only)
`POST /reservations/{id}/cancel`
- Header: `Authorization: Bearer <user_token>`
```bash
curl -X POST http://localhost:8080/reservations/7bfa168a-21e3-466d-9be5-96c21e649081/cancel \
  -H "Authorization: Bearer user_john"
```
**Response (200 OK):**
```json
{
  "reservation_id": "7bfa168a-21e3-466d-9be5-96c21e649081",
  "status": "cancelled",
  "message": "Reservation successfully cancelled and seats released"
}
```

### 4. Show State & Reconciliation
`GET /shows/{id}`
```bash
curl http://localhost:8080/shows/e9b21f92-563b-4b2a-89a5-a0bcde123456
```
**Response (200 OK):**
```json
{
  "id": "e9b21f92-563b-4b2a-89a5-a0bcde123456",
  "name": "coldplay-mumbai",
  "price_paise": 25000,
  "total_seats": 6,
  "counts": {
    "available": 6,
    "held": 0,
    "confirmed": 0
  },
  "seats": {
    "A1": "available",
    "A2": "available",
    ...
  }
}
```

### 5. Health Probes & Prometheus Metrics
- `GET /health/live`: Liveness probe (`{"status":"UP"}`)
- `GET /health/ready`: Readiness probe (validates PostgreSQL connectivity via `SELECT 1`, returns 503 if unreachable)
- `GET /metrics` or `GET /actuator/prometheus`: Prometheus scrape format

---

## ⚡ Quick Start

### Option A: Docker Compose (Recommended)
Spins up PostgreSQL 16, Spring Boot App with Java 21, and Prometheus:
```bash
docker compose up --build
```
The service will be live at `http://localhost:8080`, and Prometheus at `http://localhost:9090`.

### Option B: Local Maven Run
1. Start PostgreSQL (e.g. `docker compose up -d db` or local Postgres)
2. Run with Maven Wrapper:
```bash
# Windows
mvnw.cmd spring-boot:run

# Linux / MacOS
./mvnw spring-boot:run
```

### Option C: Run Automated Concurrency Tests
```bash
mvnw.cmd test
```
Executes the comprehensive concurrency test suite:
- `testHotSeatConcurrencyBurst` (50 concurrent threads racing for 1 seat)
- `testPerUserLimitConcurrency` (10 parallel requests on limit=4 show)
- `testReverseOrderMultiSeatContention` (Deadlock avoidance with inverted multi-seat requests)
- `testIdempotencyExactOnceAndMismatch` (Parallel identical retries & payload mismatch rejection)
- `testCancellationAndRebooking` (Owner authorization & immediate re-bookability)

---

## 🌪 One-Command High-Concurrency Burst Benchmark

We include a multithreaded burst script (`burst.py` / `burst.sh` / `burst.bat`) that reproduces an on-sale stampede against any running instance (local or remote URL):

```bash
# Unix / Linux / Mac
chmod +x burst.sh
./burst.sh http://localhost:8080

# Windows
burst.bat http://localhost:8080

# Or directly with Python
python burst.py http://localhost:8080
```

### Benchmark Workflow:
1. Validates `/health/ready` probe.
2. Creates a fresh show with 100 seats.
3. Fires **500 concurrent buyers storming seat `A1`** simultaneously.
4. Verifies **exactly 1 winner (201 Created)**, **499 clean declines (409 Conflict)**, and **0% 5xx server errors**.
5. Tests per-user limit concurrency (greedy user firing 15 parallel bookings on a limit=4 show).
6. Tests 50 concurrent retries of the same idempotency key and rejects altered payload.
7. Performs seat cancellation and verifies instant re-bookability.
8. Validates the reconciliation invariant (`available + held + confirmed == total_seats`).
9. Dumps live Prometheus metrics.

---

## 🌐 Deploy to Render / Railway / Fly.io

### Deploy to Render via Blueprint:
1. Push repository to GitHub.
2. Link repo in [Render Dashboard](https://dashboard.render.com).
3. Render automatically picks up `render.yaml` to provision the PostgreSQL instance and Web Service.
4. Health check path is `/health/ready`.

---

## 📖 Deep Technical Architecture

See [WRITEUP.md](WRITEUP.md) for detailed analysis of:
- Atomic decision mechanism and why read-then-write fails.
- Mathematical deadlock prevention proof for multi-seat requests.
- Idempotency caching and payload mismatch detection.
- CAP theorem trade-offs and network partition behavior.
- Real-time Prometheus metrics & 2:00 AM pager alerts.
- AI tool usage disclosure (directed vs. decided).
