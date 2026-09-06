package com.walletledger.config;

import com.walletledger.domain.user.User;
import com.walletledger.domain.user.UserRepository;
import com.walletledger.domain.user.UserRole;
import com.walletledger.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotent — never overwrites an existing 'root' user, safe to run on every restart.
 * Default password is a known dev/seed credential; must be changed before any real deployment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserSeeder implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "root";
    private static final String ADMIN_DEFAULT_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername(ADMIN_USERNAME).isPresent()) {
            return;
        }

        User admin = User.builder()
                .username(ADMIN_USERNAME)
                .passwordHash(passwordEncoder.encode(ADMIN_DEFAULT_PASSWORD))
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.save(admin);
        log.info("Seeded default admin user '{}'", ADMIN_USERNAME);
    }
}
