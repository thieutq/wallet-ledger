package com.walletledger.domain.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record TransferRequest(
        @NotBlank String fromAccountId,
        @NotBlank String toAccountId,
        @Positive long amount,
        String currency,
        String referenceId,
        Map<String, Object> metadata
) {
}
