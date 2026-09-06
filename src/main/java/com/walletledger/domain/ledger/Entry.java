package com.walletledger.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per side of a transfer. Amount is signed: negative = debit,
 * positive = credit. The two rows of a transfer always sum to zero
 * (double-entry) — enforced at the DB level by the Phase 2.5 trigger.
 */
@Entity
@Table(name = "entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "transfer_id", nullable = false)
    private String transferId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
