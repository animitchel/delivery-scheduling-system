package com.mmu.schedulingapp.security;

import com.mmu.schedulingapp.account.AccountPrincipal;
import com.mmu.schedulingapp.account.AccountRepository;
import com.mmu.schedulingapp.account.AccountSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {
    private final AccountRepository accounts;

    public AuthenticationController(AccountRepository accounts) { this.accounts = accounts; }

    public record CsrfResponse(String headerName, String parameterName, String token) { }

    @GetMapping("/api/auth/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @GetMapping("/api/auth/me")
    @Transactional(readOnly = true)
    public AccountSummary identity(@AuthenticationPrincipal AccountPrincipal principal) {
        var account = accounts.findById(principal.getAccountId()).orElseThrow();
        return new AccountSummary(account.getId(), account.getUsername(), account.getRole(),
                account.getOperator() == null ? null : account.getOperator().getOperatorId(), account.isEnabled());
    }
}
