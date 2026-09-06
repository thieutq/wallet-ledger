package com.walletledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Split out of SecurityConfig: ApiKeyAuthFilter needs PasswordEncoder, and
 * SecurityConfig needs ApiKeyAuthFilter injected into its constructor —
 * defining passwordEncoder() as an instance @Bean method inside SecurityConfig
 * itself created an unresolvable circular dependency the moment
 * ApiKeyAuthFilter existed (Phase 2). This class has no dependency on
 * SecurityConfig, breaking the cycle.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
