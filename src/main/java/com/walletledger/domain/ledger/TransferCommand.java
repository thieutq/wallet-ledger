package com.walletledger.domain.ledger;

import java.util.Map;

/**
 * Internal input to {@link LedgerService#credit} / {@link LedgerService#debit}
 * — decoupled from the web-layer request DTOs.
 */
public record TransferCommand(
        String accountId,
        long amount,
        String currency,
        TransferType type,
        String referenceId,
        String createdBy,
        Map<String, Object> metadata,
        String idempotencyKey
) {
}
