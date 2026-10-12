package com.mmu.schedulingapp.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
public class PasswordChangeController {
    private final AccountManagementService management;
    public PasswordChangeController(AccountManagementService management) { this.management = management; }

    @PostMapping("/api/auth/password")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void change(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody AccountRequests.ChangePassword body,
                       HttpServletRequest request, HttpServletResponse response) {
        management.changeOwnPassword(actor, body.currentPassword(), body.newPassword());
        new SecurityContextLogoutHandler().logout(request, response, null);
        new org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler("JSESSIONID").logout(request, response, null);
    }
}
