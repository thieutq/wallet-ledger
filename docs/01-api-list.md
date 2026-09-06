# Wallet Ledger API List

## Auth & User (Phase 1)

- `POST /api/v1/auth/register` — creates a new player account. Body: `username`, `password`. `role` always defaults to `CUSTOMER` — not settable by the caller (admin accounts are seeded, never self-registered).
- `POST /api/v1/auth/login` — authenticates, returns a JWT (`sub = user id`, claim `role`). Rejects (`403`) if `status = SUSPENDED`. First successful login also triggers the `signup-bonus-v1` reward (100 COINS, via the same `LedgerService.credit`) — see [Phase 3](03-implementation-plan.md#phase-3--reward-program-100-coins-on-first-login).
- `GET /api/v1/users/me` — token auth, any authenticated role. Returns the caller's profile: `id`, `username`, `role`, `status`, `created_at`. Never returns the password hash.

## Player-facing (token auth, self-service)

- `GET /api/v1/players/me/balance` — returns the player's current balance (Available, Hold, Total).
- `GET /api/v1/players/me/transactions?page=1&size=20` — returns the player's paginated transaction history.

## Ledger — Direct Debit/Credit (system/admin-facing, requires `Idempotency-Key`)

Both endpoints require a body of:
- `account_id` — target player wallet
- `amount`
- `currency` (optional) — defaults to `COINS`, the only currency currently supported
- `type` — `BONUS` | `PURCHASE` | `ADMIN_ADJUSTMENT` | `TRANSFER` (matches `transfers_type_valid`, minus `HOLD_CAPTURE`/`REFUND` — those are only ever set internally by `capture`/`refund`, never caller-supplied, so refunds can't bypass their own validation)
- `reference_id` (optional) — id of the external entity this is linked to (mission id, order id, admin ticket id...)
- `metadata` (optional) — freeform note, e.g. admin justification

- `POST /api/v1/ledger/credit` — credits funds into a player's wallet.
- `POST /api/v1/ledger/debit` — debits funds from a player's wallet immediately, rejects (409) if the available balance is insufficient.

## Ledger — Hold/Capture/Void (two-phase debit, system/admin-facing, requires `Idempotency-Key`)

`hold` requires `account_id`, `amount`, `type` (`PURCHASE` | `ADMIN_ADJUSTMENT`), optional `currency` (defaults to `COINS`) and optional `reference_id`/`metadata`, same as above. `capture`/`void` only need the `hold_id`.

- `POST /api/v1/ledger/hold` — reserves funds, rejects (409) if the available balance is insufficient.
- `POST /api/v1/ledger/capture` — confirms the hold, debits the reserved funds, and closes the hold.
- `POST /api/v1/ledger/void` — cancels the hold and releases the reserved funds back to the available balance.

## Ledger — Refund (system/admin-facing, requires `Idempotency-Key`)

- `POST /api/v1/ledger/refund` — reverses a previous `COMPLETED` transfer. Body: `original_transfer_id`, `amount` (optional, defaults to full remaining), `metadata` (optional). `404` if not found; `409` if already `REFUND`, not `COMPLETED`, or `amount` exceeds what's still refundable (computed on demand, not stored). Creates a `Transfer(type=REFUND, reference_id=original_transfer_id)` moving funds back — see [Phase 4](03-implementation-plan.md).

## Admin / Auditor (user management, audit)

- `GET /api/v1/admin/users` — lists users, filterable by role/status (paginated).
- `PATCH /api/v1/admin/users/{id}/status` — suspends or activates a user.
- `GET /api/v1/admin/audit-logs?target_type=&target_id=` — looks up admin action audit logs (paginated). Backed by the `audit_logs` table for non-balance admin actions (e.g. user suspension); balance-changing actions are already recorded on `transfers`/`holds` via `type`/`reference_id`/`created_by`.
