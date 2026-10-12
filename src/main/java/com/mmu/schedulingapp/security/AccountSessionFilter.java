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
                    && account.get().getAuthenticationRevision() >= 0
                    && account.get().getAuthenticationRevision() == principal.getAuthenticationRevision()
                    && account.get().isPasswordChangeRequired() == principal.isPasswordChangeRequired()
                    && (!account.get().isPasswordChangeRequired()
                        || (account.get().getTemporaryPasswordExpiresAt() != null && account.get().getTemporaryPasswordExpiresAt().isAfter(java.time.Instant.now())))
                    && account.get().getUsername().equals(principal.getUsername())
                    && authentication.getAuthorities().stream().anyMatch(authority ->
                    authority.getAuthority().equals("ROLE_" + account.get().getRole().name()));
            if (!current) {
                revoke(request, response);
                return;
            }
            if (account.get().isPasswordChangeRequired() && !allowedDuringPasswordSetup(request)) {
                if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                    SecurityResponses.json(response, 403, "{\"error\":\"Password change required.\",\"passwordChangeRequired\":true}");
                } else response.sendRedirect(request.getContextPath() + "/html/change-password.html");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedDuringPasswordSetup(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod())) return "/api/auth/password".equals(path);
        return "GET".equals(request.getMethod()) && java.util.Set.of("/api/auth/me", "/api/auth/csrf",
                "/html/change-password.html", "/js/password.js", "/js/auth.js", "/css/login.css").contains(path);
    }

    private static void revoke(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        SecurityResponses.unauthenticated(request, response);
    }
}
