package com.walletledger.domain.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Independently re-verifies the ledger on a schedule — catches drift that
 * the Phase 2.5 DB trigger structurally can't: the trigger only watches
 * {@code entries}, so a direct {@code UPDATE accounts.balance} (bad
 * migration, manual incident fix) is invisible to it. Full-table aggregate
 * scan, so a much longer interval than the outbox/hold-expiry pollers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:3600000}")
    public void runReconciliation() {
        ReconciliationReport report = reconciliationService.run();
        if (report.isHealthy()) {
            log.info("Reconciliation OK: global entries sum=0, no account drift");
        } else {
            log.error("RECONCILIATION FAILURE: globalEntriesSum={}, driftedAccounts={}",
                    report.globalEntriesSum(), report.driftedAccounts());
        }
    }
}
