package com.walletledger.domain.ledger;

import java.util.Map;

/**
 * Internal input to {@link LedgerService#refund} — decoupled from the
 * web-layer request DTO, same pattern as {@link TransferCommand}.
 */
public record RefundCommand(
        String originalTransferId,
        Long amount,
        Map<String, Object> metadata,
        String idempotencyKey
) {
}
