package com.walletledger.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "holds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "from_account_id", nullable = false)
    private String fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private String toAccountId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long captured;

    // Converted to/from lowercase DB values by HoldStatusConverter (autoApply).
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HoldType type;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(name = "created_by")
    private String createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "capture_transfer_id")
    private String captureTransferId;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
