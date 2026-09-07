package com.walletledger.domain.reconciliation;

import java.util.List;

/**
 * Result of an independent, read-only re-verification of the ledger: does
 * {@code entries} still net to zero system-wide, and does every account's
 * stored {@code balance} still agree with what its own entries sum to.
 */
public record ReconciliationReport(long globalEntriesSum, List<AccountDrift> driftedAccounts) {

    public boolean isHealthy() {
        return globalEntriesSum == 0 && driftedAccounts.isEmpty();
    }
}
