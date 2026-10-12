package com.mmu.schedulingapp.security;

import com.mmu.schedulingapp.account.AccountPrincipal;
import com.mmu.schedulingapp.account.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Revoke stale identities before checking privileges; not a servlet-container filter bean. */
final class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountRepository accounts;

    AccountSessionFilter(AccountRepository accounts) { this.accounts = accounts; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
            if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
                revoke(request, response);
                return;
            }
            var account = accounts.findById(principal.getAccountId());
            boolean current = account.isPresent() && account.get().isEnabled()
                    && account.get().getUsername().equals(principal.getUsername())
                    && authentication.getAuthorities().stream().anyMatch(authority ->
                    authority.getAuthority().equals("ROLE_" + account.get().getRole().name()));
            if (!current) {
                revoke(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void revoke(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityResponses.unauthenticated(request, response);
    }
}
