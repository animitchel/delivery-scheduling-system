package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.RouteService;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ActiveProfiles("java25-test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:planning-bounds;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop",
        "scheduling.maps.max-deliveries=2","scheduling.maps.max-provider-calls=1",
        "scheduling.maps.planning-timeout-ms=1000","scheduling.maps.max-concurrent-planning=1"})
class RoutePlanningBoundsIntegrationTest {
    @Autowired RouteService service;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired WarehouseRepository warehouses;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean MapsProvider provider;
    @BeforeEach void fixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(t -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); operators.deleteAll(); operators.flush();
            recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var warehouse=new Warehouse(); warehouse.setName("Depot"); warehouse.setAddress("Depot");
            warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO); warehouses.saveAndFlush(warehouse);
            var recipient=new Recipient(); recipient.setFirstName("A"); recipient.setLastName("Recipient"); recipient.setAddress("Private address");
            recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); recipients.saveAndFlush(recipient);
            for(int i=0;i<2;i++) {
                var operator=new Operator(); operator.setName("Driver "+i); operator.setPhoneNumber("123"); operator.setEmail("bounds"+i+"@test.local");
                operator.setWarehouse(warehouse); operators.saveAndFlush(operator);
                var delivery=new Delivery(); delivery.setRecipientId(recipient); delivery.setScheduledDate(LocalDate.now());
                delivery.setStartTimeWindow(LocalTime.MIN); delivery.setEndTimeWindow(LocalTime.MAX);
                delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(delivery);
            }
        });
    }
    com.mmu.schedulingapp.dto.route.RouteRequestDTO request() {
        var request=new com.mmu.schedulingapp.dto.route.RouteRequestDTO(); request.setScheduledDate(LocalDate.now()); return request;
    }
    void unchanged() {
        assertThat(routes.count()).isZero();
        assertThat(deliveries.findAll()).allSatisfy(d -> {
            assertThat(d.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
            assertThat(d.getOperatorId()).isNull(); assertThat(d.getFromPrevDistanceKm()).isNull();
        });
        assertThat(operators.findAll()).allSatisfy(o -> assertThat(o.getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE));
    }
    void fails(int status) {
        assertThatThrownBy(() -> service.createRoutes(request())).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(status));
    }
    @Test void sharedCallBudgetRollsBackFirstDriverRouteWhenSecondDriverExceedsIt() throws Exception {
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); return new DistanceResult(1,"1 min",60);
        });
        fails(422); unchanged();
        verify(provider,times(2)).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
    }
    @Test void oversizedWorkloadIsRejectedBeforeProviderAndWrites() {
        new TransactionTemplate(transactions).executeWithoutResult(t -> {
            var d=new Delivery(); d.setRecipientId(recipients.findAll().get(0)); d.setScheduledDate(LocalDate.now());
            d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(d);
        });
        fails(413); verifyNoInteractions(provider); unchanged();
    }
    @Test void deadlineCannotBeSwallowedByFallbackAndLaterRequestCanSucceed() throws Exception {
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); Thread.sleep(1100);
            throw new MapsFailure(MapsFailure.Kind.TIMEOUT);
        });
        fails(504); unchanged();
        reset(provider);
        new TransactionTemplate(transactions).executeWithoutResult(t -> {
            var all=deliveries.findAll(); deliveries.delete(all.get(1));
        });
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); return new DistanceResult(1,"1 min",60);
        });
        assertThat(service.createRoutes(request())).hasSize(1);
    }
    @Test void concurrentPlanningIsRejectedWithoutWaitingForDatabaseLocks() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Test release missing");
            return new DistanceResult(1,"1 min",60);
        });
        var first=CompletableFuture.runAsync(() -> fails(422));
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); fails(429); }
        finally { release.countDown(); }
        first.get(5,TimeUnit.SECONDS); unchanged();
    }
}
