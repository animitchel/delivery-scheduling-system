package com.mmu.schedulingapp.account;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final AccountRepository accounts;

    public AccountUserDetailsService(AccountRepository accounts) { this.accounts = accounts; }

    @Override
    @Transactional(readOnly = true)
    public AccountPrincipal loadUserByUsername(String username) {
        final String normalized;
        try { normalized = AccountService.normalizeUsername(username); }
        catch (IllegalArgumentException exception) { throw new UsernameNotFoundException("Invalid credentials."); }
        return accounts.findByUsername(normalized).map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    }
}
