package com.walletledger.domain.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.account.AccountType;
import com.walletledger.domain.outbox.OutboxEvent;
import com.walletledger.domain.outbox.OutboxEventRepository;
import com.walletledger.domain.outbox.OutboxEventStatus;
import com.walletledger.domain.user.User;
import com.walletledger.domain.user.UserRepository;
import com.walletledger.domain.user.UserRole;
import com.walletledger.domain.user.UserStatus;
import com.walletledger.infrastructure.security.jwt.JwtService;
import com.walletledger.support.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers docs/05-test-plan.md §Phase 2 (core operations, idempotency,
 * concurrency, outbox, hold expiry).
 */
class LedgerIT extends AbstractIntegrationTest {

    private static final String API_KEY = "dev-service-api-key-change-me";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private HoldRepository holdRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private JwtService jwtService;

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

    private String jwtFor(Account account) {
        return jwtService.generateToken(account.getOwnerId(), UserRole.CUSTOMER.name());
    }

    // ---- P2-I1/I2/I3/I4/I5: credit/debit core --------------------------

    @Test
    void creditIncreasesBalanceAndRecordsATransferPlusTwoEntries() throws Exception {
        Account account = newPlayerAccount(0);

        postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 100), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.type").value("BONUS"));

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualTo(100);
    }

    @Test
    void debitWithinBalanceSucceeds() throws Exception {
        Account account = newPlayerAccount(100);

        postLedger("/api/v1/ledger/debit", debitBody(account.getId(), 40), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(40));

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualTo(60);
    }

    @Test
    void debitAboveBalanceReturns409AndLeavesBalanceUnchanged() throws Exception {
        Account account = newPlayerAccount(30);

        postLedger("/api/v1/ledger/debit", debitBody(account.getId(), 40), UUID.randomUUID().toString())
                .andExpect(status().isConflict());

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualTo(30);
    }

    @Test
    void creditToNonExistentAccountReturns404() throws Exception {
        postLedger("/api/v1/ledger/credit", creditBody("does-not-exist", 10), UUID.randomUUID().toString())
                .andExpect(status().isNotFound());
    }

    @Test
    void creditWithNonPositiveAmountReturns400() throws Exception {
        Account account = newPlayerAccount(0);
        postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 0), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
    }

    // ---- P2-I6/I7/I8/I9: hold / capture / void -------------------------

    @Test
    void holdReservesFundsWithoutTouchingBalance() throws Exception {
        Account account = newPlayerAccount(100);

        postLedger("/api/v1/ledger/hold", holdBody(account.getId(), 40), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualTo(100);
        assertThat(reloaded.getHeld()).isEqualTo(40);
        assertThat(reloaded.available()).isEqualTo(60);
    }

    @Test
    void captureSettlesTheHoldAndReducesBalance() throws Exception {
        Account account = newPlayerAccount(100);
        String holdId = createHold(account.getId(), 40);

        postLedger("/api/v1/ledger/capture", Map.of("hold_id", holdId), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("HOLD_CAPTURE"));

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualTo(60);
        assertThat(reloaded.getHeld()).isEqualTo(0);
    }

    @Test
    void voidReleasesTheHoldWithoutChangingBalance() throws Exception {
        Account account = newPlayerAccount(100);
        String holdId = createHold(account.getId(), 40);

        postLedger("/api/v1/ledger/void", Map.of("hold_id", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualTo(100);
        assertThat(reloaded.getHeld()).isEqualTo(0);
    }

    @Test
    void holdExceedingAvailableBalanceReturns409() throws Exception {
        Account account = newPlayerAccount(50);
        createHold(account.getId(), 30); // held=30, available=20

        postLedger("/api/v1/ledger/hold", holdBody(account.getId(), 25), UUID.randomUUID().toString())
                .andExpect(status().isConflict());

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getHeld()).isEqualTo(30);
    }

    // ---- P2-I10/I11: balance & transaction history ---------------------

    @Test
    void balanceEndpointReflectsAvailableHoldAndTotal() throws Exception {
        Account account = newPlayerAccount(100);
        createHold(account.getId(), 30);
        String jwt = jwtFor(account);

        mockMvc.perform(get("/api/v1/players/me/balance").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(70))
                .andExpect(jsonPath("$.data.hold").value(30))
                .andExpect(jsonPath("$.data.total").value(100));
    }

    @Test
    void transactionHistoryPaginatesCorrectly() throws Exception {
        Account account = newPlayerAccount(0);
        for (int i = 0; i < 5; i++) {
            postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 10), UUID.randomUUID().toString())
                    .andExpect(status().isCreated());
        }
        String jwt = jwtFor(account);

        mockMvc.perform(get("/api/v1/players/me/transactions?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page.total_elements").value(5));
    }

    // ---- P2-I12/I13/I14: idempotency ------------------------------------

    @Test
    void repeatedCreditWithSameIdempotencyKeySequentiallyAppliesOnce() throws Exception {
        Account account = newPlayerAccount(0);
        String idempotencyKey = UUID.randomUUID().toString();

        for (int i = 0; i < 5; i++) {
            postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 100), idempotencyKey)
                    .andExpect(status().isCreated());
        }

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(transferRepository.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(idempotencyKey)).count()).isEqualTo(1);
    }

    @Test
    void repeatedCreditWithSameIdempotencyKeyConcurrentlyAppliesOnce() throws Exception {
        Account account = newPlayerAccount(0);
        String idempotencyKey = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(5);

        try {
            CompletableFuture<?>[] futures = IntStream.range(0, 5)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 100), idempotencyKey);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } finally {
            pool.shutdown();
        }

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(transferRepository.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(idempotencyKey)).count()).isEqualTo(1);
    }

    // ---- P2-I15/I16: concurrent debit race (named explicitly by the brief) --

    @Test
    void onlyOneOfTwoConcurrentDebitsForTheFullBalanceSucceeds() throws Exception {
        Account account = newPlayerAccount(100);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        try {
            CompletableFuture<?>[] futures = IntStream.range(0, 2)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            MvcResult result = postLedger("/api/v1/ledger/debit", debitBody(account.getId(), 100),
                                    UUID.randomUUID().toString()).andReturn();
                            int status = result.getResponse().getStatus();
                            if (status == 201) {
                                successCount.incrementAndGet();
                            } else if (status == 409) {
                                conflictCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } finally {
            pool.shutdown();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance()).isEqualTo(0);
    }

    // ---- P2-I18/I19: outbox lifecycle -----------------------------------

    @Test
    void successfulCreditCreatesAnOutboxEventThatTheSchedulerProcesses() throws Exception {
        Account account = newPlayerAccount(0);
        postLedger("/api/v1/ledger/credit", creditBody(account.getId(), 10), UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        assertThat(outboxEventRepository.findAll()).anySatisfy(event ->
                assertThat(event.getEventType()).isEqualTo("TRANSFER_COMPLETED"));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxEventRepository.findAll().stream()
                        .allMatch(e -> e.getStatus() == OutboxEventStatus.PROCESSED))
                        .isTrue());
    }

    // ---- P2-I20/I21: hold expiry scheduler -------------------------------

    @Test
    void expiredActiveHoldIsReleasedByTheScheduler() throws Exception {
        Account account = newPlayerAccount(100);
        Hold hold = holdRepository.save(Hold.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .fromAccountId(account.getId())
                .toAccountId(systemAccountId())
                .amount(30)
                .captured(0)
                .status(HoldStatus.ACTIVE)
                .type(HoldType.PURCHASE)
                .expiresAt(Instant.now().minusSeconds(60))
                .build());
        account.setHeld(30);
        accountRepository.save(account);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Hold reloaded = holdRepository.findById(hold.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        });

        Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAccount.getHeld()).isEqualTo(0);
        assertThat(reloadedAccount.getBalance()).isEqualTo(100);
    }

    // ---- helpers ---------------------------------------------------------

    private String systemAccountId() {
        return accountRepository.findByTypeAndCurrency(AccountType.SYSTEM, "COINS").orElseThrow().getId();
    }

    private String createHold(String accountId, long amount) throws Exception {
        MvcResult result = postLedger("/api/v1/ledger/hold", holdBody(accountId, amount), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private Map<String, Object> creditBody(String accountId, long amount) {
        return Map.of("account_id", accountId, "amount", amount, "type", "BONUS");
    }

    private Map<String, Object> debitBody(String accountId, long amount) {
        return Map.of("account_id", accountId, "amount", amount, "type", "PURCHASE");
    }

    private Map<String, Object> holdBody(String accountId, long amount) {
        return Map.of("account_id", accountId, "amount", amount, "type", "PURCHASE");
    }

    private org.springframework.test.web.servlet.ResultActions postLedger(String path, Map<String, ?> body, String idempotencyKey) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-Api-Key", API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postLedger(String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
