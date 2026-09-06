package com.walletledger.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EntryRepository extends JpaRepository<Entry, String> {
}
