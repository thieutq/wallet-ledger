package com.walletledger.domain.player.dto;

public record BalanceResponse(
        long available,
        long hold,
        long total,
        String currency
) {
}
