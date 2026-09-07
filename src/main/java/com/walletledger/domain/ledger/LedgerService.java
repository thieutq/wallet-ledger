package com.walletledger.domain.ledger;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.account.AccountType;
import com.walletledger.domain.outbox.OutboxEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core double-entry ledger operations. Every method is idempotent (retried
 * calls with the same key return the original result) and locks the accounts
 * it touches in a fixed, id-sorted order to avoid deadlocks — see
 * docs/04-design-decisions.md §3/§4.
 *
 * <p>Idempotency is implemented via {@link TransactionTemplate} rather than
 * {@code @Transactional} self-invocation: a duplicate {@code idempotency_key}
 * aborts the whole transaction at the DB level, so the fallback re-query for
 * the original row must run in a fresh transaction, not the aborted one.
 */
@Service
public class LedgerService {

    private static final String DEFAULT_CURRENCY = "COINS";

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final EntryRepository entryRepository;
    private final HoldRepository holdRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TransactionTemplate transactionTemplate;

    public LedgerService(AccountRepository accountRepository,
                          TransferRepository transferRepository,
                          EntryRepository entryRepository,
                          HoldRepository holdRepository,
                          OutboxEventPublisher outboxEventPublisher,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.entryRepository = entryRepository;
        this.holdRepository = holdRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Transfer credit(TransferCommand cmd) {
        return transferRepository.findByIdempotencyKey(cmd.idempotencyKey())
                .orElseGet(() -> runIdempotent(cmd.idempotencyKey(), () -> doCredit(cmd)));
    }

    public Transfer debit(TransferCommand cmd) {
        return transferRepository.findByIdempotencyKey(cmd.idempotencyKey())
                .orElseGet(() -> runIdempotent(cmd.idempotencyKey(), () -> doDebit(cmd)));
    }

    public Hold hold(HoldCommand cmd) {
        return holdRepository.findByIdempotencyKey(cmd.idempotencyKey())
                .orElseGet(() -> runIdempotentHold(cmd.idempotencyKey(), () -> doHold(cmd)));
    }

    public Transfer capture(String holdId, String idempotencyKey) {
        return transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> runIdempotent(idempotencyKey, () -> doCapture(holdId, idempotencyKey)));
    }

    public Hold voidHold(String holdId) {
        return transactionTemplate.execute(status -> doVoid(holdId));
    }

    public Transfer refund(RefundCommand cmd) {
        return transferRepository.findByIdempotencyKey(cmd.idempotencyKey())
                .orElseGet(() -> runIdempotent(cmd.idempotencyKey(), () -> doRefund(cmd)));
    }

    // ---- credit / debit ------------------------------------------------

    private Transfer doCredit(TransferCommand cmd) {
        String currency = resolveCurrency(cmd.currency());
        Account system = resolveSystemAccount(currency);
        rejectSystemAccountAsTarget(cmd.accountId(), system);

        Map<String, Account> accounts = lockAccounts(system.getId(), cmd.accountId());
        Account from = accounts.get(system.getId());
        Account to = accounts.get(cmd.accountId());

        from.setBalance(from.getBalance() - cmd.amount());
        to.setBalance(to.getBalance() + cmd.amount());

        Transfer transfer = buildTransfer(cmd, currency, from.getId(), to.getId());
        persistTransfer(transfer, from, to, cmd.amount());

        outboxEventPublisher.publish("TRANSFER_COMPLETED", Map.of(
                "transferId", transfer.getId(),
                "fromAccountId", from.getId(),
                "toAccountId", to.getId(),
                "amount", cmd.amount(),
                "type", cmd.type().name()
        ));

        return transfer;
    }

