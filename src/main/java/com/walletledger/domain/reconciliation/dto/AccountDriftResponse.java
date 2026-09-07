package com.walletledger.domain.reconciliation.dto;

public record AccountDriftResponse(String accountId, long storedBalance, long ledgerBalance) {
}
