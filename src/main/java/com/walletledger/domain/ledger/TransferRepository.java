package com.walletledger.domain.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    Page<Transfer> findByFromAccountIdOrToAccountId(String fromAccountId, String toAccountId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t WHERE t.type = :type AND t.referenceId = :originalTransferId")
    long sumAmountByTypeAndReferenceId(@Param("originalTransferId") String originalTransferId, @Param("type") TransferType type);
}
