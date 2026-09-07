---
name: verify
description: Run the full unit + integration test suite against a real Postgres. Use after making changes before marking a task done. Testcontainers is unreliable on this machine, so this uses the docker-compose.test.yml + IT_DB_URL escape hatch instead of relying on `mvn failsafe` alone.
---

Run in sequence and report failures:

```bash
docker compose -f docker-compose.test.yml down
docker compose -f docker-compose.test.yml up -d
```

Wait for `wallet-ledger-postgres-test-1` to report healthy (poll `docker inspect --format='{{.State.Health.Status}}' wallet-ledger-postgres-test-1`, up to ~30s) before continuing.

```bash
docker run --rm \
  -v "$(pwd):/workspace" \
  -v maven_repo_cache:/root/.m2 \
  -w /workspace \
  -e IT_DB_URL=jdbc:postgresql://host.docker.internal:5433/wallet_ledger_test \
  --add-host=host.docker.internal:host-gateway \
  maven:3.9-eclipse-temurin-17 \
  mvn -q -B clean test failsafe:integration-test failsafe:verify
```

Then tear down:
```bash
docker compose -f docker-compose.test.yml down
```

Report results from `target/surefire-reports/*.txt` (unit) and `target/failsafe-reports/failsafe-summary.xml` + `target/failsafe-reports/*.txt` (integration) — total run, failures, and for any failure the specific test class/method and assertion/error.

If Docker itself isn't reachable, say so explicitly rather than silently reporting success — don't fall back to `mvn failsafe:integration-test failsafe:verify` directly, it will spuriously fail trying to start Testcontainers.
