# Test Plan / Checklist

Granular, per-phase test cases (IDs, Given/When/Then, traced back to the take-home brief). [03-implementation-plan.md](03-implementation-plan.md) links here instead of repeating this list.

## Requirement legend

| Code | Take-home requirement |
|---|---|
| MC1–MC6 | Mandatory wallet operations: credit, debit, reject-insufficient-debit, return balance, paginated history, permanent record — §3.1 |
| SC1 | Same request not applied twice (idempotency) — §3.2 |
| SC2 | Concurrent requests don't corrupt state — §3.2 |
| SC3 | A failed operation leaves no partial update — §3.2 |
| SC4 | Invalid input handled clearly — §3.2 |
| BF-Reservation | Bonus: Reservation of Funds — §4.1 |
| BF-Refund | Bonus: Transaction Refund — §4.1 |
| BF-DomainEvents | Bonus: Domain Events — §4.2 |
| Infra | Supports the above but isn't itself a graded capability (auth plumbing, seeding, tooling) |

Test type: **Unit** = `*Test.java`, Surefire, no Spring context/DB, Mockito. **Integration** = `*IT.java`, Failsafe, full Spring context + Testcontainers Postgres.

---

## Phase 1 — Foundation: `users` + auth

### Unit

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P1-U1 | `JwtService` configured with a secret + expiry | sign a token for a user id, then parse it back | subject and claims round-trip correctly | Infra |
| P1-U2 | a token signed with an expiry already in the past | validate it | rejected as expired | Infra |
| P1-U3 | `AuthService` with `UserRepository`/`PasswordEncoder` mocked | `register()` called with a new username | the password passed to `save()` is hashed, never the plaintext | SC4 |
| P1-U4 | mocked `AuthService`; a stored user with a known password hash | `login()` with the wrong password | rejected, no JWT issued | SC4 |
| P1-U5 | mocked `AuthService`; stored user has `status = SUSPENDED` | `login()` with the correct password | rejected regardless of password correctness | SC4 |

### Integration (`AuthFlowIT`)

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P1-I1 | clean DB | `POST /api/v1/auth/register` valid username/password | `201`, user persisted with `role=CUSTOMER`, `status=ACTIVE` | Infra |
| P1-I2 | a registered user | `POST /api/v1/auth/login` correct credentials | `200`, JWT returned | Infra |
| P1-I3 | a valid JWT | `GET /api/v1/users/me` with `Authorization: Bearer ...` | `200`, correct `id`/`username`/`role`/`status`; no password hash in the body | SC4 |
| P1-I4 | no `Authorization` header | `GET /api/v1/users/me` | `401` | SC4 |
| P1-I5 | a username already registered | `POST /api/v1/auth/register` with the same username | `409` | SC4 |
| P1-I6 | — | `POST /api/v1/auth/register` with blank/missing `username` or `password` | `400` with a clear validation message | SC4 |
| P1-I7 | — | `POST /api/v1/auth/register` with a password below the minimum length | `400` | SC4 |
| P1-I8 | app started fresh, `AdminUserSeeder` ran | `POST /api/v1/auth/login` with `root`/`123456` | `200`, `role=ADMIN` | Infra (seed sanity check) |

---

## Phase 1.5 — Project tooling: Docker, tests & CI/CD

Not business-logic test cases — a one-time verification checklist:

- [ ] `docker-compose up -d` from a clean checkout brings up Postgres **and** the app; the app connects successfully.
- [ ] Flyway applies `V1__users.sql` automatically on first startup (check `flyway_schema_history`).
- [ ] `./mvnw test` runs only `*Test.java` (Surefire), finishes without touching any DB.
- [ ] `./mvnw failsafe:integration-test failsafe:verify` runs only `*IT.java` (Failsafe), starts a Testcontainers Postgres, passes.
- [ ] A commit touching `src/**` triggers `.github/workflows/ci.yml` and it goes green.
- [ ] A commit touching only `docs/**` does **not** trigger the workflow (path filter working).
- [ ] Root `README.md`'s "How to run" steps work verbatim on a machine that has never built the project before.

---

## Phase 2 — Ledger core

