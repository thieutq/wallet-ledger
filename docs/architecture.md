# Architecture

Single-page orientation: what the system is, how it's laid out, and what happens on a request. For the "why" behind each choice, see [04-design-decisions.md](04-design-decisions.md); for the full endpoint reference, [01-api-list.md](01-api-list.md); for the tool/library list, [02-tech-stack.md](02-tech-stack.md).

## System overview

A double-entry wallet ledger (Java 17 + Spring Boot + PostgreSQL). Every money movement writes one `Transfer` + two signed `Entry` rows that must sum to zero — never an in-place balance `UPDATE`. Balances are stored, maintained columns (not derived from `entries` on every read) for O(1) reads on the hot path, with three independent layers keeping the stored balance honest: the application-level check, a `DEFERRABLE` DB trigger that re-verifies at `COMMIT`, and `ReconciliationService`, which independently recomputes balances from `entries` and catches drift the trigger structurally can't (e.g. a direct `UPDATE accounts.balance` that never touches `entries`).

## Package structure

Organized by domain (bounded context), not by technical layer — each domain owns its entity/repo/service/controller/dto together:

```
com.walletledger
├── domain
│   ├── user          # User entity, repository, AuthController/Service, UserController, dto/, mapper/
│   ├── account       # Account entity, AccountRepository (pessimistic row locking)
│   ├── ledger        # Transfer/Entry/Hold, LedgerService/Controller, dto/, mapper/
│   ├── player        # PlayerController (balance, transaction history)
│   ├── reward        # RewardProgram lookup (signup bonus)
│   ├── outbox        # OutboxEvent, publisher, @Scheduled poller
│   └── reconciliation # independent ledger re-verification, scheduled + on-demand
├── infrastructure/security
│   ├── jwt           # JwtService, JwtAuthenticationFilter, JwtProperties
│   └── apikey        # ApiClient, ApiKeyAuthFilter (X-Api-Key -> ROLE_SERVICE)
├── config            # SecurityConfig, SchedulingConfig, OpenApiConfig, seeders
├── shared            # GlobalExceptionHandler, ApiResponse<T> envelope
└── WalletLedgerApplication
```

## Domain model

| Entity | Description |
|---|---|
| `User` | Identity that authenticates and owns a wallet `Account`. Role (`CUSTOMER`/`ADMIN`/`AUDITOR`) and status (`ACTIVE`/`SUSPENDED`). |
| `Account` | A wallet. `PLAYER` (one per user) or `SYSTEM` (the ledger's treasury counterparty, exempt from the non-negative balance check). Stores `balance` and `held`. |
| `Transfer` | One money-movement event — exactly one per credit/debit/capture/refund/transfer, with a `type`, `status`, `reference_id`, and `idempotency_key`. |
| `Entry` | A signed debit/credit line against an account, tied to a `Transfer`. Always created in balanced pairs. Immutable — no update/delete path. |
| `Hold` | A two-phase reservation against an account's `held` balance — `capture`s into a real debit, or `void`s/expires back to available. |
| `RewardProgram` | A named, amount-bearing bonus (e.g. `signup-bonus-v1`), credited through the same `LedgerService.credit` path as everything else. |
| `OutboxEvent` | A side-effect record (audit/notification) written in the same transaction as its business write; drained by a scheduler. |

## Request flow: peer-to-peer transfer

The most representative case — every other ledger operation (`credit`/`debit`/`hold`/`capture`/`refund`) follows the same shape (validate → lock in order → re-check → mutate → commit), with `SYSTEM` standing in as one side instead of two real players.

```
POST /api/v1/ledger/transfer
Headers: X-Api-Key: <service-key>, Idempotency-Key: k1
Body:    {
           "from_account_id": "0b1e2f3a-4c5d-4e6f-8a9b-1234567890ab",  // alice's wallet
           "to_account_id":   "1c2d3e4f-5a6b-4c7d-8e9f-0987654321ba",  // bob's wallet
           "amount": 250
         }

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
        │
        ▼  ── PLANNED, not implemented yet — see README's Task List ──
      Kafka
        │
        ▼
  Reward / Notification / etc.
```

`from_account_id`/`to_account_id` are `Account.id` — a server-generated UUID string, never a username or a number.

## Correctness & concurrency, in one paragraph

Every operation is one transaction: accounts locked in a fixed `id`-sorted order (deadlock-free, regardless of which one is logically "from"/"to"), re-checked under lock, then mutated. Idempotency is a DB `UNIQUE` constraint on `idempotency_key`, enforced via `TransactionTemplate` rather than `@Transactional` self-invocation (a duplicate key aborts the transaction at the DB level, so the fallback "fetch the existing row" must run in a *fresh* transaction). `ReconciliationScheduler` independently re-verifies the whole ledger hourly, plus on demand via `GET /api/v1/admin/reconciliation`. Full reasoning: [04-design-decisions.md](04-design-decisions.md); the concurrent-debit and idempotency-under-concurrency tests proving this holds live in `LedgerIT.java`.
