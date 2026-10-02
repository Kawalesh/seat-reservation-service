# Engineering Write-Up: Seat Reservation at Scale
**Backend Engineering — Paytm Money Evaluation**

---

## 1. The Atomic Decision Mechanism & Concurrency Control

### 1.1 Why Read-Then-Write Fails
A naive reservation pipeline typically performs:
1. `SELECT * FROM seats WHERE seat_number = 'A12' AND status = 'AVAILABLE';`
2. Application checks `if (seat.isAvailable())`
3. `UPDATE seats SET status = 'CONFIRMED' WHERE id = seat.id;`

Under a 500-thread concurrent stampede for seat `A12`, all 500 threads execute step 1 before any thread commits step 3 under standard `READ COMMITTED` isolation. As a result, all 500 threads observe the seat as "available", issue the update, and double-sell (or multi-sell) the seat.

### 1.2 Our Atomic Decision Mechanism
Our architecture pushes the atomic decision directly into the ACID storage engine utilizing two complementary layers:

1. **Deterministic Lexicographical Row-Level Locking (`PESSIMISTIC_WRITE`):**
   ```sql
   SELECT s FROM Seat s 
   WHERE s.showId = :showId AND s.seatNumber IN :seatNumbers 
   ORDER BY s.seatNumber ASC
   ```
   With JPA / Hibernate `LockModeType.PESSIMISTIC_WRITE`, this translates to PostgreSQL `SELECT ... FOR UPDATE`.
   The very first transaction to acquire the row lock on `A12` evaluates its current persisted state:
   - If `status == AVAILABLE`, it transitions the status to `CONFIRMED`, inserts the reservation, and commits.
   - The remaining 499 transactions block at the database level on the row lock. Upon waking sequentially, each immediately reads the newly committed `status == CONFIRMED`.
   - The application checks `seat.getStatus() != SeatStatus.AVAILABLE`, records the `reservations_declined_total{reason="seat_taken"}` metric, and immediately returns a clean domain `409 Conflict` (`SEAT_ALREADY_TAKEN`).
   - Zero database rollbacks or deadlocks occur for single-seat contention; losers receive clean 4xx responses, achieving **0% 5xx server errors**.

2. **Multi-Seat Deadlock Avoidance (Strict Monotonic Ordering):**
   A classic dining-philosophers deadlock happens when:
   - Transaction 1 requests `["A1", "A2"]` (locks `A1`, waits for `A2`)
   - Transaction 2 requests `["A2", "A1"]` (locks `A2`, waits for `A1`)
   In PostgreSQL, this results in an error `40P01 (deadlock detected)`.
   
   **Our Solution:** Before executing any database operation or row locking, the service normalizes, de-duplicates, and **sorts seat identifiers lexicographically**:
   ```java
   List<String> requestedSeats = request.getSeats().stream()
           .map(String::trim)
           .filter(s -> !s.isEmpty())
           .distinct()
           .sorted()
           .toList();
   ```
   Regardless of the order supplied in the client request payload, every transaction locks seats in strictly ascending order (`A1 -> A2`). Because resource acquisition is strictly monotonic across all concurrent transactions, **a cyclic wait condition is mathematically impossible**.

3. **Per-User Booking Limit Serialization:**
   To prevent a single user from firing 20 concurrent requests and bypassing the `per_user_limit` (e.g., limit = 4):
   - In PostgreSQL, we acquire a transaction-scoped advisory lock:
     ```sql
     SELECT pg_advisory_xact_lock(hashtext(:showId || ':' || :userId))
     ```
   - In the JVM, we stripe reentrant locks per `(showId + ":" + userId)`.
   - By running inside a `TransactionTemplate` bounded within the user monitor, limit checks (`countActiveSeatsForUser`) and reservations are fully committed before the next concurrent request from the same user is processed. Requests exceeding the quota receive `409 Conflict` (`PER_USER_LIMIT_EXCEEDED`).

---

## 2. Idempotency Architecture

