# Tech Stack & Codebase Structure

## Tech Stack

| Layer | Choice |
|---|---|
| Language/Framework | Java 17, Spring Boot 3.x |
| Persistence | Spring Data JPA + PostgreSQL |
| Migration | Flyway |
| Locking | Pessimistic (`SELECT ... FOR UPDATE`) on `accounts`, batch-lock ordered by `id` to avoid deadlocks |
| Idempotency | DB-level UNIQUE constraint on `idempotency_key`, no external library |
| Async events (audit/notification only — never for debit/hold) | Transactional Outbox table + `@Scheduled` poller, `FOR UPDATE SKIP LOCKED` |
| Auth | Spring Security + JWT, role check via `@PreAuthorize` (`@EnableMethodSecurity`) |
| Mapping DTO ↔ Entity | MapStruct |
| Validation | Spring Validation (`jakarta.validation`) |
| Test | JUnit 5 + Mockito (unit), Testcontainers with real Postgres (integration) |
| API docs | springdoc-openapi |
| Base package | `com.walletledger` (no org segment) |

### Key decisions, in brief

- **Java 17, not 21**: matches `../ledger-engine`; nothing here needs virtual threads.
- **Pessimistic locking**: debit must reject synchronously (409) — no optimistic retry loops on the hot path. Lock both accounts in `id`-sorted order (both reference repos do this independently).
- **Outbox = audit/notification only**: core credit/debit/hold/capture/void stay synchronous, since "reject if insufficient balance" can't be deferred to a background worker. No real Outbox consumer yet — a stub extension point.
- **`@PreAuthorize` over URL-pattern matchers**: keeps the authorization rule next to the endpoint it protects; `SecurityConfig` only declares public vs. authenticated.
- **MapStruct**: generates DTO↔Entity mappers at compile time; needs `lombok-mapstruct-binding` so its processor can see Lombok-generated getters.
- **`ddl-auto: validate`**: Flyway is the only schema source (hand-written CHECK constraints); Hibernate never generates DDL.

## Codebase Structure

```
com.walletledger
├── WalletLedgerApplication.java
├── config/                          # SecurityConfig, OpenApiConfig, SchedulingConfig, seeders
├── domain/
│   ├── user/                        # entity, repo, AuthController/Service, UserController, dto/, mapper/
│   ├── account/                     # Account entity, AccountRepository (row locking)
│   ├── ledger/                      # Transfer/Entry/Hold, LedgerService/Controller, dto/, mapper/
│   ├── player/                      # PlayerController (balance, transactions)
│   ├── rewardprogram/                # RewardProgram lookup table
│   └── outbox/                      # OutboxEvent, publisher, @Scheduled poller
├── infrastructure/security/
│   ├── jwt/                         # JwtService, JwtAuthenticationFilter, JwtProperties
│   └── apikey/                      # ApiClient, ApiKeyAuthFilter (X-Api-Key -> ROLE_SERVICE)
└── shared/
    ├── exception/                   # GlobalExceptionHandler
    └── response/                    # ApiResponse<T> envelope
```

Migration order: `V1__users.sql` → `V2__ledger.sql` (+ `outbox_events`) → `V2_1__entries_balance_trigger.sql` → `V3__reward_programs.sql`. See [03-implementation-plan.md](03-implementation-plan.md).
