package com.mmu.schedulingapp.account;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AccountAuthenticationProvider extends DaoAuthenticationProvider {
    public AccountAuthenticationProvider(AccountUserDetailsService users, PasswordEncoder passwords) {
        super(users);
        setPasswordEncoder(passwords);
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken authentication) {
        try {
            AccountService.validatePassword(authentication.getCredentials() == null
                    ? null : authentication.getCredentials().toString());
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid credentials.");
        }
        super.additionalAuthenticationChecks(user, authentication);
    }
}
