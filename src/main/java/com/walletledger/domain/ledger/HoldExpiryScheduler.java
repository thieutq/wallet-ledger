package com.walletledger.domain.ledger;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Closes a gap where {@code holds.expires_at} + its index existed with no
 * code path ever acting on them: releases active holds once they expire,
 * same effect as void, just triggered by time instead of a caller.
 */
@Component
@RequiredArgsConstructor
public class HoldExpiryScheduler {

    private static final int BATCH_SIZE = 50;

    private final HoldRepository holdRepository;
    private final AccountRepository accountRepository;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void expireOverdueHolds() {
        List<Hold> expired = holdRepository.findExpiredActiveHolds(BATCH_SIZE);
        for (Hold hold : expired) {
            Account account = accountRepository.findByIdForUpdate(hold.getFromAccountId()).orElseThrow();
            account.setHeld(account.getHeld() - hold.getAmount());
            accountRepository.save(account);

            hold.setStatus(HoldStatus.EXPIRED);
            holdRepository.save(hold);
        }
    }
}
