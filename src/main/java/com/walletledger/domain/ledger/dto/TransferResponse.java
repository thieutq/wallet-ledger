package com.walletledger.domain.ledger.dto;

import java.time.Instant;

public record TransferResponse(
        String id,
        String fromAccountId,
        String toAccountId,
        long amount,
        String currency,
        String status,
        String type,
        String referenceId,
        Instant createdAt
) {
}
