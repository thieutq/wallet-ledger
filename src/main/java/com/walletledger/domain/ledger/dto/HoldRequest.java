package com.walletledger.domain.ledger.dto;

import com.walletledger.domain.ledger.HoldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.Map;

public record HoldRequest(
        @NotBlank String accountId,
        @Positive long amount,
        String currency,
        @NotNull HoldType type,
        String referenceId,
        Map<String, Object> metadata,
        Instant expiresAt
) {
}
