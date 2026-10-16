package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.dto.route.*;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class RouteGenerationMySqlTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final TestWorkers workers = new TestWorkers();
    static ConfigurableApplicationContext context;
    RouteService service; DeliveryService lifecycle;
    RouteRepository routes; DeliveryRepository deliveries; OperatorRepository operators;
    RecipientRepository recipients; WarehouseRepository warehouses;
    MapsProvider maps; PlatformTransactionManager transactions;
    Integer operatorId, recipientId, warehouseId, deliveryId;
    static final LocalDate DATE=LocalDate.of(2026,10,13);
    @TestConfiguration(proxyBeanMethods=false)
    static class StubMaps { @Bean @Primary MapsProvider stubMapsProvider() { return mock(MapsProvider.class); } }
    @BeforeAll static void application() throws Exception {
        String url=System.getProperty("account.mysql.url");
        try(var db=DriverManager.getConnection(url,"root","disposable-test-password");var sql=db.createStatement()) { sql.execute("CREATE DATABASE route_generation_test"); }
        context=new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class,StubMaps.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run("--spring.datasource.url="+url.replace("account_foundation_test","route_generation_test"),"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password","--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect","--spring.jpa.hibernate.ddl-auto=create-drop","--spring.main.banner-mode=off","--logging.level.root=WARN","--scheduling.maps.planning-timeout-ms=5000","--scheduling.maps.max-deliveries=5","--scheduling.maps.max-provider-calls=20");
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void setup() throws Exception {
        service=context.getBean(RouteService.class); lifecycle=context.getBean(DeliveryService.class); routes=context.getBean(RouteRepository.class); deliveries=context.getBean(DeliveryRepository.class); operators=context.getBean(OperatorRepository.class); recipients=context.getBean(RecipientRepository.class); warehouses=context.getBean(WarehouseRepository.class); transactions=context.getBean(PlatformTransactionManager.class); maps=context.getBean("stubMapsProvider",MapsProvider.class);
        tx(() -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var warehouse=new Warehouse(); warehouse.setName("Depot"); warehouse.setAddress("Depot"); warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO); warehouseId=warehouses.saveAndFlush(warehouse).getWarehouseId();
            var operator=new Operator(); operator.setName("Driver"); operator.setEmail("route-sql@test.local"); operator.setPhoneNumber("123"); operator.setWarehouse(warehouse); operatorId=operators.saveAndFlush(operator).getOperatorId();
            var recipient=new Recipient(); recipient.setFirstName("SQL"); recipient.setLastName("Recipient"); recipient.setAddress("Saved address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); recipientId=recipients.saveAndFlush(recipient).getRecipientId(); deliveryId=delivery(DATE).getDeliveryId();
        });
        reset(maps); when(maps.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); ((PlanningGuard.Budget)call.getArgument(4)).countProviderCall(); return new DistanceResult(1,"1 min",60); });
    }
    void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(t -> work.run()); }
    Delivery delivery(LocalDate date) { var value=new Delivery(); value.setRecipientId(recipients.findById(recipientId).orElseThrow()); value.setScheduledDate(date); value.setStartTimeWindow(LocalTime.of(9,0)); value.setEndTimeWindow(LocalTime.of(12,0)); value.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); return deliveries.saveAndFlush(value); }
    RouteRequestDTO request() { return RouteRequestDTO.builder().scheduledDate(DATE).build(); }
    int generate() { try { service.createRoutes(request()); return 201; } catch(ResponseStatusException failure) { return failure.getStatusCode().value(); } }
    void unassigned() { assertThat(routes.count()).isZero(); assertThat(deliveries.findAll()).allSatisfy(value -> { assertThat(value.getOperatorId()).isNull(); assertThat(value.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING); }); assertThat(operators.findAll()).allSatisfy(value -> assertThat(value.getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE)); }
    void barrier(CountDownLatch entered,CountDownLatch release) throws Exception { doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return new DistanceResult(1,"1 min",60); }).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any()); }
    @Test void generationIncludesOverdueWorkAndCompletedRoutesRemainInFilteredReview() {
        int overdue=delivery(DATE.minusDays(1)).getDeliveryId(), future=delivery(DATE.plusDays(1)).getDeliveryId();
        var created=service.createRoutes(request()); assertThat(created).hasSize(1); assertThat(created.getFirst().getDeliveries()).hasSize(2);
        assertThat(deliveries.findById(future).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        lifecycle.updateDeliveryStatus(deliveryId,Delivery.DeliveryStatus.COMPLETED); lifecycle.updateDeliveryStatus(overdue,Delivery.DeliveryStatus.CANCELLED);
        var result=service.directory(0,20,DATE); assertThat(result.routes()).hasSize(1); assertThat(result.routes().getFirst().routeStatus()).isEqualTo(Route.RouteStatus.COMPLETED); assertThat(result.routes().getFirst().finishedCount()).isEqualTo(2);
        assertThat(operators.findById(operatorId).orElseThrow().getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
    }
    @Test void concurrentPlansHaveOnlyOneCommitOnMySql() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1); barrier(entered,release);
        var first=workers.supplyAsync(this::generate); var second=workers.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
        assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409); assertThat(routes.count()).isEqualTo(1);
    }
    @Test void editedPlanningInputsRejectCommitOnMySql() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); barrier(entered,release); var pending=workers.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); tx(() -> recipients.findById(recipientId).orElseThrow().setAddress("Changed after snapshot")); } finally { release.countDown(); }
        assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo(409); unassigned();
    }
    @Test void secondRouteFailureRollsBackEarlierRouteAndAllAssignmentsOnMySql() throws Exception {
        tx(() -> { var operator=new Operator(); operator.setName("Second"); operator.setEmail("second-route-sql@test.local"); operator.setPhoneNumber("123"); operator.setWarehouse(warehouses.findById(warehouseId).orElseThrow()); operators.saveAndFlush(operator); delivery(DATE); });
        var calls=new AtomicInteger(); doAnswer(call -> new DistanceResult(1,calls.incrementAndGet()==1 ? "1 min" : "x".repeat(65),60)).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        assertThatThrownBy(() -> service.createRoutes(request())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class); unassigned();
    }
    @Test void filteredPagesAreStableAndAcceptStorageDateBoundaries() {
        tx(() -> {
            for(var date:List.of(DATE,DATE,DATE.plusDays(1),LocalDate.of(1000,1,1),LocalDate.of(9999,12,31))) {
                var route=new Route(); route.setOperator(operators.findById(operatorId).orElseThrow()); route.setScheduledDate(date); route.setRouteStatus(Route.RouteStatus.PLANNED); route.setDeliveries(new ArrayList<>()); routes.saveAndFlush(route);
            }
        });
        var first=service.directory(0,1,DATE); var second=service.directory(1,1,DATE); assertThat(first.totalElements()).isEqualTo(2); assertThat(first.routes().getFirst().routeId()).isGreaterThan(second.routes().getFirst().routeId());
        assertThat(service.directory(0,20,LocalDate.of(1000,1,1)).routes()).hasSize(1); assertThat(service.directory(0,20,LocalDate.of(9999,12,31)).routes()).hasSize(1); assertThat(service.directory(2,1,DATE).routes()).isEmpty();
    }
    @Test void assessmentReadsOrderedProjectionsOnMySqlWithoutReplanning() throws Exception {
        tx(() -> delivery(DATE));
        var created=service.createRoutes(request()).getFirst();
        var expected=new ArrayList<Integer>();
        tx(() -> {
            // Set up a saved non-ID order without Hibernate's transient membership swap.
            var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            jdbc.update("update route_deliveries set delivery_order=delivery_order+10 where route_id=?",created.getRouteId());
            jdbc.update("update route_deliveries set delivery_order=11-delivery_order where route_id=?",created.getRouteId());
            var route=routes.findById(created.getRouteId()).orElseThrow();
            route.getDeliveries().getFirst().setFromPrevDurationSeconds(null);
            expected.addAll(route.getDeliveries().stream().map(Delivery::getDeliveryId).toList()); deliveries.flush();
        });
        var assessment=context.getBean(RouteWindowAssessmentService.class).assess(created.getRouteId(),
                new RouteWindowAssessmentRequest(DATE.atTime(8,0),600L));
        assertThat(assessment.stops()).extracting(RouteWindowAssessment.Stop::deliveryId).containsExactlyElementsOf(expected);
        assertThat(assessment.stops()).allSatisfy(stop -> assertThat(stop.status()).isEqualTo(com.mmu.schedulingapp.service.autorouting.DeliveryWindowAssessor.Status.UNKNOWN));
        assertThat(assessment.hypothetical()).isTrue(); assertThat(routes.count()).isEqualTo(1);
        assertThat(deliveries.findAll()).allSatisfy(d -> { assertThat(d.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS); assertThat(d.getOperatorId().getOperatorId()).isEqualTo(operatorId); });
        verify(maps,times(2)).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
    }

}
