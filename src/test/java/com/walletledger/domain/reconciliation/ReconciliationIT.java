package com.walletledger.domain.reconciliation;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.account.AccountType;
import com.walletledger.domain.ledger.AccountTransferCommand;
import com.walletledger.domain.ledger.Hold;
import com.walletledger.domain.ledger.HoldCommand;
import com.walletledger.domain.ledger.HoldType;
import com.walletledger.domain.ledger.LedgerService;
import com.walletledger.domain.ledger.RefundCommand;
import com.walletledger.domain.ledger.Transfer;
import com.walletledger.domain.ledger.TransferCommand;
import com.walletledger.domain.ledger.TransferType;
import com.walletledger.domain.user.User;
import com.walletledger.domain.user.UserRepository;
import com.walletledger.domain.user.UserRole;
import com.walletledger.domain.user.UserStatus;
import com.walletledger.infrastructure.security.jwt.JwtService;
import com.walletledger.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers docs/05-test-plan.md §Phase 7: independent re-verification of the
 * ledger, on top of the app-level checks and the Phase 2.5 DB trigger.
 */
class ReconciliationIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JwtService jwtService;
    @Autowired private DataSource dataSource;

    @Test
    void ledgerStaysReconciledAfterAMixOfOperations() throws Exception {
        Account alice = newPlayerAccount(0);
        Account bob = newPlayerAccount(0);
        Account carol = newPlayerAccount(0);

        ledgerService.credit(new TransferCommand(alice.getId(), 200, null, TransferType.BONUS, null, null, null, key()));
        ledgerService.transfer(new AccountTransferCommand(alice.getId(), bob.getId(), 80, null, null, null, key()));
        Transfer debit = ledgerService.debit(new TransferCommand(bob.getId(), 20, null, TransferType.PURCHASE, null, null, null, key()));
        ledgerService.credit(new TransferCommand(carol.getId(), 100, null, TransferType.BONUS, null, null, null, key()));
        Hold hold = ledgerService.hold(new HoldCommand(carol.getId(), 30, null, HoldType.PURCHASE, null, null, null, null, key()));
        ledgerService.capture(hold.getId(), key());
        ledgerService.refund(new RefundCommand(debit.getId(), null, null, key()));

        // Scoped, not global isHealthy(): this shared Postgres/Spring context
        // also runs LedgerIT, whose fixtures seed accounts with a nonzero
        // balance and no backing entries (a deliberate, harmless test
        // shortcut) — those legitimately show up as "drifted" to a global
        // scan. globalEntriesSum, though, is a true system-wide invariant
        // regardless of that noise: every properly-committed transfer
        // anywhere is a balanced pair, so it must always net to zero.
        List<String> myAccountIds = List.of(alice.getId(), bob.getId(), carol.getId());
        ReconciliationReport report = reconciliationService.run();
        assertThat(report.globalEntriesSum()).isEqualTo(0);
        assertThat(report.driftedAccounts()).noneMatch(d -> myAccountIds.contains(d.accountId()));
    }

    @Test
    void ledgerStaysReconciledAfterConcurrentTransfers() throws Exception {
        Account alice = newPlayerAccount(0);
        Account bob = newPlayerAccount(0);
        // Seed starting balances through the ledger itself (not a direct
        // repository save) so these accounts are entirely entries-backed —
        // otherwise the seed amount itself would look like drift.
        ledgerService.credit(new TransferCommand(alice.getId(), 1000, null, TransferType.BONUS, null, null, null, key()));
        ledgerService.credit(new TransferCommand(bob.getId(), 1000, null, TransferType.BONUS, null, null, null, key()));
        ExecutorService pool = Executors.newFixedThreadPool(8);

        try {
            CompletableFuture<?>[] futures = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        String from = i % 2 == 0 ? alice.getId() : bob.getId();
                        String to = i % 2 == 0 ? bob.getId() : alice.getId();
                        ledgerService.transfer(new AccountTransferCommand(from, to, 10, null, null, null, key()));
                    }, pool))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } finally {
            pool.shutdown();
        }

        List<String> myAccountIds = List.of(alice.getId(), bob.getId());
        ReconciliationReport report = reconciliationService.run();
        assertThat(report.globalEntriesSum()).isEqualTo(0);
        assertThat(report.driftedAccounts()).noneMatch(d -> myAccountIds.contains(d.accountId()));
    }

    @Test
    void reconciliationDetectsBalanceDriftThatTheDbTriggerCannotCatch() throws Exception {
        Account account = newPlayerAccount(0);
        ledgerService.credit(new TransferCommand(account.getId(), 500, null, TransferType.BONUS, null, null, null, key()));

        // Bypass LedgerService entirely: corrupt the stored balance directly,
        // never touching `entries` — the Phase 2.5 trigger only watches
        // `entries`, so it has nothing to fire on here.
        setBalanceDirectly(account.getId(), 999);
        try {
            ReconciliationReport report = reconciliationService.run();

            assertThat(report.isHealthy()).isFalse();
            assertThat(report.driftedAccounts()).anySatisfy(drift -> {
                assertThat(drift.accountId()).isEqualTo(account.getId());
                assertThat(drift.storedBalance()).isEqualTo(999);
                assertThat(drift.ledgerBalance()).isEqualTo(500);
            });
        } finally {
            // Restore, so later reconciliation checks in this run (or a
            // scheduler tick) aren't polluted by this deliberate corruption.
            setBalanceDirectly(account.getId(), 500);
        }
    }

    @Test
    void reconciliationEndpointRequiresAdminRole() throws Exception {
        Account account = newPlayerAccount(0);
        String customerJwt = jwtService.generateToken(account.getOwnerId(), UserRole.CUSTOMER.name());

        mockMvc.perform(get("/api/v1/admin/reconciliation").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciliationEndpointReturnsAReportWithNoGlobalImbalance() throws Exception {
        // Not asserting $.data.healthy here: this shared DB also carries
        // LedgerIT's fixture accounts (nonzero balance, no backing entries
        // — see the comment in ledgerStaysReconciledAfterAMixOfOperations),
        // which legitimately show up as drifted to a global scan. The one
        // invariant that's always true regardless is the global entries sum.
        mockMvc.perform(get("/api/v1/admin/reconciliation").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.global_entries_sum").value(0));
    }

    // ---- helpers ---------------------------------------------------------

    private Account newPlayerAccount(long initialBalance) {
        User user = userRepository.save(User.builder()
                .username("player-" + UUID.randomUUID())
                .passwordHash("unused")
                .role(UserRole.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
        return accountRepository.save(Account.builder()
                .ownerId(user.getId())
                .type(AccountType.PLAYER)
                .currency("COINS")
                .balance(initialBalance)
                .held(0)
                .build());
    }

    private String adminJwt() {
        User admin = userRepository.save(User.builder()
                .username("admin-" + UUID.randomUUID())
                .passwordHash("unused")
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
        return jwtService.generateToken(admin.getId(), UserRole.ADMIN.name());
    }

    private void setBalanceDirectly(String accountId, long balance) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
            ps.setLong(1, balance);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
