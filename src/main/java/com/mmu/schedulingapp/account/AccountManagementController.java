package com.mmu.schedulingapp.account;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/accounts")
public class AccountManagementController {
    private final AccountManagementService management;
    public AccountManagementController(AccountManagementService management) { this.management = management; }

    public record AccountPage(java.util.List<AccountSummary> accounts, int page, int size, long totalElements, int totalPages) { }
    @GetMapping public AccountPage accounts(@AuthenticationPrincipal AccountPrincipal actor,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        var result = management.list(actor, page, size);
        return new AccountPage(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    public record OperatorPage(java.util.List<AccountManagementService.EligibleOperator> operators,
                               int page, int size, long totalElements, int totalPages) { }
    @GetMapping("/eligible-operators")
    public OperatorPage eligible(@AuthenticationPrincipal AccountPrincipal actor,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size,
                                 @RequestParam(defaultValue = "") String search) {
        var result = management.eligibleOperators(actor, page, size, search);
        return new OperatorPage(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/drivers")
    public ResponseEntity<AccountManagementService.TemporaryCredential> create(@AuthenticationPrincipal AccountPrincipal actor,
                                                                              @Valid @RequestBody AccountRequests.CreateDriver request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(management.createDriver(actor, request.currentPassword(), request.username(), request.operatorId()));
    }
    @PatchMapping("/{id}/enabled")
    public AccountSummary enabled(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable Long id,
                                  @Valid @RequestBody AccountRequests.Enabled request) {
        return management.enabled(actor, request.currentPassword(), id, request.enabled());
    }
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<AccountManagementService.TemporaryCredential> reset(@AuthenticationPrincipal AccountPrincipal actor,
                                                                             @PathVariable Long id,
                                                                             @Valid @RequestBody AccountRequests.Reauthenticated request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(management.reset(actor, request.currentPassword(), id));
    }
}
