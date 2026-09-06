package com.walletledger.domain.ledger.dto;

import jakarta.validation.constraints.NotBlank;

public record HoldIdRequest(
        @NotBlank String holdId
) {
}
