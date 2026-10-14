package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.repository.OperatorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class AccountManagementService {
    private final AccountRepository accounts;
    private final OperatorRepository operators;
    private final AccountAuditRepository audits;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entities;
    private final SecureRandom random = new SecureRandom();

    public AccountManagementService(AccountRepository accounts, OperatorRepository operators,
                                    AccountAuditRepository audits, PasswordEncoder passwords, Clock clock, jakarta.persistence.EntityManager entities) {
        this.accounts = accounts; this.operators = operators; this.audits = audits;
        this.passwords = passwords; this.clock = clock; this.entities = entities;
    }

    public record TemporaryCredential(AccountSummary account, String temporaryPassword, Instant expiresAt) {
        @Override public String toString() { return "TemporaryCredential[REDACTED]"; }
    }

    @Transactional(readOnly = true)
    public Page<AccountSummary> list(AccountPrincipal actor, int page, int size) {
        requireAdministrator(current(actor, false));
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page bounds.");
        return accounts.findAll(PageRequest.of(page, size, Sort.by("id"))).map(AccountSummary::from);
    }

    public record EligibleOperator(Integer operatorId, String name) { }

    @Transactional(readOnly = true)
    public Page<EligibleOperator> eligibleOperators(AccountPrincipal actor, int page, int size, String search) {
        requireAdministrator(current(actor, false));
        if (page < 0 || size < 1 || size > 100 || search == null || search.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operator search bounds.");
        }
        return operators.findEligibleForAccount(search.strip(), PageRequest.of(page, size, Sort.by("name", "operatorId")))
                .map(operator -> new EligibleOperator(operator.getOperatorId(), operator.getName()));
    }

    // Hibernate 7 may refresh an already locked row with a non-locking read.
    // READ_COMMITTED keeps that read current; explicit row locks still serialize credential writes.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemporaryCredential createDriver(AccountPrincipal actor, String currentPassword, String username, Integer operatorId) {
        administrator(actor, currentPassword);
        String normalized = AccountService.normalizeUsername(username);
        if (operatorId == null || operatorId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operator.");
        var operator = operators.lockById(operatorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Operator not found."));
        if (accounts.existsByUsername(normalized) || accounts.existsByOperatorOperatorId(operatorId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or operator already has an account.");
        }
        String temporary = temporaryPassword();
        Instant expiry = clock.instant().plus(Duration.ofHours(24));
        var account = new UserAccount(normalized, passwords.encode(temporary), AccountRole.DRIVER, operator, false);
        account.setTemporaryPassword(account.getPasswordHash(), expiry);
        accounts.saveAndFlush(account);
        audit(actor.getAccountId(), account.getId(), "DRIVER_CREATED");
        return new TemporaryCredential(AccountSummary.from(account), temporary, expiry);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountSummary enabled(AccountPrincipal actor, String currentPassword, Long id, boolean enabled) {
        administrator(actor, currentPassword);
        var account = driverForUpdate(id);
        if (account.isEnabled() != enabled) {
            account.changeEnabled(enabled);
            accounts.saveAndFlush(account);
            audit(actor.getAccountId(), id, enabled ? "DRIVER_ENABLED" : "DRIVER_DISABLED");
        }
        return AccountSummary.from(account);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TemporaryCredential reset(AccountPrincipal actor, String currentPassword, Long id) {
        administrator(actor, currentPassword);
        var account = driverForUpdate(id);
        String temporary = temporaryPassword();
        Instant expiry = clock.instant().plus(Duration.ofHours(24));
        account.setTemporaryPassword(passwords.encode(temporary), expiry);
        accounts.saveAndFlush(account);
        audit(actor.getAccountId(), id, "PASSWORD_RESET");
        return new TemporaryCredential(AccountSummary.from(account), temporary, expiry);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeOwnPassword(AccountPrincipal actor, String currentPassword, String newPassword) {
        var account = current(actor, true);
        verifyPassword(currentPassword, account);
        AccountService.validatePassword(newPassword);
        if (passwords.matches(newPassword, account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different password.");
        }
        account.replacePassword(passwords.encode(newPassword));
        accounts.saveAndFlush(account);
        audit(actor.getAccountId(), actor.getAccountId(), "PASSWORD_CHANGED");
    }

    private UserAccount current(AccountPrincipal actor, boolean lock) {
        var account = (lock ? accounts.findForUpdate(actor.getAccountId()) : accounts.findById(actor.getAccountId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
        if (lock) entities.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!account.isEnabled() || account.getAuthenticationRevision() != actor.getAuthenticationRevision()
                || !account.getUsername().equals(actor.getUsername())
                || !actor.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_" + account.getRole().name()))
                || (account.isPasswordChangeRequired() && (account.getTemporaryPasswordExpiresAt() == null || !account.getTemporaryPasswordExpiresAt().isAfter(clock.instant())))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return account;
    }
    private void administrator(AccountPrincipal actor, String password) {
        var account = current(actor, true);
        requireAdministrator(account);
        verifyPassword(password, account);
    }
    private static void requireAdministrator(UserAccount account) {
        if (account.getRole() != AccountRole.ADMIN || account.isPasswordChangeRequired()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required.");
        }
    }
    private UserAccount driverForUpdate(Long id) {
        // Reject administrator targets before taking a second account lock; roles are immutable in this API.
        if (!accounts.existsByIdAndRole(id, AccountRole.DRIVER)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver account not found.");
        var account = accounts.findForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver account not found."));
        entities.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (account.getRole() != AccountRole.DRIVER) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver account not found.");
        return account;
    }
    private void verifyPassword(String password, UserAccount account) {
        try { AccountService.validatePassword(password); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Credential verification failed."); }
        if (!passwords.matches(password, account.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Credential verification failed.");
        }
    }
    private String temporaryPassword() {
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void audit(Long actor, Long target, String action) {
        audits.saveAndFlush(new AccountAuditEvent(actor, target, action, clock.instant()));
    }
}
