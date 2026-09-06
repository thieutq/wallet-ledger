package com.walletledger.domain.ledger.dto;

import com.walletledger.domain.ledger.TransferType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record DebitRequest(
        @NotBlank String accountId,
        @Positive long amount,
        String currency,
        @NotNull TransferType type,
        String referenceId,
        Map<String, Object> metadata
) {
}
