package com.walletledger.domain.reconciliation;

import com.walletledger.domain.reconciliation.dto.AccountDriftResponse;
import com.walletledger.domain.reconciliation.dto.ReconciliationResponse;
import com.walletledger.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Admin-only operational endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @Operation(summary = "Run ledger reconciliation on demand and return the result")
    @GetMapping
    public ResponseEntity<ApiResponse<ReconciliationResponse>> reconcile() {
        ReconciliationReport report = reconciliationService.run();
        ReconciliationResponse response = new ReconciliationResponse(
                report.isHealthy(),
                report.globalEntriesSum(),
                report.driftedAccounts().stream()
                        .map(d -> new AccountDriftResponse(d.accountId(), d.storedBalance(), d.ledgerBalance()))
                        .toList());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK.value(), response));
    }
}
