package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.dto.warehouse.*;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="account.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class WarehouseManagementMySqlTest {
    static ConfigurableApplicationContext context;
    static String url;
    WarehouseRepository warehouses;
    OperatorRepository operators;
    WarehouseService service;
    Integer id;
    @BeforeAll static void application() throws Exception {
        try(var db=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var sql=db.createStatement()) { sql.execute("CREATE DATABASE warehouse_management_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","warehouse_management_test");
        context=new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run("--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password","--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect","--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() {
        warehouses=context.getBean(WarehouseRepository.class); operators=context.getBean(OperatorRepository.class); service=context.getBean(WarehouseService.class);
        operators.deleteAll(); operators.flush(); warehouses.deleteAll(); warehouses.flush(); id=service.create(request("Original")).id();
    }
    WarehouseWriteRequest request(String name) { var request=new WarehouseWriteRequest();request.setName(name);request.setAddress("Depot address");request.setLatitude(new BigDecimal("53.123456"));request.setLongitude(new BigDecimal("-2.5"));return request; }
    String etag() { return service.snapshot(id).orElseThrow().etag(); }
    @Test void scalarDirectoryLiteralSearchAndPersistedCoordinateVersionsWorkOnMySql() {
        var created=service.create(request("Literal_%!"));
        assertThat(service.snapshot(created.id()).orElseThrow().etag()).isEqualTo(created.etag());
        assertThat(created.latitude()).isEqualByComparingTo("53.123456");
        assertThat(service.directory(0,1,"").warehouses()).hasSize(1); assertThat(service.directory(1,1,"").warehouses()).hasSize(1);
        assertThat(service.directory(0,20,"_%!").warehouses()).extracting(WarehouseItem::warehouseId).containsExactly(created.id());
        assertThat(service.delete(created.id(),created.etag())).isTrue(); assertThat(warehouses.existsById(created.id())).isFalse();
    }
    @Test void concurrentEditsUsingOneFingerprintHaveOneWinner() throws Exception {
        String before=etag();var start=new CountDownLatch(1);
        var one=CompletableFuture.supplyAsync(()->edit(start,before,"First"));var two=CompletableFuture.supplyAsync(()->edit(start,before,"Second"));start.countDown();
        assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        assertThat(warehouses.findById(id).orElseThrow().getName()).isIn("First","Second");
    }
    int edit(CountDownLatch start,String before,String name) {
        try { if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test writer did not start.");service.replace(id,request(name),before);return 200; }
        catch(org.springframework.web.server.ResponseStatusException failure) { return failure.getStatusCode().value(); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
    }
    @Test void waitingEditRechecksTheCommittedRow() throws Exception {
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeUpdate("UPDATE warehouses SET name='Winning edit' WHERE warehouse_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return service.replace(id,request("Losing edit"),before);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertStatus(pending,412);
        }
        assertThat(service.snapshot(id).orElseThrow().name()).isEqualTo("Winning edit");
    }
    @Test void staleOsivEntityCannotOverwriteOrDeleteNewerData() throws Exception {
        var factory=context.getBean(EntityManagerFactory.class);var manager=factory.createEntityManager();
        TransactionSynchronizationManager.bindResource(factory,new EntityManagerHolder(manager));
        try {
            var before=service.snapshot(id).orElseThrow();
            assertThat(CompletableFuture.supplyAsync(()->service.replace(id,request("Winning edit"),before.etag())).get(5,TimeUnit.SECONDS)).isPresent();
            assertThatThrownBy(()->service.replace(id,request("Losing edit"),before.etag())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(412));
            assertThatThrownBy(()->service.delete(id,before.etag())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally { TransactionSynchronizationManager.unbindResource(factory);manager.close(); }
        assertThat(service.snapshot(id).orElseThrow().name()).isEqualTo("Winning edit");
    }
    @Test void waitingEditCannotResurrectDeletedWarehouse() throws Exception {
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeUpdate("DELETE FROM warehouses WHERE warehouse_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return service.replace(id,request("Late edit"),before);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(warehouses.existsById(id)).isFalse();
    }
    @Test void deletionWaitsForAnAssignmentAndThenRefusesToDeleteReferencedWarehouse() throws Exception {
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);
            sql.executeUpdate("INSERT INTO operators (name,phone_number,email,availability_status,warehouse_id) VALUES ('Racing driver','123','race@example.test','AVAILABLE',"+id+")");
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return service.delete(id,before);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertStatus(pending,409);
        }
        assertThat(warehouses.existsById(id)).isTrue();assertThat(operators.findAll()).hasSize(1);
        try(var db=connection();var sql=db.createStatement()) {
            assertThatThrownBy(()->sql.executeUpdate("DELETE FROM warehouses WHERE warehouse_id="+id)).isInstanceOf(SQLException.class);
        }
    }
    @Test void waitingDeleteRejectsAConcurrentWarehouseEdit() throws Exception {
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeUpdate("UPDATE warehouses SET address='Changed address' WHERE warehouse_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return service.delete(id,before);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertStatus(pending,412);
        }
        assertThat(service.snapshot(id).orElseThrow().address()).isEqualTo("Changed address");
    }
    void assertStatus(CompletableFuture<?> pending,int status) {
        assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error.getCause()).getStatusCode().value()).isEqualTo(status));
    }
    Connection connection() throws SQLException { return DriverManager.getConnection(url,"root","disposable-test-password"); }
}
