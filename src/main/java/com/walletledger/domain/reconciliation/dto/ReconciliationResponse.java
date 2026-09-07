package com.walletledger.domain.reconciliation.dto;

import java.util.List;

public record ReconciliationResponse(boolean healthy, long globalEntriesSum, List<AccountDriftResponse> driftedAccounts) {
}