### 2.1 Storage & Schema
Idempotency state is persisted in the `idempotency_records` table:
```sql
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    show_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    reservation_id VARCHAR(64) NOT NULL,
    response_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT idx_idemp_user_key UNIQUE (user_id, idempotency_key)
);
```

### 2.2 Exactly-Once Enforcement Flow
1. **Hash Generation:** When a reservation request arrives, we calculate a deterministic SHA-256 hash of the canonical request:
   `request_hash = SHA256(show_id + ":" + sorted_seats)`
2. **Key Resolution:** The key can be passed via HTTP header `Idempotency-Key` or JSON body `idempotency_key` (header takes precedence).
3. **Lookup & Matching:**
   - **Case A (Exact Replay):** If `idempotency_key` exists for this user and `stored_hash == incoming_hash`, the original JSON response payload is deserialized and returned with `200 OK` (or `201 Created`). Metric `reservations_replayed_total` increments. No database state mutates.
   - **Case B (Payload Mismatch):** If `idempotency_key` exists but `stored_hash != incoming_hash` (e.g. buyer retries the same key asking for seat `C2` instead of `C1`), the request is rejected with `409 Conflict` (`IDEMPOTENCY_PAYLOAD_MISMATCH`). Metric `reservations_declined_total{reason="idempotency_mismatch"}` increments.
   - **Case C (First Execution):** The reservation transaction executes atomically, stores the response JSON in `idempotency_records`, and commits.
4. **Concurrent Replays of Same Key:** A JVM striped lock on `(userId + ":" + idempotencyKey)` ensures parallel retries of an in-flight key queue until the first commit finishes, returning the identical cached payload to all racers without redundant processing.

---

## 3. Holds & Expiry Architecture

