package com.walletledger.domain.player;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.ledger.Transfer;
import com.walletledger.domain.ledger.TransferRepository;
import com.walletledger.domain.ledger.dto.TransferResponse;
import com.walletledger.domain.ledger.mapper.LedgerMapper;
import com.walletledger.domain.player.dto.BalanceResponse;
import com.walletledger.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/players/me")
@RequiredArgsConstructor
@Tag(name = "Players", description = "Player-facing wallet self-service (token auth)")
// JWT claim `role` is a UserRole (CUSTOMER|ADMIN|AUDITOR) -> authority ROLE_CUSTOMER for a
// real player; there is no separate "USER" role anywhere in the system.
@PreAuthorize("hasRole('CUSTOMER')")
public class PlayerController {

    private static final String CURRENCY = "COINS";

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerMapper ledgerMapper;

    @Operation(summary = "Get the authenticated player's current balance")
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(Authentication authentication) {
        Account account = resolveAccount(authentication);
        BalanceResponse response = new BalanceResponse(account.available(), account.getHeld(), account.getBalance(), account.getCurrency());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK.value(), response));
    }

    @Operation(summary = "Get the authenticated player's paginated transaction history")
    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<PagedModel<TransferResponse>>> getTransactions(Authentication authentication, Pageable pageable) {
        Account account = resolveAccount(authentication);
        Page<Transfer> transfers = transferRepository.findByFromAccountIdOrToAccountId(account.getId(), account.getId(), pageable);
        Page<TransferResponse> responses = transfers.map(ledgerMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK.value(), new PagedModel<>(responses)));
    }

    private Account resolveAccount(Authentication authentication) {
        String userId = authentication.getName();
        return accountRepository.findByOwnerIdAndCurrency(userId, CURRENCY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No wallet found for this player"));
    }
}
