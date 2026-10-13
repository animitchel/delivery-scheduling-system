package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@ActiveProfiles("java25-test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:planning-transactions;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class RoutePlanningTransactionsIntegrationTest {
    @Autowired AccountRepository accounts;
    @Autowired RouteService service;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired WarehouseRepository warehouses;
    @Autowired DeliveryService editing;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;
    @Autowired MockMvc mvc;
    @MockitoBean MapsProvider provider;
    Integer operatorId,deliveryId,recipientId,warehouseId;
    @BeforeEach void fixtures() {
        tx(() -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush();
            recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var w=new Warehouse(); w.setName("Depot"); w.setAddress("Depot address"); w.setLatitude(BigDecimal.ZERO); w.setLongitude(BigDecimal.ZERO);
            warehouses.saveAndFlush(w); warehouseId=w.getWarehouseId();
            var o=new Operator(); o.setName("Driver"); o.setEmail("planning@test.local"); o.setPhoneNumber("123"); o.setWarehouse(w);
            operators.saveAndFlush(o); operatorId=o.getOperatorId();
            var p=new Recipient(); p.setFirstName("A"); p.setLastName("Recipient"); p.setAddress("Address"); p.setLatitude(BigDecimal.ONE); p.setLongitude(BigDecimal.ONE);
            recipients.saveAndFlush(p); recipientId=p.getRecipientId();
            var d=new Delivery(); d.setRecipientId(p); d.setScheduledDate(LocalDate.now()); d.setStartTimeWindow(LocalTime.MIN); d.setEndTimeWindow(LocalTime.MAX);
            d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(d); deliveryId=d.getDeliveryId();
        });
    }
    void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(t -> work.run()); }
    com.mmu.schedulingapp.dto.route.RouteRequestDTO request() { var q=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); q.setScheduledDate(LocalDate.now()); return q; }
    int generate() {
        try { service.createRoutes(request()); return 201; } catch(ResponseStatusException e) { return e.getStatusCode().value(); }
    }
    void providerBarrier(CountDownLatch entered,CountDownLatch release) throws Exception {
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Missing test release");
            return new DistanceResult(1,"1 min",60);
        });
    }
    void unassigned() {
        assertThat(routes.count()).isZero();
        assertThat(deliveries.findAll()).allSatisfy(d -> { assertThat(d.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
            assertThat(d.getOperatorId()).isNull(); assertThat(d.getFromPrevDistanceKm()).isNull(); });
    }
    @Test void mapsPauseHoldsNeitherTransactionConnectionNorAssignmentLocks() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); providerBarrier(entered,release);
        var planning=CompletableFuture.supplyAsync(this::generate);
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            var edit=CompletableFuture.runAsync(() -> tx(() -> deliveries.findById(deliveryId).orElseThrow().setSpecialInstructions("New instructions")));
            edit.get(1,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(planning.get(5,TimeUnit.SECONDS)).isEqualTo(409); unassigned();
        assertThat(deliveries.findById(deliveryId).orElseThrow().getSpecialInstructions()).isEqualTo("New instructions");
    }
    @ParameterizedTest @ValueSource(strings={"date","startWindow","endWindow","priority","recipientAddress","recipientCoordinates","recipientPriority","operatorAvailability","operatorCapacity","warehouseCoordinates","warehouseChange"})
    void changedInputsRejectWholePlan(String change) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); providerBarrier(entered,release);
        var planning=CompletableFuture.supplyAsync(this::generate);
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            tx(() -> {
                var d=deliveries.findById(deliveryId).orElseThrow(); var p=d.getRecipientId(); var o=operators.findById(operatorId).orElseThrow();
                switch(change) {
                    case "date" -> d.setScheduledDate(LocalDate.now().plusDays(1));
                    case "startWindow" -> d.setStartTimeWindow(LocalTime.NOON);
                    case "endWindow" -> d.setEndTimeWindow(LocalTime.NOON);
                    case "priority" -> d.setPriorityLevel(Delivery.PriorityLevel.HIGH);
                    case "recipientAddress" -> p.setAddress("Updated address");
                    case "recipientCoordinates" -> p.setLatitude(BigDecimal.TEN);
                    case "recipientPriority" -> p.setPriorityLevel(Recipient.PriorityLevel.HIGH);
                    case "operatorAvailability" -> o.setAvailabilityStatus(Operator.AvailabilityStatus.OFF_DUTY);
                    case "operatorCapacity" -> o.setMaxCapacity(123.0);
                    case "warehouseCoordinates" -> o.getWarehouse().setLongitude(BigDecimal.TEN);
                    case "warehouseChange" -> { var w=new Warehouse(); w.setName("Other depot"); w.setAddress("Other address"); w.setLatitude(BigDecimal.TEN); w.setLongitude(BigDecimal.TEN); warehouses.saveAndFlush(w); o.setWarehouse(w); }
                    default -> throw new AssertionError(change);
                }
            });
        } finally { release.countDown(); }
        assertThat(planning.get(5,TimeUnit.SECONDS)).isEqualTo(409); unassigned();
    }
    @Test void deletedDeliveryRejectsPlanWithoutResurrectingIt() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); providerBarrier(entered,release);
        var planning=CompletableFuture.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(editing.deleteDelivery(deliveryId)).isPresent(); }
        finally { release.countDown(); }
        assertThat(planning.get(5,TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(deliveries.findById(deliveryId)).isEmpty(); assertThat(routes.count()).isZero();
    }
    @Test void deletedOperatorRejectsPlanWithoutResurrectingIt() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); providerBarrier(entered,release);
        var planning=CompletableFuture.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); tx(() -> operators.deleteById(operatorId)); }
        finally { release.countDown(); }
        assertThat(planning.get(5,TimeUnit.SECONDS)).isEqualTo(409); unassigned();
    }
    @Test void twoPlannersCalculateTogetherButOnlyOneCommits() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1); providerBarrier(entered,release);
        var first=CompletableFuture.supplyAsync(this::generate); var second=CompletableFuture.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); }
        finally { release.countDown(); }
        assertThat(java.util.List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        assertThat(routes.count()).isEqualTo(1); assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
    }
    @Test void persistenceFailureOnSecondRouteRollsBackFirstRouteAndAllAssignments() throws Exception {
        tx(() -> {
            var o=new Operator(); o.setName("Second"); o.setEmail("second-planning@test.local"); o.setPhoneNumber("123"); o.setWarehouse(warehouses.findById(warehouseId).orElseThrow()); operators.saveAndFlush(o);
            var d=new Delivery(); d.setRecipientId(recipients.findById(recipientId).orElseThrow()); d.setScheduledDate(LocalDate.now()); d.setEndTimeWindow(LocalTime.MAX); d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(d);
        });
        var calls=new AtomicInteger();
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new DistanceResult(1,calls.incrementAndGet()==1 ? "1 min" : "x".repeat(65),60);
        });
        assertThatThrownBy(() -> service.createRoutes(request())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(calls).hasValue(2); unassigned();
        assertThat(operators.findAll()).allSatisfy(o -> assertThat(o.getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE));
    }
    @Test void callersCannotKeepAnOuterTransactionOpenDuringCalculation() {
        assertThatThrownBy(() -> tx(() -> service.createRoutes(request()))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(provider); unassigned();
    }
    @Test void requestScopedEntityManagerCannotReuseSnapshotEraFields() throws Exception {
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CompletableFuture.runAsync(() -> tx(() -> recipients.findById(recipientId).orElseThrow().setAddress("Changed during HTTP request"))).get(1,TimeUnit.SECONDS);
            return new DistanceResult(1,"1 min",60);
        });
        var account=accounts.saveAndFlush(new UserAccount("planning.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false));
        var result=mvc.perform(post("/api/v1/scheduling-app/routes")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(new AccountPrincipal(account))).with(csrf()).contentType("application/json")
                .content("{\"scheduledDate\":\""+LocalDate.now()+"\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409); unassigned();
    }
}
