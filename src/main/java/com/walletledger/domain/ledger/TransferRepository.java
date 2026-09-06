package com.walletledger.domain.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    Page<Transfer> findByFromAccountIdOrToAccountId(String fromAccountId, String toAccountId, Pageable pageable);
}
