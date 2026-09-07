package com.walletledger.domain.ledger;

import java.util.Map;

/**
 * Internal input to {@link LedgerService#transfer} — a direct player-to-player
 * move, unlike {@link TransferCommand} which always has the SYSTEM account as
 * the implicit other side.
 */
public record AccountTransferCommand(
        String fromAccountId,
        String toAccountId,
        long amount,
        String currency,
        String referenceId,
        Map<String, Object> metadata,
        String idempotencyKey
) {
}
