package com.walletledger.domain.ledger.dto;

import java.time.Instant;

public record HoldResponse(
        String id,
        String fromAccountId,
        String toAccountId,
        long amount,
        long captured,
        String status,
        String type,
        String referenceId,
        Instant createdAt,
        Instant expiresAt
) {
}
