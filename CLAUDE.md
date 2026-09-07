# Wallet Ledger

Double-entry wallet ledger backend (Java 17 + Spring Boot). Full design/API/test docs: [docs/README.md](docs/README.md) — read that first for anything beyond quick orientation.

## Key commands

```bash
./mvnw test                                        # unit — no DB, no Docker
./mvnw failsafe:integration-test failsafe:verify   # integration — real Postgres via Testcontainers
```

**Testcontainers is unreliable on this machine.** Don't assume the command above just works — use the `verify` skill, or the escape hatch it wraps:
```bash
docker compose -f docker-compose.test.yml up -d
IT_DB_URL=jdbc:postgresql://localhost:5433/wallet_ledger_test ./mvnw failsafe:integration-test failsafe:verify
docker compose -f docker-compose.test.yml down
```

## Layout

Package-by-domain under `com.walletledger`, not by technical layer — each domain owns its entity/repo/service/controller/dto together:
- `domain/user` — auth, JWT
- `domain/account`, `domain/ledger` — the core: `Account`/`Transfer`/`Entry`/`Hold`, `LedgerService`/`LedgerController`
- `domain/player` — player-facing balance/history
- `domain/reward` — signup bonus
- `domain/outbox` — transactional outbox (audit/notification side-effects)
- `domain/reconciliation` — independent ledger re-verification (scheduled + `GET /api/v1/admin/reconciliation`)

Full tree, entity table, and request-flow diagram: [docs/architecture.md](docs/architecture.md).

## Conventions

- Every ledger operation (credit/debit/hold/capture/refund/transfer) follows the same shape: lock accounts via `LedgerService.lockAccounts` (id-sorted, never ad hoc), idempotency via `TransactionTemplate`/`runIdempotent` (never `@Transactional` self-invocation), write balanced `Entry` pairs, never `UPDATE`/`DELETE` `entries`/`transfers`. See `docs/04-design-decisions.md` for the why.
- Git commits end with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Never commit without being asked first.
- Keep `docs/01-api-list.md`, `docs/03-implementation-plan.md`, `docs/05-test-plan.md`, and `README.md` in sync when a feature changes the API surface or test coverage — this project treats doc drift as a real defect, not a nice-to-have.
