package com.walletledger.domain.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, String> {

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Entry e")
    long sumAllEntries();

    // Accounts whose stored balance disagrees with what their own entries sum
    // to — the DB trigger (Phase 2.5) only watches `entries`, so a direct
    // UPDATE to accounts.balance bypasses it entirely; this is what catches
    // that gap. Native, since Account/Entry aren't JPA-relationship-mapped.
    @Query(value = "SELECT a.id AS id, a.balance AS storedBalance, COALESCE(SUM(e.amount), 0) AS ledgerBalance "
            + "FROM accounts a LEFT JOIN entries e ON e.account_id = a.id "
            + "GROUP BY a.id, a.balance "
            + "HAVING a.balance <> COALESCE(SUM(e.amount), 0)", nativeQuery = true)
    List<AccountDriftProjection> findDriftedAccounts();
}
