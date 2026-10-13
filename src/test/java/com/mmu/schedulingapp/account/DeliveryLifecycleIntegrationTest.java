package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.driver.DriverAssignmentService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("java25-test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:delivery-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
class DeliveryLifecycleIntegrationTest {
    @Autowired DeliveryRepository deliveries;
    @Autowired RouteRepository routes;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired WarehouseRepository warehouses;
    @Autowired AccountRepository accounts;
    @Autowired DeliveryService admin;
    @Autowired RouteService routing;
    @Autowired DriverAssignmentService driver;
    @Autowired PlatformTransactionManager transactions;
    Integer operatorId, routeId, firstId, secondId;
    Long accountId;

    @BeforeEach void fixtures() {
        tx(() -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush();
            operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var warehouse = new Warehouse(); warehouse.setName("Depot"); warehouse.setAddress("Depot");
            warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO); warehouses.saveAndFlush(warehouse);
            var operator = new Operator(); operator.setName("Driver"); operator.setEmail("lifecycle@test.local"); operator.setPhoneNumber("123");
            operator.setWarehouse(warehouse); operator.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY); operators.saveAndFlush(operator); operatorId=operator.getOperatorId();
            accountId=accounts.saveAndFlush(new UserAccount("lifecycle.driver", "{bcrypt}$2a$12$"+"a".repeat(53), AccountRole.DRIVER,operator,false)).getId();
            var recipient = new Recipient(); recipient.setFirstName("A"); recipient.setLastName("Recipient"); recipient.setAddress("Address");
            recipient.setLatitude(BigDecimal.ZERO); recipient.setLongitude(BigDecimal.ZERO); recipients.saveAndFlush(recipient);
            var first = delivery(operator,recipient,Delivery.DeliveryStatus.IN_PROGRESS); firstId=first.getDeliveryId();
            var second = delivery(operator,recipient,Delivery.DeliveryStatus.IN_PROGRESS); secondId=second.getDeliveryId();
            routeId=route(operator,List.of(first,second),Route.RouteStatus.IN_PROGRESS).getRouteId();
        });
    }
    Delivery delivery(Operator operator,Recipient recipient,Delivery.DeliveryStatus status) {
        var d=new Delivery(); d.setOperatorId(operator); d.setRecipientId(recipient); d.setDeliveryStatus(status);
        d.setStartTimeWindow(java.time.LocalTime.MIN); d.setEndTimeWindow(java.time.LocalTime.MAX);
        d.setScheduledDate(LocalDate.now()); d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);
        d.setFromPrevDurationSeconds(12); d.setFromPrevDistanceKm(0.1); d.setFromPrevDurationText("12 seconds");
        return deliveries.saveAndFlush(d);
    }
    Route route(Operator operator,List<Delivery> members,Route.RouteStatus status) {
        var r=new Route(); r.setOperator(operator); r.setDeliveries(new ArrayList<>(members)); r.setScheduledDate(LocalDate.now()); r.setRouteStatus(status);
        return routes.saveAndFlush(r);
    }
    void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(t -> work.run()); }
    void state(Route.RouteStatus routeStatus,Operator.AvailabilityStatus availability) {
        assertThat(routes.findById(routeId).orElseThrow().getRouteStatus()).isEqualTo(routeStatus);
        assertThat(operators.findById(operatorId).orElseThrow().getAvailabilityStatus()).isEqualTo(availability);
    }
    void conflict(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
    }
    @Test void mixedDriverAndAdministratorStopsFinalizeOnlyAfterLastStop() {
        driver.status(accountId,0L,firstId,"COMPLETED");
        state(Route.RouteStatus.IN_PROGRESS,Operator.AvailabilityStatus.ON_DELIVERY);
        admin.updateDeliveryStatus(secondId,Delivery.DeliveryStatus.CANCELLED);
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.AVAILABLE);
        var saved=deliveries.findById(firstId).orElseThrow();
        assertThat(saved.getOperatorId().getOperatorId()).isEqualTo(operatorId);
        assertThat(saved.getFromPrevDurationSeconds()).isEqualTo(12);
        assertThat(driver.route(accountId,0L,routeId).getDeliveries()).hasSize(2);
    }
    @Test void allCancelledStopsAlsoFinalizeRoute() {
        admin.updateDeliveryStatus(firstId,Delivery.DeliveryStatus.CANCELLED);
        driver.status(accountId,0L,secondId,"CANCELLED");
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.AVAILABLE);
    }
    @Test void anotherActiveRouteKeepsDriverBusy() {
        tx(() -> route(operators.findById(operatorId).orElseThrow(),List.of(),Route.RouteStatus.IN_PROGRESS));
        driver.status(accountId,0L,firstId,"COMPLETED"); driver.status(accountId,0L,secondId,"COMPLETED");
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.ON_DELIVERY);
    }
    @Test void unavailableOperatorIsNotAutomaticallyMadeAvailable() {
        tx(() -> operators.findById(operatorId).orElseThrow().setAvailabilityStatus(Operator.AvailabilityStatus.OFF_DUTY));
        driver.status(accountId,0L,firstId,"COMPLETED"); driver.status(accountId,0L,secondId,"COMPLETED");
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.OFF_DUTY);
    }
    @Test void terminalWorkCannotBeReopenedOrChanged() {
        admin.updateDeliveryStatus(firstId,Delivery.DeliveryStatus.COMPLETED);
        for(var target:Delivery.DeliveryStatus.values()) conflict(() -> admin.updateDeliveryStatus(firstId,target));
        conflict(() -> driver.status(accountId,0L,firstId,"CANCELLED"));
        assertThat(deliveries.findById(firstId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.COMPLETED);
    }
    @Test void pendingWorkCannotBeStartedByStatusPatch() {
        tx(() -> deliveries.findById(firstId).orElseThrow().setDeliveryStatus(Delivery.DeliveryStatus.PENDING));
        for(var target:Delivery.DeliveryStatus.values()) conflict(() -> admin.updateDeliveryStatus(firstId,target));
    }
    @Test void activeAndHistoricalRoutesCannotBeDeletedEvenInBulk() {
        conflict(() -> routing.deleteRoute(routeId));
        tx(() -> route(operators.findById(operatorId).orElseThrow(),List.of(),Route.RouteStatus.PLANNED));
        conflict(() -> routing.deleteAllRoutes()); assertThat(routes.count()).isEqualTo(2);
        driver.status(accountId,0L,firstId,"COMPLETED"); driver.status(accountId,0L,secondId,"COMPLETED");
        conflict(() -> routing.deleteRoute(routeId)); conflict(() -> routing.deleteAllRoutes());
        assertThat(deliveries.findById(firstId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.COMPLETED);
        assertThat(routes.count()).isEqualTo(2);
    }
    @Test void routedAndTerminalDeliveriesCannotBeDeleted() {
        conflict(() -> admin.deleteDelivery(firstId));
        admin.updateDeliveryStatus(firstId,Delivery.DeliveryStatus.COMPLETED);
        conflict(() -> admin.deleteDelivery(firstId)); assertThat(deliveries.count()).isEqualTo(2);
    }
    @Test void onlyEmptyPlannedRoutesAndUnroutedPendingDeliveriesCanBeDeleted() {
        final Integer[] empty={null},pending={null};
        tx(() -> {
            var op=operators.findById(operatorId).orElseThrow();
            empty[0]=route(op,List.of(),Route.RouteStatus.PLANNED).getRouteId();
            pending[0]=delivery(null,recipients.findAll().get(0),Delivery.DeliveryStatus.PENDING).getDeliveryId();
        });
        assertThat(routing.deleteRoute(empty[0])).isPresent(); assertThat(admin.deleteDelivery(pending[0])).isPresent();
        state(Route.RouteStatus.IN_PROGRESS,Operator.AvailabilityStatus.ON_DELIVERY);
    }
    @Test void completedRouteAllowsNextAssignmentWithoutReusingTerminalStops() {
        driver.status(accountId,0L,firstId,"COMPLETED"); driver.status(accountId,0L,secondId,"CANCELLED");
        final Integer[] pending={null};
        tx(() -> pending[0]=delivery(null,recipients.findAll().get(0),Delivery.DeliveryStatus.PENDING).getDeliveryId());
        var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(LocalDate.now());
        // Isolate the provider boundary: no Google requests or application credentials.
        try(var maps=org.mockito.Mockito.mockStatic(com.mmu.schedulingapp.utility.DistanceCalculator.class)) {
            maps.when(() -> com.mmu.schedulingapp.utility.DistanceCalculator.getGoogleMapsDistance(
                    org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.mmu.schedulingapp.utility.DistanceResult(0,"0 seconds",0));
            var next=routing.createRoutes(request);
            assertThat(next).hasSize(1); assertThat(next.get(0).getDeliveries()).extracting(d -> d.getDeliveryId()).containsExactly(pending[0]);
        }
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.ON_DELIVERY);
        assertThat(deliveries.findById(firstId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.COMPLETED);
    }
    @Test void availableFlagCannotCauseSecondActiveAssignment() {
        tx(() -> operators.findById(operatorId).orElseThrow().setAvailabilityStatus(Operator.AvailabilityStatus.AVAILABLE));
        var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(LocalDate.now());
        conflict(() -> routing.createRoutes(request)); assertThat(routes.count()).isEqualTo(1);
    }
    @Test void concurrentDifferentFinalStopsFinalizeExactlyOnce() throws Exception {
        var start=new CountDownLatch(1);
        var one=CompletableFuture.runAsync(() -> { await(start); driver.status(accountId,0L,firstId,"COMPLETED"); });
        var two=CompletableFuture.runAsync(() -> { await(start); admin.updateDeliveryStatus(secondId,Delivery.DeliveryStatus.CANCELLED); });
        start.countDown(); one.get(10,TimeUnit.SECONDS); two.get(10,TimeUnit.SECONDS);
        state(Route.RouteStatus.COMPLETED,Operator.AvailabilityStatus.AVAILABLE);
    }
    static void await(CountDownLatch start) {
        try { start.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
