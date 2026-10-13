package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.dto.delivery.*;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.MapsProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.sql.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class DeliveryPreparationMySqlTest {
    static ConfigurableApplicationContext context;
    static String url;
    RecipientRepository recipients;
    DeliveryRepository deliveries;
    DeliveryPreparationService service;
    Integer id;
    @TestConfiguration(proxyBeanMethods=false)
    static class StubMaps {
        @Bean @Primary MapsProvider stubMapsProvider() { return mock(MapsProvider.class); }
    }
    @BeforeAll static void application() throws Exception {
        try (var db=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password"); var sql=db.createStatement()) { sql.execute("CREATE DATABASE delivery_preparation_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","delivery_preparation_test");
        context=new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class,StubMaps.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run("--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password","--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect","--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    @AfterAll static void close() { if (context!=null) context.close(); }
    @BeforeEach void setup() {
        recipients=context.getBean(RecipientRepository.class); deliveries=context.getBean(DeliveryRepository.class); service=context.getBean(DeliveryPreparationService.class);
        deliveries.deleteAll(); deliveries.flush(); recipients.deleteAll(); recipients.flush();
        var recipient=new Recipient(); recipient.setFirstName("SQL"); recipient.setLastName("Recipient"); recipient.setAddress("Stored address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); id=recipients.saveAndFlush(recipient).getRecipientId();
    }
    DeliveryCreateRequest request(LocalDate date, String instructions) { return new DeliveryCreateRequest(id,date,LocalTime.of(9,0),LocalTime.of(12,0),Delivery.PriorityLevel.MEDIUM,instructions); }
    @Test void persistsWholeSecondWindowsAndColumnBoundAndReadsFilteredStablePages() throws Exception {
        var first=service.create(request(LocalDate.of(2020,1,2),"x".repeat(255))); var second=service.create(request(LocalDate.of(2020,1,2),"  ")); service.create(request(LocalDate.of(2020,1,3),null));
        assertThat(service.read(first.getDeliveryId()).orElseThrow().getStartTimeWindow()).isEqualTo(LocalTime.of(9,0));
        assertThat(service.read(first.getDeliveryId()).orElseThrow().getSpecialInstructions()).hasSize(255);
        assertThat(service.read(second.getDeliveryId()).orElseThrow().getSpecialInstructions()).isNull();
        var page=service.directory(1,1,LocalDate.of(2020,1,2),Delivery.DeliveryStatus.PENDING,id);
        assertThat(page.totalElements()).isEqualTo(2); assertThat(page.deliveries()).extracting(DeliveryDirectory.Item::deliveryId).containsExactly(second.getDeliveryId());
        assertThat(page.deliveries().getFirst().operatorId()).isNull();
        try (var db=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=db.createStatement(); var result=sql.executeQuery("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema='delivery_preparation_test' AND table_name='deliveries' AND column_name='special_instructions'")) {
            assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isEqualTo(255);
        }
        for (var boundary : java.util.List.of(LocalDate.of(1000,1,1),LocalDate.of(9999,12,31))) {
            var created=service.create(request(boundary,null));
            assertThat(service.read(created.getDeliveryId()).orElseThrow().getScheduledDate()).isEqualTo(boundary);
        }
        verifyNoInteractions(context.getBean("stubMapsProvider",MapsProvider.class));
    }
    @Test void waitingCreationRejectsRecipientDeletedBeforeItObtainsTheLock() throws Exception {
        var entered=new CountDownLatch(1);
        try (var db=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=db.createStatement()) {
            db.setAutoCommit(false); sql.executeUpdate("DELETE FROM recipients WHERE recipient_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{entered.countDown(); return service.create(request(LocalDate.of(2020,1,2),null));});
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { db.commit(); }
            assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException)error.getCause()).getStatusCode().value()).isEqualTo(404));
        }
        assertThat(deliveries.count()).isZero(); assertThat(recipients.existsById(id)).isFalse();
    }
    @Test void waitingCreationSucceedsIfTheRecipientDeletionRollsBack() throws Exception {
        var entered=new CountDownLatch(1);
        try (var db=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=db.createStatement()) {
            db.setAutoCommit(false); sql.executeUpdate("DELETE FROM recipients WHERE recipient_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{entered.countDown(); return service.create(request(LocalDate.of(2020,1,2),null));});
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { db.rollback(); }
            assertThat(pending.get(5,TimeUnit.SECONDS).getRecipientId()).isEqualTo(id);
        }
        assertThat(deliveries.count()).isEqualTo(1);
    }
    @Test void savedDeliveryRetainsForeignKeyAndInvalidInputDoesNotWrite() {
        service.create(request(LocalDate.of(2020,1,2),null));
        assertThatThrownBy(()->recipients.deleteById(id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(recipients.existsById(id)).isTrue(); assertThat(deliveries.count()).isEqualTo(1);
        assertThatThrownBy(()->service.create(request(LocalDate.of(2020,1,2),"x".repeat(256)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(deliveries.count()).isEqualTo(1);
    }
}
