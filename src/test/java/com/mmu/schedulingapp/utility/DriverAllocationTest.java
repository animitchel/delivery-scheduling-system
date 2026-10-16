package com.mmu.schedulingapp.utility;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.service.autorouting.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DriverAllocationTest {
    private final LocalDate date=LocalDate.of(2026,1,1);
    PlanningSnapshot.OperatorInput operator(int id,double longitude) {
        var warehouse=new PlanningSnapshot.WarehouseInput(id,"warehouse","address",BigDecimal.ZERO,BigDecimal.valueOf(longitude));
        return new PlanningSnapshot.OperatorInput(id,"operator","phone","email","van",1.0,Operator.AvailabilityStatus.AVAILABLE,warehouse);
    }
    PlanningSnapshot.DeliveryInput delivery(int id,double longitude) {
        var recipient=new PlanningSnapshot.RecipientInput(id,"first","last","address","phone",BigDecimal.ZERO,BigDecimal.valueOf(longitude),Recipient.PriorityLevel.MEDIUM);
        return new PlanningSnapshot.DeliveryInput(id,recipient,null,date,LocalTime.MIN,LocalTime.of(18,0),Delivery.PriorityLevel.MEDIUM,Delivery.DeliveryStatus.PENDING,null);
    }
    CalculatedPlan plan(List<PlanningSnapshot.OperatorInput> operators,List<PlanningSnapshot.DeliveryInput> deliveries) throws Exception {
        var provider=mock(MapsProvider.class);
        when(provider.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            PlanningGuard.Budget budget=call.getArgument(4); budget.countProviderCall();
            return new DistanceResult(1,"estimate",60);
        });
        var limits=new MapsLimits(); limits.setMaxProviderCalls(Math.max(1,deliveries.size()));
        try(var budget=new PlanningGuard.Budget(limits,() -> 0,() -> {})) {
            var result=new RoutePlanner(provider).calculate(new PlanningSnapshot(date,date.atTime(12,0),operators,deliveries),budget);
            verify(provider,atMost(deliveries.size())).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
            return result;
        }
    }
    @Test void separatedWarehousesReceiveNearbyWorkInsteadOfIdChunks() throws Exception {
        var operators=List.of(operator(1,0),operator(2,10));
        var deliveries=List.of(delivery(1,9.9),delivery(2,.1),delivery(3,9.8),delivery(4,.2));
        var result=plan(operators,deliveries);
        assertThat(result.routes().get(0).stops()).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(2,4);
        assertThat(result.routes().get(1).stops()).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(1,3);
        // All fixture points are on the equator: angular separation measures warehouse proximity.
        // This is not a road-distance or whole-route benchmark.
        double baseline=9.9+.1+(10-9.8)+(10-.2), allocated=0;
        for(var route:result.routes()) for(var stop:route.stops()) {
            double origin=operators.get(route.operatorId()-1).warehouse().longitude().doubleValue();
            double destination=deliveries.get(stop.deliveryId()-1).recipient().longitude().doubleValue();
            allocated+=Math.abs(origin-destination);
        }
        assertThat(allocated).isLessThan(baseline*.05);

    }
    @Test void equalDistanceTiesIgnoreInputOrderAndKeepBalancedQuotas() throws Exception {
        var operators=List.of(operator(1,0),operator(2,0));
        var deliveries=java.util.stream.IntStream.rangeClosed(1,5).mapToObj(id -> delivery(id,.5)).toList();
        var first=plan(operators,deliveries);
        var shuffled=plan(operators.reversed(),deliveries.reversed());
        assertThat(first.routes()).isEqualTo(shuffled.routes());
        assertThat(first.routes().get(0).stops()).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(1,2,3);
        assertThat(first.routes().get(1).stops()).extracting(CalculatedPlan.Stop::deliveryId).containsExactly(4,5);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,5,50})
    void skewedGeographyStillAssignsEachStopOnceWithBalancedCounts(int count) throws Exception {
        var operators=java.util.stream.IntStream.rangeClosed(1,Math.min(3,count)).mapToObj(id -> operator(id,(id-1)*10)).toList();
        var deliveries=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(id -> delivery(id,id*.001)).toList();
        var result=plan(operators,deliveries);
        assertThat(result.routes()).hasSize(operators.size());
        assertThat(result.routes().stream().flatMap(route -> route.stops().stream()).map(CalculatedPlan.Stop::deliveryId).toList())
                .containsExactlyInAnyOrderElementsOf(deliveries.stream().map(PlanningSnapshot.DeliveryInput::id).toList());
        var counts=result.routes().stream().mapToInt(route -> route.stops().size()).summaryStatistics();
        assertThat(counts.getMax()-counts.getMin()).isLessThanOrEqualTo(1);
    }
    @Test void deadlineDuringAllocationStopsBeforeAnyMapsCall() {
        var provider=mock(MapsProvider.class);
        var inputs=java.util.stream.IntStream.rangeClosed(1,50).mapToObj(id -> delivery(id,id*.001)).toList();
        var limits=new MapsLimits(); limits.setPlanningTimeoutMs(10);
        var ticks=new java.util.concurrent.atomic.AtomicLong();
        try(var budget=new PlanningGuard.Budget(limits,() -> ticks.getAndAdd(1_000_000),() -> {})) {
            assertThatThrownBy(() -> new RoutePlanner(provider).calculate(new PlanningSnapshot(date,date.atTime(12,0),
                    List.of(operator(1,0),operator(2,10)),inputs),budget))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(504));
        }
        verifyNoInteractions(provider);
    }

}
