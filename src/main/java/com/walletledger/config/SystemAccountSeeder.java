package com.walletledger.config;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.account.AccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotent — required before any credit/debit can succeed, since every
 * transfer needs a SYSTEM counterparty (see docs/04-design-decisions.md #2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemAccountSeeder implements CommandLineRunner {

    private static final String CURRENCY = "COINS";

    private final AccountRepository accountRepository;

    @Override
    public void run(String... args) {
        if (accountRepository.findByTypeAndCurrency(AccountType.SYSTEM, CURRENCY).isPresent()) {
            return;
        }

        Account system = Account.builder()
                .type(AccountType.SYSTEM)
                .currency(CURRENCY)
                .balance(0)
                .held(0)
                .build();

        accountRepository.save(system);
        log.info("Seeded SYSTEM/{} treasury account", CURRENCY);
    }
}
