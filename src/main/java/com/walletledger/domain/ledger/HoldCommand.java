package com.walletledger.domain.ledger;

import java.time.Instant;
import java.util.Map;

public record HoldCommand(
        String accountId,
        long amount,
        String currency,
        HoldType type,
        String referenceId,
        String createdBy,
        Map<String, Object> metadata,
        Instant expiresAt,
        String idempotencyKey
) {
}
