package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mmu.schedulingapp.model.Operator;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "user_accounts")
@Check(name = "ck_account_role_link", constraints = "(role = 'ADMIN' AND operator_id IS NULL) OR (role = 'DRIVER' AND operator_id IS NOT NULL)")
@Check(name = "ck_account_bootstrap", constraints = "bootstrap_slot IS NULL OR (bootstrap_slot = 1 AND role = 'ADMIN')")
@Check(name = "ck_account_revision", constraints = "auth_revision >= 0")
@Check(name = "ck_account_password_setup", constraints = "(password_change_required = TRUE AND temporary_password_expires_at IS NOT NULL) OR (password_change_required = FALSE AND temporary_password_expires_at IS NULL)")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountRole role;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", unique = true, foreignKey = @ForeignKey(name = "fk_account_operator"))
    private Operator operator;

    @Column(nullable = false)
    private boolean enabled = true;

    @JsonIgnore
    @Column(name = "bootstrap_slot", unique = true)
    private Integer bootstrapSlot;

    @JsonIgnore
    @Column(name = "auth_revision", nullable = false)
    private long authenticationRevision;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @JsonIgnore
    @Column(name = "temporary_password_expires_at", columnDefinition = "datetime(6)")
    private java.time.Instant temporaryPasswordExpiresAt;

    protected UserAccount() {
    }

    UserAccount(String username, String passwordHash, AccountRole role, Operator operator, boolean initialAdmin) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.operator = operator;
        this.bootstrapSlot = initialAdmin ? 1 : null;
        validate();
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        if (username == null || !username.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("Account username is invalid.");
        }
        if (passwordHash == null || !passwordHash.matches("\\{bcrypt\\}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Account requires an encoded password.");
        }
        if (role == null || (role == AccountRole.DRIVER) != (operator != null)) {
            throw new IllegalArgumentException("Account role and operator link do not match.");
        }
        if (authenticationRevision < 0 || passwordChangeRequired != (temporaryPasswordExpiresAt != null)) {
            throw new IllegalArgumentException("Account credential state is invalid.");
        }
        if (bootstrapSlot != null && (bootstrapSlot != 1 || role != AccountRole.ADMIN)) {
            throw new IllegalArgumentException("Initial administrator marker is invalid.");
        }
    }

    @JsonIgnore
    public long getAuthenticationRevision() { return authenticationRevision; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    @JsonIgnore
    public java.time.Instant getTemporaryPasswordExpiresAt() { return temporaryPasswordExpiresAt; }

    void changeEnabled(boolean value) {
        if (enabled != value) { authenticationRevision = Math.incrementExact(authenticationRevision); enabled = value; }
    }
    void setTemporaryPassword(String hash, java.time.Instant expiry) {
        authenticationRevision = Math.incrementExact(authenticationRevision);
        passwordHash = hash; passwordChangeRequired = true; temporaryPasswordExpiresAt = expiry;
    }
    void replacePassword(String hash) {
        authenticationRevision = Math.incrementExact(authenticationRevision);
        passwordHash = hash; passwordChangeRequired = false; temporaryPasswordExpiresAt = null;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public AccountRole getRole() { return role; }
    @JsonIgnore
    public Operator getOperator() { return operator; }
    public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return "UserAccount{id=" + id + ", role=" + role + ", enabled=" + enabled + "}";
    }
}
