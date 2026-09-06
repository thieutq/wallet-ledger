package com.walletledger.domain.ledger;

/**
 * DB stores these lowercase ({@code holds_status_valid} CHECK constraint) —
 * see {@link HoldStatusConverter} for the mapping.
 */
public enum HoldStatus {
    ACTIVE,
    CAPTURED,
    VOIDED,
    EXPIRED
}
