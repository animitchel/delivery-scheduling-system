package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.driver.DriverAssignmentService;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class DriverMySqlIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final TestWorkers workers = new TestWorkers();
    static ConfigurableApplicationContext context;
    static String url;
    DriverAssignmentService service;
    DeliveryRepository deliveries;
    Long accountId;
    Integer deliveryId, routeId, secondOperator;

    @BeforeAll static void application() throws Exception {
        try(var connection=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password"); var sql=connection.createStatement()) { sql.execute("CREATE DATABASE driver_ownership_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","driver_ownership_test");
        context=MySqlAccountSchemaTest.application(url,"create");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() {
        service=context.getBean(DriverAssignmentService.class); deliveries=context.getBean(DeliveryRepository.class);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(transaction -> {
            var routes=context.getBean(RouteRepository.class); var accounts=context.getBean(AccountRepository.class);
            var operators=context.getBean(OperatorRepository.class); var recipients=context.getBean(RecipientRepository.class);
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush();
            var first=new Operator(); first.setName("First"); first.setEmail("first@mysql.test"); first.setPhoneNumber("123"); first.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY); operators.saveAndFlush(first);
            var second=new Operator(); second.setName("Second"); second.setEmail("second@mysql.test"); second.setPhoneNumber("123"); operators.saveAndFlush(second); secondOperator=second.getOperatorId();
            accountId=accounts.saveAndFlush(new UserAccount("mysql.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,first,false)).getId();
            var recipient=new Recipient(); recipient.setFirstName("First"); recipient.setLastName("Recipient"); recipient.setAddress("Address"); recipient.setLatitude(java.math.BigDecimal.ZERO); recipient.setLongitude(java.math.BigDecimal.ZERO); recipients.saveAndFlush(recipient);
            var delivery=new Delivery(); delivery.setRecipientId(recipient); delivery.setOperatorId(first); delivery.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS); delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(delivery); deliveryId=delivery.getDeliveryId();
            var route=new Route(); route.setOperator(first); route.setScheduledDate(java.time.LocalDate.now()); route.setRouteStatus(Route.RouteStatus.IN_PROGRESS); route.setDeliveries(new java.util.ArrayList<>(java.util.List.of(delivery))); routes.saveAndFlush(route); routeId=route.getRouteId();
        });
    }
    @Test void boundedAdministratorDirectoryCountsWorkOnMySql() {
        var admin=context.getBean(com.mmu.schedulingapp.service.RouteService.class);
        var first=admin.directory(0,20); assertThat(first.routes()).hasSize(1);
        assertThat(first.routes().getFirst().stopCount()).isEqualTo(1); assertThat(first.routes().getFirst().finishedCount()).isZero();
        service.status(accountId,0L,deliveryId,"COMPLETED");
        var finished=admin.directory(0,20).routes().getFirst(); assertThat(finished.finishedCount()).isEqualTo(1); assertThat(finished.routeStatus()).isEqualTo(Route.RouteStatus.COMPLETED);
        assertThat(admin.directory(1,20).routes()).isEmpty();
    }
    @Test void nativeOwnershipQueriesAndAtomicStatusWorkOnMySql() {
        assertThat(service.routes(accountId,0L)).hasSize(1);
        assertThat(service.recipient(accountId,0L,deliveryId).address()).isEqualTo("Address");
        assertThat(service.status(accountId,0L,deliveryId,"COMPLETED").getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.COMPLETED);
        assertThatThrownBy(() -> service.status(accountId,0L,deliveryId,"CANCELLED")).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(((ResponseStatusException)failure).getStatusCode().value()).isEqualTo(409));
    }
    @Test void ambiguousLegacyMembershipIsHiddenAndCannotBeUpdated() throws Exception {
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password")) {
            var indexes=new java.util.ArrayList<String>();
            try(var sql=connection.createStatement(); var rows=sql.executeQuery("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='route_deliveries' AND column_name='delivery_id' AND non_unique=0 AND index_name<>'PRIMARY'")) {
                while(rows.next()) indexes.add(rows.getString(1));
            }
            try(var sql=connection.createStatement()) {
                if(!indexes.isEmpty()) {
                    sql.execute("CREATE INDEX driver_test_membership_idx ON route_deliveries(delivery_id)");
                    for(String index:indexes) {
                        assertThat(index).matches("[A-Za-z0-9_]+");
                        sql.execute("ALTER TABLE route_deliveries DROP INDEX `"+index+"`");
                    }
                }
                // Model legacy data without a unique inverse-membership index, in this disposable DB only.
                sql.execute("INSERT INTO routes(operator_id,scheduled_date,route_status) VALUES ("+secondOperator+",CURRENT_DATE,'IN_PROGRESS')");
                try(var rows=sql.executeQuery("SELECT MAX(route_id) FROM routes")) {
                    rows.next(); int otherRoute=rows.getInt(1);
                    sql.execute("INSERT INTO route_deliveries(route_id,delivery_id,delivery_order) VALUES ("+otherRoute+","+deliveryId+",0)");
                }
            }
        }
        assertThat(service.routes(accountId,0L)).isEmpty();
        assertThatThrownBy(() -> service.recipient(accountId,0L,deliveryId)).isInstanceOf(ResponseStatusException.class);
        assertThat(update("COMPLETED")).isEqualTo(404);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
    }
    @Test void concurrentTerminalUpdatesHaveOnlyOneWinner() throws Exception {
        var start=new CountDownLatch(1);
        var one=workers.supplyAsync(() -> { await(start); return update("COMPLETED"); });
        var two=workers.supplyAsync(() -> { await(start); return update("CANCELLED"); });
        start.countDown();
        assertThat(java.util.List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
    }
    @Test void reassignmentCannotBeOverwrittenByPendingDriverUpdate() throws Exception {
        raceWithAdministrator("UPDATE deliveries SET operator_id="+secondOperator+" WHERE delivery_id="+deliveryId);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getOperatorId().getOperatorId()).isEqualTo(secondOperator);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
    }
    @Test void deletedRouteCannotBeResurrectedByPendingDriverUpdate() throws Exception {
        raceWithAdministrator("UPDATE deliveries SET operator_id=NULL,delivery_status='PENDING' WHERE delivery_id="+deliveryId,
                "DELETE FROM route_deliveries WHERE route_id="+routeId, "DELETE FROM routes WHERE route_id="+routeId);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(service.routes(accountId,0L)).isEmpty();
    }
    @Test void deletedDeliveryCannotBeResurrectedByPendingDriverUpdate() throws Exception {
        raceWithAdministrator("UPDATE deliveries SET operator_id=NULL WHERE delivery_id="+deliveryId,
                "DELETE FROM route_deliveries WHERE delivery_id="+deliveryId,"DELETE FROM deliveries WHERE delivery_id="+deliveryId);
        assertThat(deliveries.findById(deliveryId)).isEmpty();
    }
    void raceWithAdministrator(String... statements) throws Exception {
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password")) {
            connection.setAutoCommit(false);
            for(String statement:statements) try(var sql=connection.createStatement()) { sql.executeUpdate(statement); }
            var started=new CountDownLatch(1);
            var result=workers.supplyAsync(() -> { started.countDown(); return update("COMPLETED"); });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            connection.commit();
            assertThat(result.get(10,TimeUnit.SECONDS)).isEqualTo(404);
        }
    }
    @Test void differentFinalStopsSerializeAndReleaseOperatorOnMySql() throws Exception {
        final Integer[] next={null};
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(t -> {
            var route=context.getBean(RouteRepository.class).findById(routeId).orElseThrow();
            var original=route.getDeliveries().get(0);
            var second=new Delivery(); second.setRecipientId(original.getRecipientId()); second.setOperatorId(route.getOperator());
            second.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS); second.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);
            deliveries.saveAndFlush(second); next[0]=second.getDeliveryId(); route.getDeliveries().add(second);
            context.getBean(RouteRepository.class).saveAndFlush(route);
        });
        var start=new CountDownLatch(1);
        var one=workers.runAsync(() -> { await(start); service.status(accountId,0L,deliveryId,"COMPLETED"); });
        var two=workers.runAsync(() -> { await(start); context.getBean(com.mmu.schedulingapp.service.DeliveryService.class)
                .updateDeliveryStatus(next[0],Delivery.DeliveryStatus.CANCELLED); });
        start.countDown(); one.get(10,TimeUnit.SECONDS); two.get(10,TimeUnit.SECONDS);
        var route=context.getBean(RouteRepository.class).findById(routeId).orElseThrow();
        assertThat(route.getRouteStatus()).isEqualTo(Route.RouteStatus.COMPLETED);
        assertThat(context.getBean(OperatorRepository.class).findById(route.getOperator().getOperatorId()).orElseThrow().getAvailabilityStatus())
                .isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
    }
    @Test void administratorAndDriverCompetingTerminalWritesHaveOneWinnerOnMySql() throws Exception {
        var start=new CountDownLatch(1);
        var one=workers.supplyAsync(() -> { await(start); return update("COMPLETED"); });
        var two=workers.supplyAsync(() -> {
            await(start);
            try { context.getBean(com.mmu.schedulingapp.service.DeliveryService.class).updateDeliveryStatus(deliveryId,Delivery.DeliveryStatus.CANCELLED); return 200; }
            catch(ResponseStatusException e) { return e.getStatusCode().value(); }
        });
        start.countDown();
        assertThat(java.util.List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        assertThat(context.getBean(RouteRepository.class).findById(routeId).orElseThrow().getRouteStatus()).isEqualTo(Route.RouteStatus.COMPLETED);
    }

    @Test void routeAndDeliveryChangesRollBackIfOperatorReleaseFailsOnMySql() throws Exception {
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_test_release BEFORE UPDATE ON operators FOR EACH ROW BEGIN IF NEW.availability_status='AVAILABLE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Test release failure'; END IF; END");
            try {
                assertThatThrownBy(() -> service.status(accountId,0L,deliveryId,"COMPLETED")).isInstanceOf(RuntimeException.class);
                assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
                assertThat(context.getBean(RouteRepository.class).findById(routeId).orElseThrow().getRouteStatus()).isEqualTo(Route.RouteStatus.IN_PROGRESS);
            } finally { sql.execute("DROP TRIGGER reject_test_release"); }
        }
    }
    @Test void simultaneousRouteGenerationCannotClaimSameOperatorOrDeliveryOnMySql() throws Exception {
        final Integer[] operator={null};
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(t -> {
            var routes=context.getBean(RouteRepository.class); routes.deleteAll(); routes.flush();
            var op=deliveries.findById(deliveryId).orElseThrow().getOperatorId(); operator[0]=op.getOperatorId();
            op.setAvailabilityStatus(Operator.AvailabilityStatus.AVAILABLE);
            context.getBean(OperatorRepository.class).findById(secondOperator).orElseThrow().setAvailabilityStatus(Operator.AvailabilityStatus.OFF_DUTY);
            var warehouse=new Warehouse(); warehouse.setName("Concurrency depot"); warehouse.setAddress("Test address");
            warehouse.setLatitude(java.math.BigDecimal.ZERO); warehouse.setLongitude(java.math.BigDecimal.ZERO);
            context.getBean(WarehouseRepository.class).saveAndFlush(warehouse); op.setWarehouse(warehouse);
            var d=deliveries.findById(deliveryId).orElseThrow(); d.setDeliveryStatus(Delivery.DeliveryStatus.PENDING);
            d.setScheduledDate(java.time.LocalDate.now()); d.setStartTimeWindow(java.time.LocalTime.MIN); d.setEndTimeWindow(java.time.LocalTime.MAX);
        });
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password")) {
            connection.setAutoCommit(false);
            try(var sql=connection.createStatement()) { sql.executeQuery("SELECT operator_id FROM operators WHERE operator_id="+operator[0]+" FOR UPDATE").close(); }
            var started=new CountDownLatch(2);
            var one=workers.supplyAsync(() -> generate(started));
            var two=workers.supplyAsync(() -> generate(started));
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> one.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> two.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            connection.commit();
            assertThat(java.util.List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(context.getBean(RouteRepository.class).count()).isEqualTo(1);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
    }
    int generate(CountDownLatch started) {
        try(var maps=org.mockito.Mockito.mockStatic(com.mmu.schedulingapp.utility.DistanceCalculator.class)) {
            maps.when(() -> com.mmu.schedulingapp.utility.DistanceCalculator.getGoogleMapsDistance(
                    org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.mmu.schedulingapp.utility.DistanceResult(0,"0 seconds",0));
            var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(java.time.LocalDate.now());
            started.countDown();
            context.getBean(com.mmu.schedulingapp.service.RouteService.class).createRoutes(request); return 201;
        } catch(ResponseStatusException e) { return e.getStatusCode().value(); }
    }

    Integer preparePlanning(int count) {
        final Integer[] operator={null};
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(t -> {
            var routes=context.getBean(RouteRepository.class); routes.deleteAll(); routes.flush();
            var d=deliveries.findById(deliveryId).orElseThrow(); var op=d.getOperatorId(); operator[0]=op.getOperatorId();
            var w=new Warehouse(); w.setName("Planning depot"); w.setAddress("Depot"); w.setLatitude(java.math.BigDecimal.ZERO); w.setLongitude(java.math.BigDecimal.ZERO);
            context.getBean(WarehouseRepository.class).saveAndFlush(w); op.setWarehouse(w); op.setAvailabilityStatus(Operator.AvailabilityStatus.AVAILABLE);
            var other=context.getBean(OperatorRepository.class).findById(secondOperator).orElseThrow(); other.setWarehouse(w);
            other.setAvailabilityStatus(count==2 ? Operator.AvailabilityStatus.AVAILABLE : Operator.AvailabilityStatus.OFF_DUTY);
            d.setOperatorId(null); d.setDeliveryStatus(Delivery.DeliveryStatus.PENDING); d.setScheduledDate(java.time.LocalDate.now()); d.setEndTimeWindow(java.time.LocalTime.MAX);
            if(count==2) { var next=new Delivery(); next.setRecipientId(d.getRecipientId()); next.setDeliveryStatus(Delivery.DeliveryStatus.PENDING);
                next.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); next.setScheduledDate(java.time.LocalDate.now()); next.setEndTimeWindow(java.time.LocalTime.MAX); deliveries.saveAndFlush(next); }
        });
        return operator[0];
    }
    int pausedPlan(CountDownLatch entered,CountDownLatch release) {
        try(var maps=org.mockito.Mockito.mockStatic(com.mmu.schedulingapp.utility.DistanceCalculator.class)) {
            maps.when(() -> com.mmu.schedulingapp.utility.DistanceCalculator.getGoogleMapsDistance(
                    org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> {
                        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Missing test release");
                        return new com.mmu.schedulingapp.utility.DistanceResult(1,"1 min",60);
                    });
            var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(java.time.LocalDate.now());
            context.getBean(com.mmu.schedulingapp.service.RouteService.class).createRoutes(request); return 201;
        } catch(ResponseStatusException e) { return e.getStatusCode().value(); }
    }
    @Test void mapsPauseReleasesMySqlConnectionAndAllowsOperatorEdits() throws Exception {
        var operator=preparePlanning(1); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var planning=workers.supplyAsync(() -> pausedPlan(entered,release));
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(context.getBean(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
            var edit=workers.runAsync(() -> {
                try(var connection=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=connection.createStatement()) {
                    sql.executeUpdate("UPDATE operators SET availability_status='OFF_DUTY' WHERE operator_id="+operator);
                } catch(SQLException e) { throw new RuntimeException(e); }
            }); edit.get(1,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(planning.get(10,TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(context.getBean(RouteRepository.class).count()).isZero();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
    }
    @Test void relatedDataChangedWhileCommitWaitsIsRefreshedAndRejectedOnMySql() throws Exception {
        preparePlanning(1); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var planning=workers.supplyAsync(() -> pausedPlan(entered,release));
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password")) {
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); connection.setAutoCommit(false);
                try(var sql=connection.createStatement()) { sql.executeUpdate("UPDATE recipients SET latitude=10 WHERE recipient_id=(SELECT recipient_id FROM deliveries WHERE delivery_id="+deliveryId+")"); }
                release.countDown();
                assertThatThrownBy(() -> planning.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                connection.commit(); assertThat(planning.get(10,TimeUnit.SECONDS)).isEqualTo(409);
            } finally { release.countDown(); }
        }
        assertThat(context.getBean(RouteRepository.class).count()).isZero();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
    }
    @Test void secondRoutePersistenceFailureRollsBackEntirePlanOnMySql() throws Exception {
        var operator=preparePlanning(2);
        try(var connection=DriverManager.getConnection(url,"root","disposable-test-password"); var sql=connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_second_planned_route BEFORE INSERT ON routes FOR EACH ROW BEGIN IF (SELECT COUNT(*) FROM routes)>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Test second-route failure'; END IF; END");
            try(var maps=org.mockito.Mockito.mockStatic(com.mmu.schedulingapp.utility.DistanceCalculator.class)) {
                maps.when(() -> com.mmu.schedulingapp.utility.DistanceCalculator.getGoogleMapsDistance(
                        org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any()))
                        .thenReturn(new com.mmu.schedulingapp.utility.DistanceResult(1,"1 min",60));
                var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(java.time.LocalDate.now());
                assertThatThrownBy(() -> context.getBean(com.mmu.schedulingapp.service.RouteService.class).createRoutes(request)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { sql.execute("DROP TRIGGER reject_second_planned_route"); }
        }
        assertThat(context.getBean(RouteRepository.class).count()).isZero();
        assertThat(deliveries.findAll()).allSatisfy(d -> { assertThat(d.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
            assertThat(d.getOperatorId()).isNull(); assertThat(d.getFromPrevDistanceKm()).isNull(); });
        assertThat(context.getBean(OperatorRepository.class).findById(operator).orElseThrow().getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
        assertThat(context.getBean(OperatorRepository.class).findById(secondOperator).orElseThrow().getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
    }

    int update(String status) {
        try { service.status(accountId,0L,deliveryId,status); return 200; }
        catch(ResponseStatusException failure) { return failure.getStatusCode().value(); }
    }
    static void await(CountDownLatch start) {
        try { start.await(); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
    }
}
