# Implementation Plan

Seven phases. Stack/structure: [02-tech-stack.md](02-tech-stack.md). Detailed test cases per phase: [05-test-plan.md](05-test-plan.md) (this file keeps only a one-line pointer per phase to avoid duplicating that checklist).

`audit_logs` and `/admin/*` endpoints are **out of scope** — no phase here implements admin user-management/audit-log.

---

## Phase 1 — Foundation: `users` + auth

Register/login/me end-to-end, one seeded admin account.

**Migration**: `V1__users.sql` — `users` table (`users_role_valid`/`users_status_valid` CHECK constraints), from `migrations/0001_init.sql`.

**Entities & endpoints**:
- `User`, `UserRole` (`CUSTOMER|ADMIN|AUDITOR`), `UserStatus` (`ACTIVE|SUSPENDED`)
- `POST /api/v1/auth/register` — public; BCrypt hash; role defaults `CUSTOMER`; duplicate username → `409`
- `POST /api/v1/auth/login` — public; rejects `SUSPENDED` (`403`); issues JWT (`sub=userId`, claim `role`)
- `GET /api/v1/users/me` — authenticated; returns `UserResponse` (no password hash)

**Security**: `/api/v1/auth/**` + swagger `permitAll()`, rest `authenticated()`. `@EnableMethodSecurity` on (JWT filter only — API-Key arrives Phase 2).

**Seed**: `AdminUserSeeder` — idempotent `root`/`123456`, role `ADMIN`.

**Tests**: [05-test-plan.md §Phase 1](05-test-plan.md).

---

## Phase 1.5 — Project tooling: Docker, tests & CI/CD

Turn Phase 1 into something buildable, containerized, tested in two tiers, and CI'd — before more features land.

- **Docker**: `Dockerfile` (multi-stage Maven→JRE), `docker-compose.yml` (Postgres + app, one command).
- **Unit tests** (`*Test.java`, Surefire, no DB): `JwtServiceTest`, `AuthServiceTest` (Mockito).
- **Integration tests** (`*IT.java`, Failsafe, real Postgres): `AbstractIntegrationTest` base class (shared container), `AuthFlowIT`.
- **CI**: `.github/workflows/ci.yml` — path-filtered (`pom.xml`/`mvnw`/`src/**`), unit tests then integration tests, uploads reports.
- **README v1**: created now, scoped honestly to what exists so far.

**Tests**: [05-test-plan.md §Phase 1.5](05-test-plan.md).

---

## Phase 2 — Ledger core: credit / debit / hold / capture / void / balance / history

Full double-entry ledger, synchronous insufficient-balance rejection, Outbox as a side channel.

**Migration**: `V2__ledger.sql` — `accounts`/`transfers`/`entries`/`holds` (from `migrations/0001_init.sql`) **plus** `outbox_events`:

```sql
CREATE TABLE outbox_events (
    id           TEXT PRIMARY KEY,
    event_type   TEXT NOT NULL,          -- e.g. 'TRANSFER_COMPLETED', 'HOLD_CAPTURED'
    payload      JSONB NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_valid CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
);
CREATE INDEX outbox_events_pending_idx ON outbox_events(created_at) WHERE status = 'PENDING';
```

**Entities & endpoints**:
- `Account`/`Transfer`/`Entry`/`Hold`; `AccountRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`)
- `LedgerService` (`@Transactional` per operation):
  - `credit`/`debit` — idempotency check → lock both accounts (`id`-sorted) → debit verifies `balance-held>=amount` (`409` otherwise) → persist `Transfer`+2 `Entry` → catch `DataIntegrityViolationException` on idempotency race, re-fetch
  - `hold`/`capture`/`void` — same pattern on `held`; `capture` creates `Transfer(type=HOLD_CAPTURE)`
  - Publishes an outbox event inside the same transaction
- `LedgerController` — `POST /ledger/{credit,debit,hold,capture,void}`, `ROLE_SERVICE`, requires `Idempotency-Key`
- `PlayerController` — `GET /players/me/balance`, `GET /players/me/transactions` (paginated), `ROLE_USER`
- `OutboxEventScheduler` — polls `PENDING`→`PROCESSED` (stub, no real consumer yet)
- `HoldExpiryScheduler` — polls active holds past `expires_at` → `expired`, releases `held` (closes a gap: the column/index existed with no code acting on them)

**Security**: add `ApiKeyAuthFilter` (`X-Api-Key` → `ROLE_SERVICE`).

**Retrofit**: `AuthService.register` also creates the player's `Account` (`PLAYER`/`COINS`/`balance=0`).

**Seed**: `SystemAccountSeeder` — `SYSTEM`/`COINS` treasury account, required before any `credit` succeeds.

**Tests**: [05-test-plan.md §Phase 2](05-test-plan.md) — includes the concurrent-debit and repeated-idempotency-key cases the take-home brief names explicitly.

---

## Phase 2.5 — Database-level backstop for the double-entry invariant

