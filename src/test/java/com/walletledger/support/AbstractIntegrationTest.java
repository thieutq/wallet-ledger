package com.walletledger.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base for *IT tests: one full Spring context + one real Postgres,
 * started once and reused across every subclass in the same JVM. Extend this
 * instead of repeating the container/context boilerplate in each new *IT
 * class.
 *
 * <p>Defaults to a Testcontainers-managed Postgres (same as CI). If
 * Testcontainers can't reach Docker on a given machine, set {@code IT_DB_URL}
 * (+ optionally {@code IT_DB_USERNAME}/{@code IT_DB_PASSWORD}) to point at an
 * already-running Postgres instead — e.g. {@code docker-compose.test.yml} —
 * with no code changes required. See README.md's "Run the tests" section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_DB_URL = System.getenv("IT_DB_URL");

    private static final PostgreSQLContainer<?> postgres;

    static {
        if (EXTERNAL_DB_URL == null) {
            postgres = new PostgreSQLContainer<>("postgres:15-alpine");
            postgres.start();
        } else {
            postgres = null;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (EXTERNAL_DB_URL != null) {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_DB_USERNAME", "wallet_ledger_test"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_DB_PASSWORD", "wallet_ledger_test"));
        } else {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }
}