### 3.1 Model Implemented
We implemented an **Instant Atomic Confirmation + Explicit Cancellation** model (`POST /reservations/{id}/cancel`), with support for temporary holds.
- Direct reservation returns `status: confirmed` (matching requirement 2's sample payload).
- Cancellation (`POST /reservations/{id}/cancel`):
  - Enforces token-derived ownership: only `reservation.user_id == authenticated_user` can cancel (imposters receive `403 Forbidden`).
  - Idempotent cancellation: cancelling an already cancelled reservation returns `200 OK`.
  - Atomically locks the associated seats in sorted order, updates their status from `CONFIRMED` to `AVAILABLE`, and marks the reservation `CANCELLED`.
  - The freed seats are immediately bookable by other users.

### 3.2 Time-Boxed Holds vs. Immediate Confirmation Trade-offs
- **Time-Boxed Hold Model:**
  - *Pros:* Simulates ticketing checkout cart (e.g., 5-minute hold while paying).
  - *Cons:* Requires active background sweeper or Redis TTL keys. Under high churn, holds lock inventory from serious buyers. Zombie holds cause artificial inventory starvation.
- **Immediate Confirmation with Webhooks/Cancellation:**
  - *Pros:* Zero inventory starvation; linear state machine; strictly deterministic reconciliation invariant `available + held + confirmed == total_seats`.

---

## 4. Consistency vs. Availability Under Network Partitions (CAP)

### 4.1 Strict Consistency (CP) Over Availability (AP)
In seat reservations and financial ledgers, **Consistency is non-negotiable**.
Selling the same physical concert seat to two paying customers is a real-world catastrophe (overbooking a flight or concert hall results in venue breach and severe brand damage).

Therefore, our architecture chooses **CP (Consistency & Partition Tolerance)**:
- In the event of a network partition between application pods and the primary database, the service **fails closed**:
  - The readiness probe (`/health/ready`) executes `SELECT 1`. If the database is unreachable, it immediately transitions to `503 Service Unavailable`.
  - Upstream load balancers (or Kubernetes Ingress / Cloudflare) drop traffic to unhealthy pods.
- If database replicas lag or split-brain threatens the cluster, transactions must route strictly to the PostgreSQL Primary using synchronous replication (`synchronous_commit = on`).
- We intentionally sacrifice availability for partitioned nodes to guarantee the **absolute invariant: zero double-bookings**.

---

## 5. Observability & 2:00 AM Paging Alerts

### 5.1 Real-Time Metrics (`/actuator/prometheus` & `/metrics`)
- `reservations_confirmed_total` (Counter): Total seats successfully booked.
- `reservations_declined_total{reason="..."}` (Counter with labels):
  - `reason="seat_taken"`: Contention on hot seats.
  - `reason="per_user_limit"`: Buyers attempting to exceed max seats.
  - `reason="idempotency_mismatch"`: Fraudulent or bugged client retry.
- `reservations_replayed_total` (Counter): Exact-once idempotent retries served from cache.
- `seats_available{show_id="..."}` (Gauge): Current unsold inventory.
- `seats_confirmed{show_id="..."}` (Gauge): Current sold inventory.
- `seats_held{show_id="..."}` (Gauge): Inventory currently held.

### 5.2 What Would Page an On-Call Engineer at 2:00 AM?
1. **`ReconciliationInvariantBroken` (P0 / Critical):**
   - Alert rule: `seats_available + seats_held + seats_confirmed != total_seats` for any show.
   - Meaning: Database corruption, unhandled transaction rollback, or silent phantom write. Must page immediately.
2. **`Http5xxRateSpike` (P1 / Critical):**
   - Alert rule: `rate(http_server_requests_seconds_count{status=~"5.."}[1m]) > 0.001`.
   - In our design, all contentious outcomes are 4xx (`409 Conflict`). A 5xx indicates unhandled exceptions, database connection pool exhaustion, or thread starvation.
3. **`HikariPoolConnectionTimeout` (P1):**
   - Alert rule: `hikaricp_connections_timeout_total > 5 within 2m`.
   - Meaning: Database connection pool saturation; lock wait times are exceeding timeout thresholds.
4. **`ReadinessProbeFailing` (P1):**
   - Alert rule: `/health/ready` returning non-200 for > 30 seconds across > 50% of pods.

---

## 6. AI Usage Disclosure (Directed vs. Decided)

In accordance with Paytm Money's guidelines to use AI tools well and disclose honestly:

- **What was Decided by the Engineer:**
  - Selected PostgreSQL row-level pessimistic locking (`SELECT ... FOR UPDATE`) with **deterministic lexicographical ordering** to eliminate deadlock cycles.
  - Designed the two-phase idempotency mechanism storing request SHA-256 hashes alongside cached response bodies.
  - Designed the hybrid per-user limit serialization using database advisory transaction locks (`pg_advisory_xact_lock`) coupled with JVM lock striping.
  - Decided on Java 21 with Project Loom Virtual Threads (`spring.threads.virtual.enabled=true`) for non-blocking I/O throughput under heavy concurrent request bursts.
  - Defined the strict invariant rule: All domain contention (seat taken, limit hit, idempotency mismatch) must map to `409 Conflict`, never `500`.

- **What was Directed to the AI Assistant:**
  - Generating standard boilerplate Spring Data JPA repository interfaces and Jackson DTO classes.
  - Generating the multi-stage Alpine Dockerfile and Prometheus configuration file.
  - Generating the multithreaded Python burst benchmark client script (`burst.py`) with concurrent futures.
  - Generating standard unit test scaffolding and verifying surefire reports.

---

## 7. What Would We Do Next in Production?

1. **Distributed Caching with Redis Cluster:**
   - Place a Redis read-cache with TTL for `GET /shows/{id}` and `/health` to eliminate database read load during flash sales.
   - Use Redis Bloom filters for rapid pre-rejection of non-existent shows.
2. **Distributed Rate Limiting:**
   - Implement token-bucket rate limiting (via Bucket4j or Envoy) per IP and per `user_id` at the API Gateway level to shield the core reservation engine from volumetric DDoS.
3. **Event-Driven Outbox Pattern:**
   - Persist reservation events to a transactional outbox table in the same DB transaction, followed by Debezium CDC streaming to Apache Kafka for payment dispatch, ticket PDF generation, and SMS notifications.
4. **Zero-Downtime Blue/Green Deployments:**
   - Implement Flyway database migrations with backward-compatible schema changes (Expand/Contract pattern) and Kubernetes rolling updates.
