package com.walletledger.domain.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Map;

public record RefundRequest(
        @NotBlank String originalTransferId,
        @Positive Long amount,
        Map<String, Object> metadata
) {
}