### Core operations (Integration)

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P2-I1 | player account, balance=0 | `POST /ledger/credit` amount=100, type=BONUS | balance=100; 1 `Transfer` + 2 `Entry` rows created | MC1, MC6 |
| P2-I2 | player account, balance=100 | `POST /ledger/debit` amount=40, type=PURCHASE | `200`, balance=60 | MC2, MC6 |
| P2-I3 | player account, balance=30 | `POST /ledger/debit` amount=40 | `409`, balance still 30 | MC3 |
| P2-I4 | `account_id` that doesn't exist | `POST /ledger/credit` with that id | `404`, no `Transfer`/`Entry` created | SC4 |
| P2-I5 | — | `POST /ledger/credit` amount = 0 or negative | `400`, rejected before touching the DB | SC4 |
| P2-I6 | player account, balance=100 | `POST /ledger/hold` amount=40 | `held`=40, balance still 100, available (balance−held)=60 | BF-Reservation |
| P2-I7 | active hold from P2-I6 | `POST /ledger/capture` that hold | balance=60, `held`=0, a `Transfer(type=HOLD_CAPTURE)` created referencing the hold | BF-Reservation |
| P2-I8 | active hold | `POST /ledger/void` that hold | `held` returns to 0, balance unchanged, no `Transfer` created | BF-Reservation |
| P2-I9 | balance=50, `held`=30 (available=20) | `POST /ledger/hold` amount=25 | `409` (exceeds available), `held` unchanged at 30 | MC3-equivalent |
| P2-I10 | a player with several transfers | `GET /players/me/balance` | Available/Hold/Total exactly match balance/held/balance | MC4 |
| P2-I11 | a player with N transfers spanning 3 pages | `GET /players/me/transactions?page=1..3&size=10` | correct contents and total count per page, no duplicate/missing rows across pages | MC5 |

### Idempotency (Integration)

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P2-I12 | — | `POST /ledger/credit` 5× **sequentially**, same `Idempotency-Key` | exactly 1 `Transfer` row; balance changed exactly once | SC1 |
| P2-I13 | — | `POST /ledger/credit` fired **concurrently** from 5 threads, same `Idempotency-Key` | exactly 1 `Transfer` row; all 5 responses carry the same transfer id | SC1, SC2 |
| P2-I14 | — | same `Idempotency-Key` reused between a credit call and a debit call | second call rejected or returns the first result — no ambiguous double-processing | SC1 |

### Concurrency (Integration — the take-home's named "concurrent debit case")

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P2-I15 | account balance=100 | 2 concurrent `debit(100)` requests, different `Idempotency-Key`s | exactly 1 succeeds, 1 gets `409`, final balance=0 | MC3, SC2 |
| P2-I16 | account balance=100 | 10 concurrent `debit(100)` requests, different keys | exactly 1 succeeds, final balance=0, never negative | SC2 |
| P2-I17 | account balance=100 | concurrent `credit(+50)` and `debit(-30)` | final balance=120 regardless of execution order (no lost update) | SC2, SC3 |

### Outbox & hold expiry (Integration)

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P2-I18 | — | a successful credit/debit | an `outbox_events` row is created in the same transaction | BF-DomainEvents |
| P2-I19 | a `PENDING` outbox row | `OutboxEventScheduler` runs | row flips to `PROCESSED` | BF-DomainEvents |
| P2-I20 | a hold, `status=active`, `expires_at` in the past | `HoldExpiryScheduler` runs | flips to `expired`; `held` released back to available balance | BF-Reservation (closes the schema/behavior gap noted in the plan) |
| P2-I21 | a hold, `status=active`, `expires_at` in the future | `HoldExpiryScheduler` runs | untouched, still `active` | BF-Reservation |

---

## Phase 2.5 — Database-level backstop

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P2.5-I1 | — | insert 2 `entries` directly (native query, bypassing `LedgerService`) for one `transfer_id`, deliberately summing to non-zero, in one transaction | `COMMIT` fails with the constraint violation | backstop for MC6/SC3 |
| P2.5-I2 | — | a normal credit/debit through `LedgerService` | commits fine — the trigger doesn't false-positive on the correct path | regression guard |

---

## Phase 3 — Reward program

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P3-I1 | a newly registered user | `login()` once | balance=100; exactly one `Transfer(type=BONUS, reference_id=signup-bonus-v1)` | MC6 |
| P3-I2 | same user | `login()` a 2nd and 3rd time | balance stays 100; still only 1 `BONUS` transfer row | SC1 (idempotency applied to a business flow, not just a raw retried request) |

---

## Phase 4 — Transaction Refund

| ID | Given | When | Then | Req |
|---|---|---|---|---|
| P4-I1 | a completed `PURCHASE` of 100 | `POST /ledger/refund` (no `amount` — full refund) | `200`; both accounts restored to pre-purchase balances; 1 `Transfer(type=REFUND)` created | BF-Refund |
| P4-I2 | a completed `PURCHASE` of 100 | refund 40, then refund 60 | both succeed; sum of refunds = 100; 2nd call correctly computes `remaining=60` | BF-Refund |
| P4-I3 | a completed `PURCHASE` of 100, already refunded 40 | refund 70 (exceeds remaining 60) | `409`, no state change | BF-Refund |
| P4-I4 | a `REFUND` transfer | refund that `REFUND` | `409` | BF-Refund |
| P4-I5 | — | refund a `transfer_id` that doesn't exist | `404` | SC4 |
| P4-I6 | — | the same refund request fired twice with the same `Idempotency-Key` | single refund applied, not double-reversed | SC1 |

---

## Phase 5 — Final README pass & submission

Not test cases — see the **Submission checklist** already in [03-implementation-plan.md — Phase 5](03-implementation-plan.md).
