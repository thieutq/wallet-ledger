# Wallet Ledger

A production-minded wallet ledger backend service (Java 17 + Spring Boot).

> **Status**: Phase 1 (`users` + auth) is implemented and tested. Ledger operations (credit/debit/hold/capture/void/balance/history), the reward program, and refund support land in later phases — see [Assumptions & limitations](#assumptions--limitations) below and [docs/03-implementation-plan.md](docs/03-implementation-plan.md) for the full roadmap.

## How to run

### Prerequisites
- Docker + Docker Compose
- JDK 17 and Maven, if you'd rather run outside Docker (the repo ships `./mvnw`, so a local Maven install isn't required)

### Run everything with Docker Compose
```bash
docker-compose up -d
```
This starts Postgres and the app together; Flyway migrates the schema automatically on startup. The API is then available at `http://localhost:8080`.

### Run locally without Docker
```bash
docker-compose up -d postgres   # just the database
./mvnw spring-boot:run
```

### Run the tests
```bash
./mvnw test                                        # unit tests only (fast, no Docker needed)
./mvnw failsafe:integration-test failsafe:verify    # integration tests (needs Docker, for Testcontainers)
```

If Testcontainers can't reach Docker on your machine, point the integration tests at a Postgres
```bash
docker compose -f docker-compose.test.yml up -d
IT_DB_URL=jdbc:postgresql://localhost:5433/wallet_ledger_test ./mvnw failsafe:integration-test failsafe:verify
docker compose -f docker-compose.test.yml down
```

### Try it
A `root` / `123456` admin account is seeded automatically on first startup (see [Assumptions & limitations](#assumptions--limitations) — change this before any real deployment).

```bash
# Register a new player
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "password123"}'

# Log in (also works with root/123456)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "password123"}'

# Get the authenticated profile (use the token from the login response)
curl http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer <token>"
```

Interactive API docs (Swagger UI): `http://localhost:8080/swagger-ui.html`.

## Design decisions

Full reasoning, alternatives considered, and trade-offs are written up in [docs/04-design-decisions.md](docs/04-design-decisions.md) — start there for the "why." In short, this is a double-entry ledger (not a single mutable balance column), with a stored/maintained balance for O(1) reads and immutable `entries` for history, `holds` + `SYSTEM` accounts for two-phase reservations and currency issuance, and a DB-level trigger backstop on top of the application-level invariant. See [docs/](docs/README.md) for the full documentation set (API list, tech stack, implementation plan, test plan) and the suggested reading order.

What's actually live as of this phase: `users`, JWT-based auth (`register`/`login`/`me`), and the project tooling below. The ledger core itself (where most of those design decisions apply) arrives in Phase 2.

## Concurrency & Idempotency

Not yet applicable at this phase — there's no money-movement code yet (Phase 1 is auth only), so there's nothing here to race or retry. Once Phase 2 lands, this section will cover: pessimistic row locking in `id`-sorted order to avoid deadlocks, one `@Transactional` unit of work per operation, idempotency via a DB-level `UNIQUE` constraint (not an external library), and a deferred DB trigger that backstops the double-entry invariant even against an application bug. The reasoning for each of these already exists in [docs/04-design-decisions.md §3, §4, §7, §13](docs/04-design-decisions.md) — written ahead of the code, and implemented as the plan gets executed phase by phase.

## Testing approach

Two tiers, kept separate on purpose (matches [docs/05-test-plan.md](docs/05-test-plan.md)):

- **Unit** (`*Test.java`, run by Maven Surefire via `./mvnw test`) — no Spring context, no DB, pure logic with Mockito. `JwtServiceTest` covers token sign/parse/expiry; `AuthServiceTest` covers `AuthService` with all its collaborators mocked (password hashing happens before save, wrong password is rejected without issuing a token, a suspended account is rejected even with the correct password).
- **Integration** (`*IT.java`, run by Maven Failsafe via `./mvnw failsafe:integration-test failsafe:verify`) — full Spring context against a real Postgres via Testcontainers (`AbstractIntegrationTest` starts one container and shares it across every `*IT` class). `AuthFlowIT` covers the full register → login → `/users/me` flow, a 401 without a token, a 409 on duplicate username, 400s on invalid input (blank username, short password), and the seeded `root`/`123456` admin logging in successfully.

CI (`.github/workflows/ci.yml`) runs both tiers on every push/PR that touches `pom.xml`, `mvnw`, or `src/**` — unit tests first (fail fast), then integration tests, with both tiers' reports uploaded as build artifacts.

## Assumptions & limitations

- **`root`/`123456` is a known seed credential**, meant for local development only — it must be changed before any real deployment (no automated password-rotation is provided; changing it is a manual `AuthService`/DB step).
- Ledger operations, the reward program, holds/refunds, and admin user-management/audit-log endpoints are **not implemented yet** — they're designed in [docs/](docs/README.md) and scheduled across the remaining phases in [docs/03-implementation-plan.md](docs/03-implementation-plan.md), but this commit only contains Phase 1 (`users` + auth) plus this project-tooling phase.
- Single currency (`COINS`) is assumed throughout the design; see [docs/04-design-decisions.md](docs/04-design-decisions.md) for why and what it would take to relax that.
- No admin/audit-log surface exists yet (`audit_logs` table and `/admin/*` endpoints are explicitly out of scope for the phases currently planned).

## AI Tooling Notes

This project's schema, API design, architecture decisions, and implementation were developed with heavy use of **Claude Code** (Anthropic) throughout — including reviewing early design choices for correctness, comparing against reference implementations, and writing the Java source and tests. All design trade-offs and their reasoning are recorded in [docs/04-design-decisions.md](docs/04-design-decisions.md) as they were made, rather than reconstructed after the fact.