    private Transfer doDebit(TransferCommand cmd) {
        String currency = resolveCurrency(cmd.currency());
        Account system = resolveSystemAccount(currency);
        rejectSystemAccountAsTarget(cmd.accountId(), system);

        Map<String, Account> accounts = lockAccounts(system.getId(), cmd.accountId());
        Account player = accounts.get(cmd.accountId());
        Account system2 = accounts.get(system.getId());

        if (player.available() < cmd.amount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient balance");
        }

        player.setBalance(player.getBalance() - cmd.amount());
        system2.setBalance(system2.getBalance() + cmd.amount());

        Transfer transfer = buildTransfer(cmd, currency, player.getId(), system2.getId());
        persistTransfer(transfer, player, system2, cmd.amount());

        outboxEventPublisher.publish("TRANSFER_COMPLETED", Map.of(
                "transferId", transfer.getId(),
                "fromAccountId", player.getId(),
                "toAccountId", system2.getId(),
                "amount", cmd.amount(),
                "type", cmd.type().name()
        ));

        return transfer;
    }

    private Transfer buildTransfer(TransferCommand cmd, String currency, String fromId, String toId) {
        return Transfer.builder()
                .idempotencyKey(cmd.idempotencyKey())
                .fromAccountId(fromId)
                .toAccountId(toId)
                .amount(cmd.amount())
                .currency(currency)
                .status(TransferStatus.COMPLETED)
                .type(cmd.type())
                .referenceId(cmd.referenceId())
                .createdBy(cmd.createdBy())
                .metadata(cmd.metadata())
                .build();
    }

    private void persistTransfer(Transfer transfer, Account from, Account to, long amount) {
        transferRepository.saveAndFlush(transfer);

        entryRepository.saveAll(List.of(
                Entry.builder().transferId(transfer.getId()).accountId(from.getId()).amount(-amount).build(),
                Entry.builder().transferId(transfer.getId()).accountId(to.getId()).amount(amount).build()
        ));

        accountRepository.save(from);
        accountRepository.save(to);
    }

    // ---- hold / capture / void -----------------------------------------

    private Hold doHold(HoldCommand cmd) {
        String currency = resolveCurrency(cmd.currency());
        Account system = resolveSystemAccount(currency);
        rejectSystemAccountAsTarget(cmd.accountId(), system);

        Account target = accountRepository.findByIdForUpdate(cmd.accountId())
                .orElseThrow(() -> notFound(cmd.accountId()));

        if (target.available() < cmd.amount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available balance to place hold");
        }

        target.setHeld(target.getHeld() + cmd.amount());
        accountRepository.save(target);

        Hold hold = Hold.builder()
                .idempotencyKey(cmd.idempotencyKey())
                .fromAccountId(target.getId())
                .toAccountId(system.getId())
                .amount(cmd.amount())
                .captured(0)
                .status(HoldStatus.ACTIVE)
                .type(cmd.type())
                .referenceId(cmd.referenceId())
                .createdBy(cmd.createdBy())
                .metadata(cmd.metadata())
                .expiresAt(cmd.expiresAt())
                .build();

        holdRepository.saveAndFlush(hold);
        return hold;
    }

