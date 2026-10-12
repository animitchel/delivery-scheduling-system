package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.repository.OperatorRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.DriverManager;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class AccountManagementMySqlTest {
    static ConfigurableApplicationContext context;
    static final String PASSWORD="private management test passphrase";
    static final String HASH="{bcrypt}"+new BCryptPasswordEncoder(12).encode(PASSWORD);
    AccountRepository accounts;
    AccountAuditRepository audits;
    AccountManagementService service;
    AccountPrincipal one, two, driver;
    Integer spareOperator;

    @BeforeAll static void application() throws Exception {
        try(var connection=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE account_management_test");
        }
        context=MySqlAccountSchemaTest.application(System.getProperty("account.mysql.url").replace("account_foundation_test","account_management_test"),"create");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() {
        accounts=context.getBean(AccountRepository.class); audits=context.getBean(AccountAuditRepository.class); service=context.getBean(AccountManagementService.class);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(transaction -> {
            audits.deleteAll(); audits.flush(); accounts.deleteAll(); accounts.flush();
            var operators=context.getBean(OperatorRepository.class); operators.deleteAll(); operators.flush();
            one=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("first.admin",HASH,AccountRole.ADMIN,null,false)));
            two=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("second.admin",HASH,AccountRole.ADMIN,null,false)));
            var operator=new Operator(); operator.setName("Driver"); operator.setEmail("driver@management.test"); operator.setPhoneNumber("123"); operators.saveAndFlush(operator);
            driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("managed.driver",HASH,AccountRole.DRIVER,operator,false)));
            var spare=new Operator(); spare.setName("Spare"); spare.setEmail("spare@management.test"); spare.setPhoneNumber("123"); operators.saveAndFlush(spare); spareOperator=spare.getOperatorId();
        });
    }
    @Test void concurrentResetsSerializeAndOnlyFinalCredentialWorks() throws Exception {
        var start=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(() -> { await(start); return service.reset(one,PASSWORD,driver.getAccountId()); });
        var second=CompletableFuture.supplyAsync(() -> { await(start); return service.reset(two,PASSWORD,driver.getAccountId()); });
        start.countDown(); var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
        var account=accounts.findById(driver.getAccountId()).orElseThrow();
        assertThat(account.getAuthenticationRevision()).isEqualTo(2);
        var encoder=context.getBean(PasswordEncoder.class);
        assertThat(encoder.matches(a.temporaryPassword(),account.getPasswordHash()) ^ encoder.matches(b.temporaryPassword(),account.getPasswordHash())).isTrue();
        assertThat(audits.count()).isEqualTo(2);
    }
    @Test void resetVersusOwnChangeCannotRestoreOldCredentialOrLoseRevision() throws Exception {
        var start=new CountDownLatch(1);
        var reset=CompletableFuture.supplyAsync(() -> { await(start); return service.reset(one,PASSWORD,driver.getAccountId()); });
        var change=CompletableFuture.supplyAsync(() -> {
            await(start); try { service.changeOwnPassword(driver,PASSWORD,"new permanent driver passphrase"); return true; }
            catch(ResponseStatusException failure) { assertThat(failure.getStatusCode().value()).isEqualTo(401); return false; }
        });
        start.countDown(); var credential=reset.get(15,TimeUnit.SECONDS); boolean changed=change.get(15,TimeUnit.SECONDS);
        var account=accounts.findById(driver.getAccountId()).orElseThrow();
        assertThat(account.getAuthenticationRevision()).isEqualTo(changed?2:1);
        assertThat(account.isPasswordChangeRequired()).isTrue();
        assertThat(context.getBean(PasswordEncoder.class).matches(credential.temporaryPassword(),account.getPasswordHash())).isTrue();
        assertThat(audits.count()).isEqualTo(changed?2:1);
    }
    @Test void duplicateCreateAcrossTwoAdministratorsHasOneWinner() throws Exception {
        var start=new CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(() -> createAfter(start,one));
        var second=CompletableFuture.supplyAsync(() -> createAfter(start,two));
        start.countDown();
        assertThat(java.util.List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        assertThat(accounts.count()).isEqualTo(4); assertThat(audits.count()).isEqualTo(1);
    }
    @Test void temporaryExpiryAndAuditTimestampsRoundTripInUtc() {
        var before=java.time.Instant.now(); var credential=service.reset(one,PASSWORD,driver.getAccountId());
        var expiry=accounts.findById(driver.getAccountId()).orElseThrow().getTemporaryPasswordExpiresAt();
        assertThat(expiry).isAfter(before.plusSeconds(86395)).isBefore(java.time.Instant.now().plusSeconds(86405));
        assertThat(audits.findAll().getFirst().getOccurredAt()).isAfter(before.minusSeconds(1)).isBefore(java.time.Instant.now().plusSeconds(1));
        assertThat(credential.toString()).doesNotContain(credential.temporaryPassword());
    }
    int createAfter(CountDownLatch start,AccountPrincipal actor) {
        await(start);
        try { service.createDriver(actor,PASSWORD,"concurrent.driver",spareOperator); return 201; }
        catch(ResponseStatusException failure) { return failure.getStatusCode().value(); }
        catch(DataIntegrityViolationException failure) { return 409; }
    }
    static void await(CountDownLatch start) {
        try { start.await(); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
    }
}
