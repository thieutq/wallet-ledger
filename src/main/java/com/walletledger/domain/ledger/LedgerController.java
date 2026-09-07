package com.walletledger.domain.ledger;

import com.walletledger.domain.ledger.dto.CreditRequest;
import com.walletledger.domain.ledger.dto.DebitRequest;
import com.walletledger.domain.ledger.dto.HoldIdRequest;
import com.walletledger.domain.ledger.dto.HoldRequest;
import com.walletledger.domain.ledger.dto.HoldResponse;
import com.walletledger.domain.ledger.dto.RefundRequest;
import com.walletledger.domain.ledger.dto.TransferResponse;
import com.walletledger.domain.ledger.mapper.LedgerMapper;
import com.walletledger.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "System/admin-facing money movement (requires X-Api-Key + Idempotency-Key)")
@PreAuthorize("hasRole('SERVICE')")
public class LedgerController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final LedgerService ledgerService;
    private final LedgerMapper ledgerMapper;

    @Operation(summary = "Credit funds into a player's wallet")
    @PostMapping("/credit")
    public ResponseEntity<ApiResponse<TransferResponse>> credit(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank String idempotencyKey,
            @Valid @RequestBody CreditRequest request) {
        Transfer transfer = ledgerService.credit(toCommand(request, idempotencyKey));
        return created(transfer);
    }

    @Operation(summary = "Debit funds from a player's wallet, rejecting if insufficient")
    @PostMapping("/debit")
    public ResponseEntity<ApiResponse<TransferResponse>> debit(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank String idempotencyKey,
            @Valid @RequestBody DebitRequest request) {
        Transfer transfer = ledgerService.debit(toCommand(request, idempotencyKey));
        return created(transfer);
    }

    @Operation(summary = "Reserve funds without moving them (two-phase debit)")
    @PostMapping("/hold")
    public ResponseEntity<ApiResponse<HoldResponse>> hold(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {
        Hold hold = ledgerService.hold(new HoldCommand(
                request.accountId(), request.amount(), request.currency(), request.type(),
                request.referenceId(), null, request.metadata(), request.expiresAt(), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED.value(), ledgerMapper.toResponse(hold)));
    }

    @Operation(summary = "Confirm a hold, debiting the reserved funds and closing it")
    @PostMapping("/capture")
    public ResponseEntity<ApiResponse<TransferResponse>> capture(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank String idempotencyKey,
            @Valid @RequestBody HoldIdRequest request) {
        Transfer transfer = ledgerService.capture(request.holdId(), idempotencyKey);
        return created(transfer);
    }

    @Operation(summary = "Cancel a hold, releasing the reserved funds")
    @PostMapping("/void")
    public ResponseEntity<ApiResponse<HoldResponse>> voidHold(@Valid @RequestBody HoldIdRequest request) {
        Hold hold = ledgerService.voidHold(request.holdId());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK.value(), ledgerMapper.toResponse(hold)));
    }

    @Operation(summary = "Refund a completed transfer, fully or partially")
    @PostMapping("/refund")
    public ResponseEntity<ApiResponse<TransferResponse>> refund(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {
        Transfer transfer = ledgerService.refund(new RefundCommand(
                request.originalTransferId(), request.amount(), request.metadata(), idempotencyKey));
        return created(transfer);
    }

    private ResponseEntity<ApiResponse<TransferResponse>> created(Transfer transfer) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED.value(), ledgerMapper.toResponse(transfer)));
    }

    private TransferCommand toCommand(CreditRequest request, String idempotencyKey) {
        return new TransferCommand(request.accountId(), request.amount(), request.currency(), request.type(),
                request.referenceId(), null, request.metadata(), idempotencyKey);
    }

    private TransferCommand toCommand(DebitRequest request, String idempotencyKey) {
        return new TransferCommand(request.accountId(), request.amount(), request.currency(), request.type(),
                request.referenceId(), null, request.metadata(), idempotencyKey);
    }
}
