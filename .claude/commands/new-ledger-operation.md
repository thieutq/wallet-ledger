---
description: Scaffold a new LedgerService operation, following the exact pattern credit/debit/hold/capture/refund/transfer already use.
---

Every money-movement operation in this codebase follows the same six-step shape. Use `LedgerService.transfer`/`doTransfer` and `LedgerController.transfer` (added for the peer-to-peer transfer feature) as the concrete template — read them first.

1. **Command record** — a new `record` in `domain/ledger/` (e.g. `AccountTransferCommand`), decoupled from the web-layer request DTO. Always carries an `idempotencyKey`.
2. **`LedgerService`**: a public method (`credit`/`debit`/`refund`/`transfer` pattern) that short-circuits on `transferRepository.findByIdempotencyKey(...)`, else runs a private `doXxx` inside `runIdempotent(...)`. Inside `doXxx`: resolve currency, `lockAccounts(...)` (id-sorted — never lock accounts any other way), whatever business checks apply (400/409 via `ResponseStatusException`), mutate balances, `persistTransfer(...)`, publish an outbox event.
3. **Request DTO** in `domain/ledger/dto/` + **Controller endpoint** in `LedgerController` — `ROLE_SERVICE`, requires the `Idempotency-Key` header, returns `201` via the existing `created(transfer)` helper.
4. **Tests** in `LedgerIT.java` — at minimum: happy path, the relevant 409 (insufficient balance / invalid state), a 400 for bad input, and an idempotency-replay test (same key fired twice → single effect). Add a concurrent variant if the operation touches shared balance under contention.
5. **Docs**: add the endpoint to `docs/01-api-list.md`, a Phase entry to `docs/03-implementation-plan.md`, and test-case rows to `docs/05-test-plan.md` (new requirement code if it's a genuinely new capability).
6. **README.md**: add the endpoint to the API Endpoints table, a bullet to Task List's Done section, and — only if it changes a *guarantee*, not just adds a capability — a line in Correctness & Concurrency Guarantees.

After implementing, run the `verify` skill before considering it done.
