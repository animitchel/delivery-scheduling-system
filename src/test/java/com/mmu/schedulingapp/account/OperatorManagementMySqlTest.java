package com.mmu.schedulingapp.account;
import com.mmu.schedulingapp.dto.operator.*;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="account.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class OperatorManagementMySqlTest {
    static ConfigurableApplicationContext context;
    static String url;
    OperatorService service;
    OperatorRepository operators;
    WarehouseRepository warehouses;
    AccountRepository accounts;
    MapsProvider maps;
    Integer id,warehouseId;
    @TestConfiguration(proxyBeanMethods=false) static class StubMaps { @Bean @Primary MapsProvider testMaps() { return mock(MapsProvider.class); } }
    @BeforeAll static void application() throws Exception {
        try(var db=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var sql=db.createStatement()) { sql.execute("CREATE DATABASE operator_management_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","operator_management_test");
        context=new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class,StubMaps.class).web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run("--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password","--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect","--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() throws Exception {
        service=context.getBean(OperatorService.class);operators=context.getBean(OperatorRepository.class);warehouses=context.getBean(WarehouseRepository.class);accounts=context.getBean(AccountRepository.class);maps=context.getBean("testMaps",MapsProvider.class);
        context.getBean(RouteRepository.class).deleteAll();context.getBean(DeliveryRepository.class).deleteAll();context.getBean(AccountAuditRepository.class).deleteAll();accounts.deleteAll();accounts.flush();operators.deleteAll();operators.flush();context.getBean(RecipientRepository.class).deleteAll();warehouses.deleteAll();warehouses.flush();
        var warehouse=new Warehouse();warehouse.setName("Depot");warehouse.setAddress("Depot address");warehouse.setLatitude(BigDecimal.ZERO);warehouse.setLongitude(BigDecimal.ZERO);warehouseId=warehouses.saveAndFlush(warehouse).getWarehouseId();
        id=service.create(request("Original",Operator.AvailabilityStatus.AVAILABLE)).id();reset(maps);
        when(maps.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call->{((PlanningGuard.Budget)call.getArgument(4)).countProviderCall();return new DistanceResult(1,"1 min",60);});
    }
    OperatorWriteRequest request(String name,Operator.AvailabilityStatus status) { var r=new OperatorWriteRequest();r.setName(name);r.setPhoneNumber("+447123456789");r.setEmail("original@example.test");r.setVehicleType("Van");r.setMaxCapacity(25.0);r.setWarehouseId(warehouseId);r.setAvailabilityStatus(status);return r; }
    String etag() { return service.snapshot(id).orElseThrow().etag(); }
    @Test void scalarDirectoryAndPersistedVersionsWork() {
        var before=service.snapshot(id).orElseThrow();assertThat(service.directory(0,1,"original").operators()).extracting(OperatorItem::operatorId).containsExactly(id);
        var updated=service.replace(id,request("Literal_%!",Operator.AvailabilityStatus.OFF_DUTY),before.etag()).orElseThrow();assertThat(service.snapshot(id).orElseThrow().etag()).isEqualTo(updated.etag());
        assertThat(service.directory(0,20,"_%!").operators()).hasSize(1);assertThat(service.delete(id,updated.etag())).isTrue();
    }
    @Test void concurrentEditsHaveOneWinner() throws Exception {
        String before=etag();var start=new CountDownLatch(1);
        var one=CompletableFuture.supplyAsync(()->edit(start,before,"First"));var two=CompletableFuture.supplyAsync(()->edit(start,before,"Second"));start.countDown();
        assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
    }
    int edit(CountDownLatch start,String before,String name) { try { if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Writer did not start.");service.replace(id,request(name,Operator.AvailabilityStatus.AVAILABLE),before);return 200; } catch(org.springframework.web.server.ResponseStatusException failure) { return failure.getStatusCode().value(); } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); } }
    @Test void staleOsivEntitiesCannotOverwriteCommittedAvailability() throws Exception {
        var factory=context.getBean(EntityManagerFactory.class);var manager=factory.createEntityManager();TransactionSynchronizationManager.bindResource(factory,new EntityManagerHolder(manager));
        try {
            String before=etag();CompletableFuture.supplyAsync(()->service.replace(id,request("Winner",Operator.AvailabilityStatus.UNAVAILABLE),before)).get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->service.replace(id,request("Loser",Operator.AvailabilityStatus.AVAILABLE),before)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally { TransactionSynchronizationManager.unbindResource(factory);manager.close(); }
        assertThat(service.snapshot(id).orElseThrow().status()).isEqualTo(Operator.AvailabilityStatus.UNAVAILABLE);
    }
    void pendingDelivery() {
        var recipients=context.getBean(RecipientRepository.class);var recipient=new Recipient();recipient.setFirstName("Test");recipient.setLastName("Recipient");recipient.setAddress("Address");recipient.setLatitude(BigDecimal.ONE);recipient.setLongitude(BigDecimal.ONE);recipients.saveAndFlush(recipient);
        var delivery=new Delivery();delivery.setRecipientId(recipient);delivery.setScheduledDate(LocalDate.now());delivery.setStartTimeWindow(LocalTime.MIN);delivery.setEndTimeWindow(LocalTime.MAX);delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);context.getBean(DeliveryRepository.class).saveAndFlush(delivery);
    }
    int generate() { var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO();request.setScheduledDate(LocalDate.now());try { context.getBean(RouteService.class).createRoutes(request);return 201; } catch(org.springframework.web.server.ResponseStatusException failure) { return failure.getStatusCode().value(); } }
    @Test void operatorEditDuringCalculationInvalidatesTheRoutePlan() throws Exception {
        pendingDelivery();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{((PlanningGuard.Budget)call.getArgument(4)).countProviderCall();entered.countDown();if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Missing provider release.");return new DistanceResult(1,"1 min",60);}).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        var planning=CompletableFuture.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();service.replace(id,request("Original",Operator.AvailabilityStatus.UNAVAILABLE),etag()); } finally { release.countDown(); }
        assertThat(planning.get(5,TimeUnit.SECONDS)).isEqualTo(409);assertThat(context.getBean(RouteRepository.class).count()).isZero();assertThat(service.snapshot(id).orElseThrow().status()).isEqualTo(Operator.AvailabilityStatus.UNAVAILABLE);
    }
    @Test void committedGenerationBlocksAvailabilityAndDeletion() {
        pendingDelivery();String before=etag();assertThat(generate()).isEqualTo(201);
        assertThatThrownBy(()->service.replace(id,request("Unsafe",Operator.AvailabilityStatus.AVAILABLE),before)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service.replace(id,request("Unsafe",Operator.AvailabilityStatus.AVAILABLE),etag())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(()->service.delete(id,etag())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(service.snapshot(id).orElseThrow().status()).isEqualTo(Operator.AvailabilityStatus.ON_DELIVERY);
    }
    @ParameterizedTest @ValueSource(strings={"foundation","management"})
    void accountCreationWinsBeforeDeletionWithoutDanglingReferences(String path) throws Exception {
        String password="disposable test admin passphrase";var summary=context.getBean(AccountService.class).createAdministrator("race.admin",password);
        var actor=new AccountPrincipal(accounts.findById(summary.id()).orElseThrow());
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeQuery("SELECT operator_id FROM operators WHERE operator_id="+id+" FOR UPDATE").close();
            int blocker=connectionId(sql);
            var account=CompletableFuture.supplyAsync(()->{started.countDown();return createAccount(path,actor,password);});assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();awaitWaiters(blocker,1);
            assertThatThrownBy(()->account.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            var deletion=CompletableFuture.supplyAsync(()->service.delete(id,before));
            try { assertThatThrownBy(()->deletion.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertThat(account.get(5,TimeUnit.SECONDS)).isNotNull();assertThatThrownBy(()->deletion.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(accounts.existsByOperatorOperatorId(id)).isTrue();assertThat(operators.existsById(id)).isTrue();
    }

    Object createAccount(String path,AccountPrincipal actor,String password) {
        return path.equals("foundation")?context.getBean(AccountService.class).createDriver("race.driver","disposable test driver passphrase",id):context.getBean(AccountManagementService.class).createDriver(actor,password,"race.driver",id);
    }
    @ParameterizedTest @ValueSource(strings={"foundation","management"})
    void deletionWinsBeforeAccountCreationWithoutResurrectingOperator(String path) throws Exception {
        String password="disposable test admin passphrase";var summary=context.getBean(AccountService.class).createAdministrator("race.admin",password);var actor=new AccountPrincipal(accounts.findById(summary.id()).orElseThrow());String before=etag();
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeQuery("SELECT operator_id FROM operators WHERE operator_id="+id+" FOR UPDATE").close();
            int blocker=connectionId(sql);
            var deletion=CompletableFuture.supplyAsync(()->service.delete(id,before));awaitWaiters(blocker,1);assertThatThrownBy(()->deletion.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            var account=CompletableFuture.supplyAsync(()->createAccount(path,actor,password));
            awaitWaiters(blocker,2);
            try { assertThatThrownBy(()->account.get(400,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertThat(deletion.get(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->account.get(5,TimeUnit.SECONDS)).satisfies(error->assertThat(error.getCause()).isInstanceOfAny(IllegalArgumentException.class,org.springframework.web.server.ResponseStatusException.class));
        }
        assertThat(operators.existsById(id)).isFalse();assertThat(accounts.existsByUsername("race.driver")).isFalse();
    }
    @Test void deletedOperatorCannotBeResurrectedByAWaitingEdit() throws Exception {
        String before=etag();var started=new CountDownLatch(1);
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);sql.executeUpdate("DELETE FROM operators WHERE operator_id="+id);
            var pending=CompletableFuture.supplyAsync(()->{started.countDown();return service.replace(id,request("Late",Operator.AvailabilityStatus.AVAILABLE),before);});
            try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->pending.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); } finally { db.commit(); }
            assertThat(pending.get(5,TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(operators.existsById(id)).isFalse();
    }
    int connectionId(Statement sql) throws SQLException { try(var result=sql.executeQuery("SELECT CONNECTION_ID()")) { result.next();return result.getInt(1); } }
    void awaitWaiters(int blocker,int expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try(var db=connection();var sql=db.createStatement()) {
            do {
                try(var result=sql.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits WHERE BLOCKING_ENGINE_TRANSACTION_ID IN (SELECT trx_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id="+blocker+")")) { result.next();if(result.getInt(1)>=expected) return; }
                Thread.sleep(10);
            } while(System.nanoTime()<deadline);
        }
        throw new AssertionError("Expected writers did not enter the database lock queue.");
    }
    Connection connection() throws SQLException { return DriverManager.getConnection(url,"root","disposable-test-password"); }
}
