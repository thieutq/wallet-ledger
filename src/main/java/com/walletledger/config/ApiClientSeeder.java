package com.walletledger.config;

import com.walletledger.infrastructure.security.apikey.ApiClient;
import com.walletledger.infrastructure.security.apikey.ApiClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotent — seeds one default service credential so the system/admin-facing
 * ledger endpoints (X-Api-Key) are callable out of the box. Dev-only key,
 * same "known seed credential, change before real use" caveat as
 * AdminUserSeeder's root/123456.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiClientSeeder implements CommandLineRunner {

    private static final String CLIENT_NAME = "default-service";
    private static final String DEFAULT_API_KEY = "dev-service-api-key-change-me";

    private final ApiClientRepository apiClientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (apiClientRepository.findByName(CLIENT_NAME).isPresent()) {
            return;
        }

        ApiClient client = ApiClient.builder()
                .name(CLIENT_NAME)
                .hashedApiKey(passwordEncoder.encode(DEFAULT_API_KEY))
                .active(true)
                .build();

        apiClientRepository.save(client);
        log.info("Seeded default API client '{}' (dev key: {})", CLIENT_NAME, DEFAULT_API_KEY);
    }
}