Make "entries of a transfer sum to zero" a DB guarantee, not just an app convention. Full reasoning: [04-design-decisions.md §13](04-design-decisions.md).

**Migration**: `V2_1__entries_balance_trigger.sql` (Flyway dotted version, sorts between `V2`/`V3` — no renumbering needed):

```sql
-- Refuse to install the backstop over already-corrupt data.
DO $$
DECLARE
    bad RECORD;
BEGIN
    SELECT transfer_id, SUM(amount) AS total
    INTO bad
    FROM entries
    GROUP BY transfer_id
    HAVING SUM(amount) <> 0
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'ledger: existing entries for transfer % sum to %, not zero', bad.transfer_id, bad.total;
    END IF;
END;
$$;

CREATE FUNCTION entries_must_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    total BIGINT;
BEGIN
    IF TG_OP <> 'DELETE' THEN
        SELECT COALESCE(SUM(amount), 0) INTO total FROM entries WHERE transfer_id = NEW.transfer_id;
        IF total <> 0 THEN
            RAISE EXCEPTION 'ledger: entries for transfer % sum to %, not zero', NEW.transfer_id, total
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    IF TG_OP <> 'INSERT' THEN
        SELECT COALESCE(SUM(amount), 0) INTO total FROM entries WHERE transfer_id = OLD.transfer_id;
        IF total <> 0 THEN
            RAISE EXCEPTION 'ledger: entries for transfer % sum to %, not zero', OLD.transfer_id, total
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER entries_must_balance
    AFTER INSERT OR UPDATE OR DELETE ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION entries_must_balance();
```

Reuses the existing `entries_transfer_idx` — no new index.

**App changes**: none — `LedgerService` already only writes balanced entries. `ERRCODE=integrity_constraint_violation` (class `23`) is auto-mapped to `DataIntegrityViolationException` → the existing `GlobalExceptionHandler` catches it as `409`.

**Scope**: enforces balance, not row immutability (deleting both entries of a transfer still passes). Hardening that further (`REVOKE UPDATE, DELETE`) is a noted follow-up, not yet scoped.

**Tests**: [05-test-plan.md §Phase 2.5](05-test-plan.md).

---

## Phase 3 — Reward program: 100 COINS on first login

Reuses Phase 2's `credit` — no new ledger logic.

**Migration**: `V3__reward_programs.sql` — table + seed `('signup-bonus-v1', 'First login bonus', 100)`.

**Changes**: `RewardProgram`/`RewardProgramRepository` (lookup by `code`). `AuthService.login` calls `ledgerService.credit(playerAccountId, program.amount(), type=BONUS, referenceId="signup-bonus-v1", idempotencyKey="signup-bonus:"+userId)` — dedup via the DB UNIQUE constraint, no new `users` column needed. No changes to `LedgerService`/`LedgerController`.

**Tests**: [05-test-plan.md §Phase 3](05-test-plan.md).

---

## Phase 4 — Transaction Refund

Closes the gap flagged in [04-design-decisions.md §5](04-design-decisions.md) — formalizes `REFUND`. Also a take-home bonus feature.

**Migration**: none — `REFUND` type and `reference_id` already exist; pure application logic, consistent with append-only (no new column, no `UPDATE` of the original `Transfer`).

**Design**: "already refunded" is computed on demand — `SUM(amount) FROM transfers WHERE type='REFUND' AND reference_id=:originalTransferId` — not stored.

`LedgerService.refund(originalTransferId, amount, idempotencyKey)`:
1. Idempotency check (same pattern as `credit`/`debit`).
2. Look up original `Transfer` → `404` if missing.
3. Reject (`409`) if original is itself a `REFUND` or not `COMPLETED`.
4. Lock both accounts (`id`-sorted, same as #4 in design-decisions).
5. `remaining = amount - alreadyRefunded`; default `amount` to full `remaining`; reject (`409`) if `amount<=0` or `>remaining`.
6. Insert reversed `Transfer(type=REFUND)` + 2 offsetting `Entry` rows; update balances.
7. Publish outbox event (`REFUND_COMPLETED`).

**Endpoint**: `POST /api/v1/ledger/refund` — `ROLE_SERVICE`, `Idempotency-Key`. Body: `original_transfer_id`, `amount` (optional), `metadata` (optional).

**Tests**: [05-test-plan.md §Phase 4](05-test-plan.md).

---

## Phase 5 — Final README pass & submission readiness

README has existed since Phase 1.5 — this is the final consolidation pass, not a rewrite.

- Fill in **Concurrency & Idempotency** (real content, replacing the Phase 1.5 placeholder); finalize **Testing approach** (call out the concurrent-debit/idempotency-key cases) and **Assumptions & limitations**.
- **Submission**: push to GitHub (public, or private with reviewer access); fresh-clone sanity check (`docker-compose up -d` → `./mvnw test && ./mvnw failsafe:integration-test failsafe:verify` → manual smoke test of each mandatory endpoint).
