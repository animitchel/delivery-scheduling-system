package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;

/** A detached principal; the provider erases its password before saving the session. */
public final class AccountPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final Long accountId;

    AccountPrincipal(UserAccount account) {
        super(account.getUsername(), account.getPasswordHash(), account.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
        this.accountId = account.getId();
    }

    public Long getAccountId() { return accountId; }

    @Override
    @JsonIgnore
    public String getPassword() { return super.getPassword(); }
}
