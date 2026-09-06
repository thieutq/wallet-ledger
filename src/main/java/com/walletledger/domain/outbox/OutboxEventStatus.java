package com.walletledger.domain.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSED,
    FAILED
}
