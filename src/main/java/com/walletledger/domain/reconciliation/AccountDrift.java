package com.walletledger.domain.reconciliation;

public record AccountDrift(String accountId, long storedBalance, long ledgerBalance) {
}
