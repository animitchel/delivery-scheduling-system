package com.mmu.schedulingapp.utility;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.service.autorouting.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoutePlannerTest {
    @Test void fixedSnapshotProducesRepeatableOrderWithoutChangingEntities() throws Exception {
        var w=new Warehouse(); w.setWarehouseId(1); w.setLatitude(BigDecimal.ZERO); w.setLongitude(BigDecimal.ZERO);
        var o=new Operator(); o.setOperatorId(1); o.setWarehouse(w);
        var p=new Recipient(); p.setRecipientId(1); p.setLatitude(BigDecimal.ONE); p.setLongitude(BigDecimal.ONE);
        var low=delivery(1,p,Delivery.PriorityLevel.LOW);
        var high=delivery(2,p,Delivery.PriorityLevel.HIGH);
        var snapshot=new PlanningSnapshot(LocalDate.of(2026,1,1),LocalDateTime.of(2026,1,1,12,0),
                List.of(PlanningSnapshot.OperatorInput.from(o)),List.of(PlanningSnapshot.DeliveryInput.from(low),PlanningSnapshot.DeliveryInput.from(high)));
        var provider=mock(MapsProvider.class);
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall(); return new DistanceResult(1,"1 min",60);
        });
        var limits=new MapsLimits(); limits.setMaxProviderCalls(2); var planner=new RoutePlanner(provider);
        CalculatedPlan first,second;
        try(var budget=new PlanningGuard.Budget(limits,() -> 0,() -> {})) { first=planner.calculate(snapshot,budget); }
        try(var budget=new PlanningGuard.Budget(limits,() -> 0,() -> {})) { second=planner.calculate(snapshot,budget); }
        assertThat(first).isEqualTo(second);
        assertThat(first.routes().get(0).stops()).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(2,1);
        // Only two distinct legs per calculation, despite repeated candidate/cache checks.
        verify(provider,times(4)).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        assertThat(low.getOperatorId()).isNull(); assertThat(high.getOperatorId()).isNull();
        assertThat(low.getFromPrevDistanceKm()).isNull(); assertThat(high.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(o.getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
        assertThatThrownBy(() -> snapshot.deliveries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.routes().get(0).stops().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"14,false","50,false","14,true","50,true"})
    void distinctStopsFitDefaultBudgetWithAtMostOneProviderCallPerStop(int count,boolean unavailable) throws Exception {
        var warehouse=new Warehouse(); warehouse.setWarehouseId(1);
        warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO);
        var operator=new Operator(); operator.setOperatorId(1); operator.setWarehouse(warehouse);
        var inputs=new java.util.ArrayList<PlanningSnapshot.DeliveryInput>();
        for(int id=1;id<=count;id++) {
            var recipient=new Recipient(); recipient.setRecipientId(id);
            recipient.setLatitude(BigDecimal.valueOf(id).movePointLeft(2)); recipient.setLongitude(BigDecimal.ONE);
            inputs.add(PlanningSnapshot.DeliveryInput.from(delivery(id,recipient,Delivery.PriorityLevel.MEDIUM)));
        }
        var snapshot=new PlanningSnapshot(LocalDate.of(2026,1,1),LocalDateTime.of(2026,1,1,12,0),
                List.of(PlanningSnapshot.OperatorInput.from(operator)),inputs);
        var provider=mock(MapsProvider.class);
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall();
            if(unavailable) throw new MapsFailure(MapsFailure.Kind.UNAVAILABLE);
            return new DistanceResult(7,"Road estimate",420);
        });
        try(var budget=new PlanningGuard.Budget(new MapsLimits(),() -> 0,() -> {})) {
            var stops=new RoutePlanner(provider).calculate(snapshot,budget).routes().get(0).stops();
            assertThat(stops).extracting(CalculatedPlan.Stop::deliveryId)
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,count).boxed().toList());
            assertThat(stops).allSatisfy(stop -> {
                if(unavailable) {
                    assertThat(stop.distanceKm()).isPositive(); assertThat(stop.durationSeconds()).isNull();
                    assertThat(stop.durationText()).isEqualTo("Estimated distance; time unavailable");
                } else {
                    assertThat(stop.distanceKm()).isEqualTo(7); assertThat(stop.durationSeconds()).isEqualTo(420);
                }
            });
        }
        verify(provider,times(count)).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
    }
    @Test void localRankingRetainsPriorityAndChecksDeadlineBeforeCallingProvider() {
        var w=new PlanningSnapshot.WarehouseInput(1,"warehouse","address",BigDecimal.ZERO,BigDecimal.ZERO);
        var o=new PlanningSnapshot.OperatorInput(1,"operator","phone","email","van",1.0,Operator.AvailabilityStatus.AVAILABLE,w);
        var near=new Recipient(); near.setLatitude(new BigDecimal("0.001")); near.setLongitude(BigDecimal.ZERO);
        var farther=new Recipient(); farther.setLatitude(new BigDecimal("0.002")); farther.setLongitude(BigDecimal.ZERO);
        var low=PlanningSnapshot.DeliveryInput.from(delivery(1,near,Delivery.PriorityLevel.LOW));
        var high=PlanningSnapshot.DeliveryInput.from(delivery(2,farther,Delivery.PriorityLevel.HIGH));
        var now=LocalDateTime.of(2026,1,1,12,0);
        var builder=new RouteBuilder(o,now,(a,b,c,d) -> new DistanceResult(99,"Road estimate",120),() -> {});
        assertThat(builder.buildOptimizedRoute(List.of(low,high))).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(2,1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var expired=new RouteBuilder(o,now,(a,b,c,d) -> { throw new AssertionError("Provider must not run after local deadline"); },
                () -> { if(calls.incrementAndGet()==2) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT); });
        assertThatThrownBy(() -> expired.buildOptimizedRoute(List.of(low,high)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void urgencyUsesCapturedTimeAndPreservesOverdueWeighting() {
        var p=new Recipient(); p.setRecipientId(1); p.setPriorityLevel(Recipient.PriorityLevel.MEDIUM);
        var d=delivery(1,p,Delivery.PriorityLevel.MEDIUM); d.setEndTimeWindow(LocalTime.of(10,0));
        var score=DeliveryScore.calculate(PlanningSnapshot.DeliveryInput.from(d),2,LocalDateTime.of(2026,1,1,12,0));
        assertThat(score.urgency()).isEqualTo(-6);
        assertThat(score.total()).isEqualTo(10); // priority 12 + distance 4 - urgency 6
    }
    Delivery delivery(int id,Recipient p,Delivery.PriorityLevel priority) {
        var d=new Delivery(); d.setDeliveryId(id); d.setRecipientId(p); d.setPriorityLevel(priority);
        d.setScheduledDate(LocalDate.of(2026,1,1)); d.setEndTimeWindow(LocalTime.of(18,0)); return d;
    }
}