    private Transfer doCapture(String holdId, String idempotencyKey) {
        Hold hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found: " + holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold is not active: " + hold.getStatus());
        }

        long amount = hold.getAmount() - hold.getCaptured();
        Map<String, Account> accounts = lockAccounts(hold.getFromAccountId(), hold.getToAccountId());
        Account from = accounts.get(hold.getFromAccountId());
        Account to = accounts.get(hold.getToAccountId());

        from.setBalance(from.getBalance() - amount);
        from.setHeld(from.getHeld() - amount);
        to.setBalance(to.getBalance() + amount);

        Transfer transfer = Transfer.builder()
                .idempotencyKey(idempotencyKey)
                .fromAccountId(from.getId())
                .toAccountId(to.getId())
                .amount(amount)
                .currency(DEFAULT_CURRENCY)
                .status(TransferStatus.COMPLETED)
                .type(TransferType.HOLD_CAPTURE)
                .referenceId(hold.getId())
                .createdBy(hold.getCreatedBy())
                .build();

        persistTransfer(transfer, from, to, amount);

        hold.setCaptured(hold.getCaptured() + amount);
        hold.setStatus(HoldStatus.CAPTURED);
        hold.setCaptureTransferId(transfer.getId());
        holdRepository.save(hold);

        outboxEventPublisher.publish("HOLD_CAPTURED", Map.of(
                "holdId", hold.getId(),
                "transferId", transfer.getId(),
                "amount", amount
        ));

        return transfer;
    }

    private Hold doVoid(String holdId) {
        Hold hold = holdRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found: " + holdId));

        if (hold.getStatus() == HoldStatus.VOIDED) {
            return hold; // already voided — idempotent no-op
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold is not active: " + hold.getStatus());
        }

        Account target = accountRepository.findByIdForUpdate(hold.getFromAccountId())
                .orElseThrow(() -> notFound(hold.getFromAccountId()));

        target.setHeld(target.getHeld() - hold.getAmount());
        accountRepository.save(target);

        hold.setStatus(HoldStatus.VOIDED);
        return holdRepository.save(hold);
    }

    // ---- refund ----------------------------------------------------------

    private Transfer doRefund(RefundCommand cmd) {
        Transfer original = transferRepository.findById(cmd.originalTransferId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Transfer not found: " + cmd.originalTransferId()));

        if (original.getType() == TransferType.REFUND) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot refund a REFUND transfer");
        }
        if (original.getStatus() != TransferStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Original transfer is not COMPLETED: " + original.getStatus());
        }

        long alreadyRefunded = transferRepository.sumAmountByTypeAndReferenceId(original.getId(), TransferType.REFUND);
        long remaining = original.getAmount() - alreadyRefunded;
        long amount = cmd.amount() != null ? cmd.amount() : remaining;

        if (amount <= 0 || amount > remaining) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invalid refund amount: requested=" + amount + ", remaining=" + remaining);
        }

        Map<String, Account> accounts = lockAccounts(original.getFromAccountId(), original.getToAccountId());
        Account from = accounts.get(original.getToAccountId());
        Account to = accounts.get(original.getFromAccountId());

        if (from.getType() != AccountType.SYSTEM && from.available() < amount) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient balance to refund");
        }

        from.setBalance(from.getBalance() - amount);
        to.setBalance(to.getBalance() + amount);

        Transfer refund = Transfer.builder()
                .idempotencyKey(cmd.idempotencyKey())
                .fromAccountId(from.getId())
                .toAccountId(to.getId())
                .amount(amount)
                .currency(original.getCurrency())
                .status(TransferStatus.COMPLETED)
                .type(TransferType.REFUND)
                .referenceId(original.getId())
                .metadata(cmd.metadata())
                .build();

        persistTransfer(refund, from, to, amount);

        outboxEventPublisher.publish("REFUND_COMPLETED", Map.of(
                "transferId", refund.getId(),
                "originalTransferId", original.getId(),
                "amount", amount
        ));

        return refund;
    }

    // ---- shared helpers --------------------------------------------------

    private Transfer runIdempotent(String idempotencyKey, java.util.function.Supplier<Transfer> action) {
        try {
            return transactionTemplate.execute(status -> action.get());
        } catch (DataIntegrityViolationException e) {
            return transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        }
    }

    private Hold runIdempotentHold(String idempotencyKey, java.util.function.Supplier<Hold> action) {
        try {
            return transactionTemplate.execute(status -> action.get());
        } catch (DataIntegrityViolationException e) {
            return holdRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
        }
    }

    private Map<String, Account> lockAccounts(String... ids) {
        Map<String, Account> result = new LinkedHashMap<>();
        java.util.Arrays.stream(ids).distinct().sorted().forEach(id ->
                result.put(id, accountRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id))));
        return result;
    }

    private Account resolveSystemAccount(String currency) {
        return accountRepository.findByTypeAndCurrency(AccountType.SYSTEM, currency)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "System account not configured for currency " + currency));
    }

    private void rejectSystemAccountAsTarget(String accountId, Account systemAccount) {
        if (accountId.equals(systemAccount.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot target the system account directly");
        }
    }

    private String resolveCurrency(String requested) {
        String currency = requested != null ? requested : DEFAULT_CURRENCY;
        if (!DEFAULT_CURRENCY.equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported currency: " + currency);
        }
        return currency;
    }

    private ResponseStatusException notFound(String accountId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
    }
}
