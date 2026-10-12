package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.repository.OperatorRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** Internal provisioning operations; intentionally not exposed through an HTTP controller. */
@Service
public class AccountService {
    private final AccountRepository accounts;
    private final OperatorRepository operators;
    private final PasswordEncoder passwords;

    public AccountService(AccountRepository accounts, OperatorRepository operators, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.operators = operators;
        this.passwords = passwords;
    }

    @Transactional
    public AccountSummary createAdministrator(String username, String password) {
        return create(username, password, AccountRole.ADMIN, null, false);
    }

    @Transactional
    public AccountSummary createDriver(String username, String password, Integer operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw new IllegalArgumentException("A valid operator ID is required for a driver account.");
        }
        Operator operator = operators.findById(operatorId)
                .orElseThrow(() -> new IllegalArgumentException("Operator does not exist."));
        if (accounts.existsByOperatorOperatorId(operatorId)) {
            throw new IllegalArgumentException("Operator already has an account.");
        }
        return create(username, password, AccountRole.DRIVER, operator, false);
    }

    @Transactional
    public Optional<AccountSummary> provisionInitialAdministrator(String username, String password) {
        if (hasAdministrator()) {
            return Optional.empty();
        }
        return Optional.of(create(username, password, AccountRole.ADMIN, null, true));
    }

    public boolean hasAdministrator() {
        return accounts.existsByRole(AccountRole.ADMIN);
    }

    private AccountSummary create(String username, String password, AccountRole role, Operator operator,
                                  boolean initialAdmin) {
        String normalized = normalizeUsername(username);
        validatePassword(password);
        if (accounts.existsByUsername(normalized)) {
            throw new IllegalArgumentException("Account username is already in use.");
        }
        var account = new UserAccount(normalized, passwords.encode(password), role, operator, initialAdmin);
        // Flush here so uniqueness and FK failures occur within the provisioning transaction.
        return AccountSummary.from(accounts.saveAndFlush(account));
    }

    static String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("Username must be 3–64 letters, digits, dots, underscores or hyphens.");
        }
        return normalized;
    }

    static void validatePassword(String password) {
        if (password != null) {
            for (int i = 0; i < password.length(); i++) {
                char character = password.charAt(i);
                if (Character.isHighSurrogate(character)) {
                    if (i + 1 >= password.length() || !Character.isLowSurrogate(password.charAt(i + 1))) {
                        throw new IllegalArgumentException("Password contains invalid Unicode.");
                    }
                    i++;
                } else if (Character.isLowSurrogate(character)) {
                    throw new IllegalArgumentException("Password contains invalid Unicode.");
                }
            }
        }
        if (password == null || password.isBlank() || password.codePointCount(0, password.length()) < 15
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must contain at least 15 characters and at most 72 UTF-8 bytes.");
        }
    }
}
