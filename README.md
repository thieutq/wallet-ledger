# Wallet Ledger

A double-entry wallet ledger backend (Java 17 + Spring Boot): credit/debit/hold/capture/void, direct player-to-player transfers, a signup bonus, and refunds — all crash-safe and race-safe by construction, not by convention.

## Core Concept

- Every `Transfer` writes exactly two `Entry` records (one debit-signed, one credit-signed) across two accounts.
- The system enforces `sum(entries.amount) == 0` per transfer, both at the application layer and as a DB-level backstop — or the write is rejected.
- Balances (`accounts.balance`/`held`) are **stored, maintained columns**, updated in the same transaction as the entries — not derived via `SUM(entries)` on every read. O(1) reads on the hot balance-check path; `entries` is kept for history/audit, not for computing the current balance.
- All entries are append-only. Corrections happen via **reversal** (`refund`), never `UPDATE`/`DELETE` of the original.

## How to run

**Prerequisites**: Docker + Docker Compose, or JDK 17 + Maven (the repo ships `./mvnw`, so a local Maven install isn't required).

**Full stack** (Postgres + app, Flyway migrates automatically):
```bash
docker-compose up -d
```
API is then live at `http://localhost:8080`, Swagger UI at `http://localhost:8080/swagger-ui.html`.

![Swagger UI](docs/images/swagger-ui.png)

**App only, Postgres in Docker**:
```bash
docker-compose up -d postgres
./mvnw spring-boot:run
```

**Tests**:
```bash
./mvnw test                                        # unit — no DB, no Docker
./mvnw failsafe:integration-test failsafe:verify   # integration — real Postgres via Testcontainers
```
If Testcontainers can't reach Docker on your machine, use the bundled escape hatch instead (no code changes needed):
```bash
docker compose -f docker-compose.test.yml up -d
IT_DB_URL=jdbc:postgresql://localhost:5433/wallet_ledger_test ./mvnw failsafe:integration-test failsafe:verify
docker compose -f docker-compose.test.yml down
```

**Try it** — a `root`/`123456` admin and a seeded API key (`dev-service-api-key-change-me`) are created on first startup:
```bash
# Register + log in as a player (first login also grants a 100 COINS signup bonus)
curl -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "password123"}'
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "password123"}'   # -> { token, user }

# Player self-service (Authorization: Bearer <token>)
curl http://localhost:8080/api/v1/players/me/balance -H "Authorization: Bearer <token>"

# System/admin money movement (X-Api-Key + Idempotency-Key required)
curl -X POST http://localhost:8080/api/v1/ledger/debit -H "X-Api-Key: dev-service-api-key-change-me" \
  -H "Idempotency-Key: <uuid>" -H "Content-Type: application/json" \
  -d '{"account_id": "<id>", "amount": 100, "type": "PURCHASE"}'
```
See [API Endpoints](#api-endpoints) below for the full surface, or [docs/01-api-list.md](docs/01-api-list.md) for exact request/response shapes.

## Tech Stack

- **Java 17**
- **Spring Boot 3.3.5**
- **Spring Data JPA**: persistence layer (Hibernate as the JPA provider)
- **PostgreSQL**: primary datastore
- **Flyway**: versioned schema migrations — hand-written DDL is the source of truth; Hibernate only validates (`ddl-auto=validate`)
- **Spring Security**: JWT for players, API-Key (`X-Api-Key`) for system/service callers, `@PreAuthorize` per endpoint
- **Lombok**: boilerplate reduction
- **Spring Validation**: request-level input validation
- **MapStruct**: compile-time DTO ↔ Entity mapping
- **springdoc-openapi**: Swagger UI / OpenAPI documentation
- **Testcontainers**: real Postgres in integration tests
- **Maven**: build tool

## Architecture

The codebase is organized by domain (bounded context), not by technical layer:

```
com.walletledger
├── domain
│   ├── user          # User entity, repository, AuthController/Service, UserController, dto/, mapper/
│   ├── account       # Account entity, AccountRepository (pessimistic row locking)
│   ├── ledger        # Transfer/Entry/Hold, LedgerService/Controller, dto/, mapper/
│   ├── player        # PlayerController (balance, transaction history)
│   ├── reward        # RewardProgram lookup (signup bonus)
│   └── outbox        # OutboxEvent, publisher, @Scheduled poller
├── infrastructure/security
│   ├── jwt           # JwtService, JwtAuthenticationFilter, JwtProperties
│   └── apikey        # ApiClient, ApiKeyAuthFilter (X-Api-Key -> ROLE_SERVICE)
├── config            # SecurityConfig, SchedulingConfig, OpenApiConfig, seeders
├── shared            # GlobalExceptionHandler, ApiResponse<T> envelope
└── WalletLedgerApplication
```

### Domain Model

| Entity | Description |
|---|---|
| `User` | Identity that authenticates and owns a wallet `Account`. Role (`CUSTOMER`/`ADMIN`/`AUDITOR`) and status (`ACTIVE`/`SUSPENDED`). |
| `Account` | A wallet. `PLAYER` (one per user) or `SYSTEM` (the ledger's treasury counterparty, exempt from the non-negative balance check). Stores `balance` and `held`. |
| `Transfer` | One money-movement event — exactly one per credit/debit/capture/refund, with a `type`, `status`, `reference_id`, and `idempotency_key`. |
| `Entry` | A signed debit/credit line against an account, tied to a `Transfer`. Always created in balanced pairs. Immutable — no update/delete path. |
| `Hold` | A two-phase reservation against an account's `held` balance — `capture`s into a real debit, or `void`s/expires back to available. |
| `RewardProgram` | A named, amount-bearing bonus (e.g. `signup-bonus-v1`), credited through the same `LedgerService.credit` path as everything else. |
| `OutboxEvent` | A side-effect record (audit/notification) written in the same transaction as its business write; drained by a scheduler. |

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/auth/register` | Register a new player |
| `POST` | `/api/v1/auth/login` | Authenticate, returns a JWT (first login also grants the signup bonus) |
| `GET` | `/api/v1/users/me` | Get the authenticated user's profile |
| `GET` | `/api/v1/players/me/balance` | Get the authenticated player's balance (available/hold/total) |
| `GET` | `/api/v1/players/me/transactions` | Get the authenticated player's paginated transaction history |
| `POST` | `/api/v1/ledger/credit` | Credit funds into a player's wallet *(system/admin, `Idempotency-Key`)* |
| `POST` | `/api/v1/ledger/debit` | Debit funds from a player's wallet, rejects if insufficient *(system/admin, `Idempotency-Key`)* |
| `POST` | `/api/v1/ledger/hold` | Reserve funds without moving them, two-phase debit *(system/admin, `Idempotency-Key`)* |
| `POST` | `/api/v1/ledger/capture` | Confirm a hold, debiting the reserved funds *(system/admin, `Idempotency-Key`)* |
| `POST` | `/api/v1/ledger/void` | Cancel a hold, releasing the reserved funds *(system/admin)* |
| `POST` | `/api/v1/ledger/refund` | Refund a completed transfer, fully or partially *(system/admin, `Idempotency-Key`)* |
| `POST` | `/api/v1/ledger/transfer` | Transfer funds directly between two player wallets, no `SYSTEM` account involved *(system/admin, `Idempotency-Key`)* |

## Data Flow — Peer-to-peer Transfer

The most representative case: money moving between two players. Every other ledger operation (`credit`/`debit`/`hold`/`capture`/`refund`) follows the same shape — validate → lock in order → re-check → mutate → commit — with `SYSTEM` standing in as one side instead of two real players.

```
POST /api/v1/ledger/transfer
Headers: X-Api-Key: <service-key>, Idempotency-Key: k1
Body:    { "from_account_id": "alice", "to_account_id": "bob", "amount": 250 }

Client
  │
  ▼
LedgerController.transfer()
  │  @Valid — amount > 0, both account ids present → 400 if not
  ▼
LedgerService.transfer()
  │
  ├── findByIdempotencyKey("k1") — already exists? ──▶ return that Transfer as-is, nothing re-run
  │         │ not found
  ▼
BEGIN transaction (TransactionTemplate)
  │
  ├── from_account_id == to_account_id? ──────────────▶ 400
  ├── resolve currency (defaults to COINS)
  │
  ├── lock both accounts — SELECT ... FOR UPDATE, sorted by account id
  │     (not "from, then to" — id order, so two transfers racing in
  │     opposite directions can't deadlock each other)
  │
  ├── either account is SYSTEM? ───────────────────────▶ 400 (use credit/debit instead)
  ├── sender's available balance (balance − held) < amount? ──▶ 409
  │
  ├── insert transfer (type=TRANSFER, idempotency_key='k1')
  │     └── UNIQUE(idempotency_key) collision (concurrent retry) ──▶ catch,
  │           re-fetch by key, return the original — same 201, no double-move
  ├── insert entry (alice, -250)
  ├── insert entry (bob,   +250)
  ├── update accounts.balance (both)
  └── insert outbox_events (status=PENDING, type=TRANSFER_COMPLETED)
  │
  ▼
COMMIT
  │  deferred trigger re-checks: this transfer's entries SUM(amount) = 0,
  │  or the whole COMMIT fails — DB-level backstop behind the checks above
  ▼
201 Created ──▶ Client

Meanwhile, asynchronously:
OutboxEventScheduler (@Scheduled, every 5s)
  │
  ├── SELECT ... WHERE status='PENDING' FOR UPDATE SKIP LOCKED
  └── mark PROCESSED
        (no real consumer wired up yet — a message bus here, e.g. Kafka,
         is future work; see Task List)
```

See [LedgerController.java](src/main/java/com/walletledger/domain/ledger/LedgerController.java) and [LedgerService.java](src/main/java/com/walletledger/domain/ledger/LedgerService.java) for the actual code this diagram traces.

## Correctness & Concurrency Guarantees

- **Balanced-transaction invariant**: a transfer's entries must sum to zero — checked at the application layer, and backstopped by a `DEFERRABLE` DB constraint trigger that re-verifies at `COMMIT`, so even a future application bug can't silently unbalance the books.
- **Idempotency keys**: every mutating `POST /api/v1/ledger/*` call requires an `Idempotency-Key` header, backed by a DB `UNIQUE` constraint — a retried request returns the original result instead of double-processing.
- **Atomicity**: each operation (credit/debit/hold/capture/void/refund/transfer) is one transaction — accounts locked in a fixed `id`-sorted order (deadlock-free), re-checked, then mutated; if any step fails, nothing commits.
- **Append-only entries**: no `UPDATE`/`DELETE` path on `entries`/`transfers`. Corrections happen via `POST /api/v1/ledger/refund`, which creates new offsetting entries rather than touching the original transfer.
- **Database-level constraints**: `CHECK` constraints (non-negative balance except `SYSTEM`, `held <= balance`, valid enum values) and `UNIQUE` (idempotency keys) act as a second line of defense beyond the application-level checks.
- **Lock ordering, not lock avoidance**: accounts are always locked in a fixed `id`-sorted order, regardless of which one is logically "from" or "to" — this, not the locks themselves, is what prevents a classic deadlock (transaction A locking X then Y while B locks Y then X at the same time).
- **`TransactionTemplate`, not `@Transactional` self-invocation**: a duplicate `Idempotency-Key` aborts the whole transaction at the DB level, so the fallback "fetch the existing row" has to run in a *fresh* transaction — self-invocation can't give you that, since Spring's proxy is bypassed on an internal method call.

The signup bonus reuses this same idempotency mechanism rather than a separate "already granted" flag: login always calls `credit(..., idempotencyKey="signup-bonus:"+userId)`, so only the first call ever inserts.

## Design decisions

**Double-entry, not a mutable balance column.** Every money movement writes one `Transfer` + two signed `Entry` rows — never an in-place `UPDATE`. This makes "the audit trail disagrees with the balance" structurally impossible rather than a rule someone has to remember.

**Balance is stored, not derived.** Chosen over computing `SUM(entries)` on every read: `GET /players/me/balance` needs an O(1) read on a likely hot path. Drift risk between the stored balance and the entry history is mitigated by updating both in the same transaction (see Correctness guarantees above).

**`SYSTEM` accounts as the ledger's counterparty.** Double-entry needs two sides even for "credit from nowhere" (a bonus) or "debit to nowhere" (a purchase) — a `SYSTEM` treasury account is that counterparty.

**`holds` for two-phase reservations.** A hold raises `held` without touching `balance`; `capture` settles it into a real debit, `void`/expiry releases it back — so "reserve now, charge later" never risks double-spending the same funds elsewhere.

**Refund is a reversal, never an edit.** Refunding creates a new offsetting `Transfer(type=REFUND)` pointing back at the original via `reference_id`; the original row is never touched. "How much has already been refunded" is computed on demand, not stored — one aggregate query per refund, which is fine since refunds aren't a hot path.

**Peer-to-peer transfer reuses the same machinery, not a special case.** `credit`/`debit` always resolve `SYSTEM` as the implicit other side; `transfer` is the same locking/idempotency/outbox code path with both accounts supplied explicitly instead, rejecting `SYSTEM` on either side (that's what `credit`/`debit` are for) and self-transfers.

**Trade-off accepted throughout**: 2+ inserts and an extra table per operation instead of a single `UPDATE` — the requirement here is auditability and correctness under concurrency, not maximum raw throughput. Full reasoning and rejected alternatives: [docs/04-design-decisions.md](docs/04-design-decisions.md).

## Testing approach

Two tiers, run separately in CI (unit first, fails fast; integration only if unit passes):
- **Unit** (`*Test.java`, Surefire, no DB): `JwtServiceTest`, `AuthServiceTest` — pure logic, collaborators mocked.
- **Integration** (`*IT.java`, Failsafe, real Postgres): `AuthFlowIT` (register/login/bonus/auth), `LedgerIT` (credit/debit/hold/capture/void/refund/transfer/balance/history/idempotency/outbox/hold-expiry/the DB trigger backstop).

**The concurrent debit case** ([`LedgerIT.onlyOneOfTwoConcurrentDebitsForTheFullBalanceSucceeds`](src/test/java/com/walletledger/domain/ledger/LedgerIT.java)) is the one most worth calling out: an account with balance 100 gets two debits of 100 fired at once from two threads. Without the row lock, both could read balance=100 and both succeed, double-spending the account into the negative. The test asserts exactly one request gets `201` and the other gets `409`, and the final balance is `0`, not negative — proving the lock, not just the application-level check, is what's actually enforcing correctness under a real race. A second concurrency test (`repeatedCreditWithSameIdempotencyKeyConcurrentlyAppliesOnce`) does the same for idempotency: 5 threads fire the identical request simultaneously, and exactly one transfer is ever created.

Everything else follows the same real-Postgres-over-mocks philosophy: the insufficient-balance rejection, the paginated history, the hold-expiry scheduler, and the DB trigger are all exercised against an actual database, not stubbed.

## Assumptions & limitations

- **`root`/`123456`** and the seeded service API key (`dev-service-api-key-change-me`) are known dev-only credentials — must be rotated before any real deployment; no rotation tooling is provided.
- **Single currency** (`COINS`) throughout — multi-currency would mean per-currency `SYSTEM` accounts and FX handling, neither designed here.
- **No admin/audit-log HTTP surface** — every mutation *is* permanently recorded (that's the point of the ledger), but there's no `/admin/*` API to browse it; today that's direct DB/SQL access.
- **Outbox has no real consumer** — `OutboxEventScheduler` marks events `PROCESSED` as a stub extension point; wiring it to an actual message bus/notification service is future work.
- **Refund isn't restricted to `PURCHASE`** by type — any `COMPLETED`, non-`REFUND` transfer can be reversed. This is intentionally general, but means a refund whose reversed direction lands on a `PLAYER` account (not the usual `SYSTEM`) is subject to the same insufficient-balance check a debit gets.
- **Testcontainers can be unreliable on some local Docker Desktop setups** (seen during development on Windows) — CI is unaffected, and the `docker-compose.test.yml` / `IT_DB_URL` escape hatch above covers local dev when that happens.

## Task List

### Done
- [x] User registration & JWT login, role-based access (player JWT vs. system/service API key)
- [x] Double-entry ledger core: credit / debit / hold / capture / void
- [x] Player balance and paginated transaction history endpoints
- [x] Idempotency via DB `UNIQUE` constraint, verified sequentially and concurrently
- [x] Pessimistic row locking with deadlock-free ordering, verified with a concurrent-debit race test
- [x] Transactional outbox for audit/notification side-effects
- [x] Hold-expiry background scheduler
- [x] DB-level constraint trigger backstopping the double-entry invariant
- [x] Signup bonus reward program
- [x] Transaction refund, full and partial
- [x] Direct player-to-player transfer (no `SYSTEM` account involved)
- [x] Dockerized app + Postgres (`docker-compose`), Flyway-managed schema
- [x] Unit + integration test suites (Surefire/Failsafe, Testcontainers)
- [x] CI/CD pipeline (GitHub Actions), two-tier and path-filtered
- [x] Local test escape hatch (`docker-compose.test.yml` + `IT_DB_URL`) for environments where Testcontainers is unreliable
- [x] Swagger / OpenAPI documentation

### Planned
- [ ] Load testing with k6 — throughput/latency under realistic concurrency, not just correctness
- [ ] Redis caching layer for read-heavy endpoints (balance/history) to reduce DB load at scale
- [ ] Kafka (or another broker) as the real outbox consumer, replacing the stub scheduler
- [ ] Admin/audit-log HTTP surface (`/admin/*` — user management, audit log browsing)
- [ ] Multi-currency support (per-currency `SYSTEM` accounts, FX handling)
- [ ] Observability: structured logging, metrics/tracing (e.g. Micrometer + Prometheus/Grafana)
- [ ] Rate limiting / abuse protection on public auth endpoints
- [ ] API key rotation & per-client scoping, instead of one shared service key
- [ ] Credential rotation tooling for the seeded admin account

## AI Tooling Notes

This project's schema, API design, architecture decisions, and implementation were developed with heavy use of **Claude Code** (Anthropic) throughout — including reviewing early design choices for correctness, comparing against reference implementations, debugging a CI-only Testcontainers failure, and writing the Java source and tests. Design trade-offs and their reasoning are recorded as they were made in [docs/04-design-decisions.md](docs/04-design-decisions.md), not reconstructed after the fact.
