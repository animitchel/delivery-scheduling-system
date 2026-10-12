package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.repository.OperatorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({AccountService.class, AccountPasswordConfiguration.class})
class AccountServiceTest {
    private static final String PASSWORD = "a long private passphrase";
    @Autowired AccountService service;
    @Autowired AccountRepository accounts;
    @Autowired OperatorRepository operators;
    @Autowired PasswordEncoder encoder;

    @Test void persistsNormalizedAdministratorWithSaltedHash() {
        var summary = service.createAdministrator("  Main.Admin  ", PASSWORD);
        var account = accounts.findById(summary.id()).orElseThrow();
        assertThat(summary.username()).isEqualTo("main.admin");
        assertThat(summary.role()).isEqualTo(AccountRole.ADMIN);
        assertThat(summary.operatorId()).isNull();
        assertThat(summary.enabled()).isTrue();
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(encoder.matches("incorrect password", account.getPasswordHash())).isFalse();
        assertThat(encoder.encode(PASSWORD)).isNotEqualTo(account.getPasswordHash());
    }
    @Test void linksDriverWithoutCreatingOrChangingOperator() {
        var operator = new Operator(); operator.setName("Driver"); operator.setPhoneNumber("123"); operator.setEmail("driver@example.test");
        operators.saveAndFlush(operator);
        var summary = service.createDriver("driver.one", PASSWORD, operator.getOperatorId());
        assertThat(summary.operatorId()).isEqualTo(operator.getOperatorId());
        assertThat(summary.role()).isEqualTo(AccountRole.DRIVER);
        assertThat(operators.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.createDriver("driver.two", PASSWORD, operator.getOperatorId())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsDuplicateCanonicalUsernames() {
        service.createAdministrator("admin.one", PASSWORD);
        assertThatThrownBy(() -> service.createAdministrator(" ADMIN.ONE ", PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.count()).isEqualTo(1);
    }
    @Test void initialProvisioningIsIdempotentAndNeverResetsCredentials() {
        var first = service.provisionInitialAdministrator("initial.admin", PASSWORD).orElseThrow();
        String hash = accounts.findById(first.id()).orElseThrow().getPasswordHash();
        assertThat(service.provisionInitialAdministrator(null, null)).isEmpty();
        assertThat(accounts.count()).isEqualTo(1);
        assertThat(accounts.findById(first.id()).orElseThrow().getPasswordHash()).isEqualTo(hash);
    }
    @Test void accountSerializationAndStringExcludeCredentialsAndOperator() throws Exception {
        var summary = service.createAdministrator("private.admin", PASSWORD);
        var account = accounts.findById(summary.id()).orElseThrow();
        String json = new ObjectMapper().writeValueAsString(account);
        assertThat(json).doesNotContain("\"password\"", "passwordHash", "password_hash", "bcrypt", "bootstrap", "operator", PASSWORD);
        assertThat(account.toString()).doesNotContain(account.getPasswordHash(), PASSWORD, "private.admin");
        assertThat(new ObjectMapper().writeValueAsString(summary)).doesNotContain("\"password\"", "passwordHash", "password_hash", "bcrypt", PASSWORD);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"ab", "bad user", "éadmin", "-admin", "admin@example.test"})
    void rejectsInvalidUsernames(String username) { assertThatThrownBy(() -> service.createAdministrator(username, PASSWORD)).isInstanceOf(IllegalArgumentException.class); assertThat(accounts.count()).isZero(); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"short", "               "})
    void rejectsWeakPasswords(String password) { assertThatThrownBy(() -> service.createAdministrator("valid.admin", password)).isInstanceOf(IllegalArgumentException.class); assertThat(accounts.count()).isZero(); }
    @Test void countsUtf8BytesAndAcceptsBcryptBoundary() {
        assertThatThrownBy(() -> AccountService.validatePassword("é".repeat(37))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> AccountService.validatePassword("é".repeat(36))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccountService.validatePassword("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> AccountService.validatePassword("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccountService.validatePassword("😀".repeat(14))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMalformedUnicodeBeforeEncoding() {
        String prefix = "a".repeat(14);
        assertThatThrownBy(() -> service.createAdministrator("invalid.high", prefix + (char) 0xD800))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid Unicode");
        assertThatThrownBy(() -> service.createAdministrator("invalid.low", prefix + (char) 0xDC00))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid Unicode");
        assertThat(accounts.count()).isZero();
        assertThatCode(() -> AccountService.validatePassword("😀".repeat(15))).doesNotThrowAnyException();
    }
    @Test void rejectsMissingDriverOperator() { assertThatThrownBy(() -> service.createDriver("driver.one", PASSWORD, 99999)).isInstanceOf(IllegalArgumentException.class); assertThat(accounts.count()).isZero(); }
    @Test void rejectsPlaintextAndRoleLinkMismatchAtEntityBoundary() {
        String hash = encoder.encode(PASSWORD);
        assertThatThrownBy(() -> new UserAccount("valid.admin", PASSWORD, AccountRole.ADMIN, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccount("valid.driver", hash, AccountRole.DRIVER, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccount("valid.admin", hash, AccountRole.ADMIN, new Operator(), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAccount("valid.driver", hash, AccountRole.DRIVER, new Operator(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.matches(PASSWORD, "{noop}"+PASSWORD)).isInstanceOf(IllegalArgumentException.class);
    }
}
