package com.walletledger.domain.reconciliation;

import com.walletledger.domain.ledger.EntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final EntryRepository entryRepository;

    @Transactional(readOnly = true)
    public ReconciliationReport run() {
        long globalEntriesSum = entryRepository.sumAllEntries();
        var driftedAccounts = entryRepository.findDriftedAccounts().stream()
                .map(p -> new AccountDrift(p.getId(), p.getStoredBalance(), p.getLedgerBalance()))
                .toList();
        return new ReconciliationReport(globalEntriesSum, driftedAccounts);
    }
}
