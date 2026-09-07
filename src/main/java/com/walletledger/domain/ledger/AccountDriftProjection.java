package com.walletledger.domain.ledger;

/**
 * Result row of {@link EntryRepository#findDriftedAccounts()} — an account
 * whose stored {@code balance} disagrees with what its {@code entries}
 * actually sum to.
 */
public interface AccountDriftProjection {
    String getId();

    long getStoredBalance();

    long getLedgerBalance();
}
