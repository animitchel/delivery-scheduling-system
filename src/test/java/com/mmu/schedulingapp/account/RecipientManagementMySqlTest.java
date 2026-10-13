package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.RecipientRepository;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="account.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class RecipientManagementMySqlTest {
    static ConfigurableApplicationContext context;
    static String url;
    RecipientRepository recipients;
    RecipientService service;
    RecipientTransactions transactions;
    MapsProvider provider;
    Integer id;
    @TestConfiguration(proxyBeanMethods=false)
    static class StubMaps {
        @Bean @Primary MapsProvider stubMapsProvider() { return mock(MapsProvider.class); }
    }
    @BeforeAll static void application() throws Exception {
        try(var connection=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var sql=connection.createStatement()) { sql.execute("CREATE DATABASE recipient_management_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","recipient_management_test");
        context=new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class,StubMaps.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run("--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password","--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect","--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() {
        recipients=context.getBean(RecipientRepository.class); service=context.getBean(RecipientService.class); transactions=context.getBean(RecipientTransactions.class); provider=context.getBean("stubMapsProvider",MapsProvider.class);
        recipients.deleteAll(); recipients.flush(); reset(provider);
        when(provider.coordinates(anyString())).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Optional.of(new GeocodingService.Coordinate(53.12345678,-2.5));});
        var recipient=new Recipient(); recipient.setFirstName("Original"); recipient.setLastName("Recipient"); recipient.setAddress("Original address"); recipient.setLatitude(BigDecimal.TEN); recipient.setLongitude(new BigDecimal("20")); recipient.setPriorityLevel(Recipient.PriorityLevel.MEDIUM); recipient.setCreatedAt(LocalDateTime.of(2026,10,12,12,0)); id=recipients.saveAndFlush(recipient).getRecipientId();
    }
    RecipientWriteRequest request(String name,String address) { var result=new RecipientWriteRequest(); result.setFirstName(name); result.setLastName("Recipient"); result.setAddress(address); result.setPriorityLevel(RecipientRequestDTO.PriorityLevel.HIGH); return result; }
    @Test void providerIsOutsideTransactionsAndDirectoryAndVersionsWorkOnMySql() {
        var created=service.createRecipient(request("Literal_%!","New address")); assertThat(created.latitude()).isEqualByComparingTo("53.123457");
        assertThat(service.versionedRecipient(created.id()).orElseThrow().etag()).isEqualTo(created.etag());
        assertThat(service.directory(0,1,"").recipients()).hasSize(1); assertThat(service.directory(1,1,"").recipients()).hasSize(1);
        assertThat(service.directory(0,20,"_%!").recipients()).extracting(RecipientDirectory.Item::recipientId).containsExactly(created.id());
        var before=service.versionedRecipient(id).orElseThrow(); var updated=service.replaceRecipient(id,request("Edit","Original address"),before.etag()).orElseThrow();
        assertThat(updated.latitude()).isEqualByComparingTo("10"); assertThat(updated.createdAt()).isEqualTo(before.createdAt());
        assertThatThrownBy(()->service.replaceRecipient(id,request("Stale","Different address"),before.etag())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(412));
        verify(provider,times(1)).coordinates(anyString());
    }
    @Test void concurrentCommitsUsingTheSameSnapshotHaveOneWinner() throws Exception {
        var before=transactions.snapshot(id).orElseThrow(); var start=new CountDownLatch(1);
        var one=CompletableFuture.supplyAsync(()->replace(start,before,"First")); var two=CompletableFuture.supplyAsync(()->replace(start,before,"Second"));
        start.countDown(); assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        assertThat(recipients.findById(id).orElseThrow().getFirstName()).isIn("First","Second");
    }
    int replace(CountDownLatch start,RecipientState before,String name) {
        try { assertThat(start.await(5,TimeUnit.SECONDS)).isTrue(); transactions.replace(before,RecipientInput.from(request(name,"Original address")),before.latitude(),before.longitude()); return 200; }
        catch(org.springframework.web.server.ResponseStatusException failure) { return failure.getStatusCode().value(); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
    }
    @Test void waitingWriterRechecksCommittedRowRatherThanOverwritingAConcurrentEdit() throws Exception {
        var before=transactions.snapshot(id).orElseThrow(); var started=new CountDownLatch(1);
        try(var db=DriverManager.getConnection(url,"root","disposable-test-password");var sql=db.createStatement()) {
            db.setAutoCommit(false); sql.executeUpdate("UPDATE recipients SET first_name='Winning edit' WHERE recipient_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return transactions.replace(before,RecipientInput.from(request("Losing edit","Changed address")),BigDecimal.ONE,BigDecimal.ONE);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { db.commit(); }
            assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error.getCause()).getStatusCode().value()).isEqualTo(409));
        }
        var winner=recipients.findById(id).orElseThrow(); assertThat(winner.getFirstName()).isEqualTo("Winning edit"); assertThat(winner.getAddress()).isEqualTo("Original address"); assertThat(winner.getLatitude()).isEqualByComparingTo("10");
    }
    @Test void waitingWriterCannotResurrectADeletedRow() throws Exception {
        var before=transactions.snapshot(id).orElseThrow();var started=new CountDownLatch(1);
        try(var db=DriverManager.getConnection(url,"root","disposable-test-password");var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeUpdate("DELETE FROM recipients WHERE recipient_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return transactions.replace(before,RecipientInput.from(request("Late edit","Changed address")),BigDecimal.ONE,BigDecimal.ONE);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { db.commit(); }
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(recipients.existsById(id)).isFalse();
    }
}
