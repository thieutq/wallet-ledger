package com.walletledger.infrastructure.security.jwt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test — no Spring context, no DB. Run by Surefire (mvn test).
 */
class JwtServiceTest {

    private static final String TEST_SECRET = "unit-test-secret-key-must-be-at-least-32-bytes-long";

    private JwtService newJwtService(long expirationMs) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setExpirationMs(expirationMs);
        return new JwtService(properties);
    }

    @Test
    void generatesAndParsesTokenRoundTrip() {
        JwtService jwtService = newJwtService(60_000);

        String token = jwtService.generateToken("user-123", "CUSTOMER");

        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo("user-123");
        assertThat(jwtService.extractRole(token)).isEqualTo("CUSTOMER");
    }

    @Test
    void rejectsExpiredToken() {
        // Expiration in the past the instant the token is issued.
        JwtService jwtService = newJwtService(-1_000);

        String token = jwtService.generateToken("user-123", "CUSTOMER");

        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    void rejectsGarbageToken() {
        JwtService jwtService = newJwtService(60_000);

        assertThat(jwtService.isTokenValid("not-a-real-token")).isFalse();
    }
}
