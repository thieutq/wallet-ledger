# Design Decisions & Trade-offs

Reasoning behind the schema/API/architecture choices: decision → rejected alternative → why. #6 was a correction (a re-check overturned an initial assumption); #5 was flagged undecided and resolved later in Phase 4; #13 was added once the DB backstop was designed.

---

## 1. Double-entry ledger, not a single balance column
**Decision**: every balance change = 1 `Transfer` + 2 `Entry` rows (signed, sum to zero) — [migrations/0001_init.sql:94-102](migrations/0001_init.sql).
**Rejected**: single `balance` column + a separate log table — nothing forces the log to stay consistent with the balance.
**Why**: makes "balance drifts from its own history" structurally impossible, not an app rule to remember.
**Trade-off**: 2 inserts instead of 1 update — acceptable, the requirement is about auditability, not raw throughput.

## 2. `holds` table + `SYSTEM` accounts
**`holds`**: two-phase reserve → capture/void via `accounts.held`, without touching `balance` until capture.
**`SYSTEM` accounts**: double-entry needs a counterparty even for "credit from nowhere" (a bonus) — a `SYSTEM` (treasury) account is that counterparty.
**⚠️ Correction**: `balance_non_negative` originally applied to every account, which would make crediting a player (debiting the treasury) impossible from day one. Fixed: `CHECK (type='SYSTEM' OR balance>=0)` — [migrations/0001_init.sql:40](migrations/0001_init.sql).

## 3. Unit of Work: one transaction per money movement
**Decision**: every operation is one `@Transactional` — check idempotency → lock accounts (ordered, #4) → check balance → update → insert transfer+entries (+outbox) → commit.
**Why**: guarantees no lost updates/partial writes; also why Outbox can't replace this (#9) — "reject synchronously" needs an answer inside the same transaction.
**Reference**: matches `../ledger-engine`'s `TransactionService`, chosen over `../ledger-service`'s outbox-first approach.

## 4. Deadlock prevention: lock ordering
**Decision**: always lock the two accounts in a fixed order (sorted by `id`), never "from, then to."
**Why**: prevents the classic deadlock where transaction A locks X→Y while B locks Y→X concurrently.
**Validated**: both reference repos independently converge on the same pattern.

## 5. Append-only + reversal — ✅ resolved (Phase 4)
**True today**: no `UPDATE`/`DELETE` path anywhere — corrections are new offsetting transfers, never edits.
**Resolved**: "already refunded" is computed via `SUM(...) WHERE type='REFUND'`, not stored — keeps the original transfer untouched; no `REVERSED` status added; refund-of-a-refund is rejected. See [03-implementation-plan.md — Phase 4](03-implementation-plan.md).
**Trade-off**: an aggregate query instead of an O(1) read — fine, refunds aren't a hot path.

## 6. Balance storage — ⚠️ Correction: stored, not derived
**Correction**: this project does **not** compute balance as `SUM(entries)`. `accounts.balance`/`held` are stored, mutable columns updated in the same transaction as the entries insert — [migrations/0001_init.sql:33-34](migrations/0001_init.sql).
**Why not derived** (deviates from `../ledger-engine`): `GET /players/me/balance` needs O(1) reads, not an aggregate scan, on a likely hot path. Drift risk is mitigated by the shared transaction (#3).
**Trade-off**: `entries` becomes redundant for computing *current* balance — kept anyway for history (`GET /players/me/transactions`).

## 7. Idempotency: DB UNIQUE constraint, no external library
**Decision**: `idempotency_key UNIQUE NOT NULL` on `transfers`/`holds` — a retried insert collides, the service returns the original result.
**Rejected**: a Redis/cache-based idempotency layer — can desync from DB state unless made transactional with the write anyway, at which point it's just reinventing this constraint with extra infrastructure.

## 8. `type` as a closed CHECK enum; volatility pushed to `reference_id`/`reward_programs`
**Decision**: `type` stays a small, stable CHECK enum (`BONUS`/`PURCHASE`/`REFUND`/`ADMIN_ADJUSTMENT`/`HOLD_CAPTURE`/`TRANSFER`). Specific campaigns/missions live in `reference_id`, not `type`.
**Rejected**: normalizing `type` into a lookup table — unnecessary; the categories are genuinely stable. (Original mistake, later fixed: one `type` per bonus program, meaning a migration per campaign — collapsed into `BONUS` + `reference_id`.)
**Not a DB FK**: `reference_id` is polymorphic (mission/order/ticket/program code depending on `type`) — a single FK can't express that; enforced at the app layer instead.

## 9. Synchronous core writes; Outbox only for side-effects
**Decision**: credit/debit/hold/capture/void are fully synchronous. Outbox carries only side-effects allowed to lag (audit/notification), written in the same transaction.
**Rejected**: `../ledger-service`'s full outbox pattern (`202 Accepted`, processed later) — incompatible with "reject a debit synchronously."

## 10. Dual auth: JWT for players, API-Key for system/admin
**Decision**: players use JWT; ledger mutation endpoints require `X-Api-Key` → `ROLE_SERVICE`. `@PreAuthorize` enforces per endpoint.
**Why**: a player must never call `/ledger/credit` directly (that's minting their own currency) — JWT identifies a person, API-Key identifies a trusted service.

## 11. Flyway owns the schema; `ddl-auto=validate`
**Decision**: all constraints/indexes are hand-written SQL; Hibernate only validates, never generates DDL.
**Why**: correctness guarantees (CHECK constraints, the `SYSTEM` exemption) can't be reliably expressed via JPA annotations alone.

## 12. Package-by-domain, not package-by-layer
**Decision**: `domain/user/`, `domain/ledger/`, etc. — each owns its entity/repo/service/controller/DTOs together.
**Why**: `../ledger-engine` does this; `../ledger-service` does package-by-layer, making one capability's full shape harder to see at a glance. Ledger is the highest-risk part of this system — keeping it together matters more here.
**Trade-off**: needs an explicit `shared/` package for cross-domain code.

## 13. DB-level backstop trigger (Phase 2.5)
**Decision**: a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `entries` re-checks, at `COMMIT`, that every touched `transfer_id` sums to zero. Full SQL: [03-implementation-plan.md — Phase 2.5](03-implementation-plan.md).
**Rejected**: relying solely on the app-level invariant — makes #1's guarantee only conditionally true (depends on every future contributor keeping entries balanced).
**Why deferred**: a transfer's 2 entries are inserted as separate statements; only the final COMMIT-time state is meaningful.
**Scope**: enforces balance, not immutability (deleting both entries still passes) — hardening that further is a noted, unscoped follow-up.
**Minor note**: raises `ERRCODE=integrity_constraint_violation`, auto-mapped to `409` by the existing handler — arguably should be `500` (it signals an app bug), left as-is since this path should never fire in correct operation.
