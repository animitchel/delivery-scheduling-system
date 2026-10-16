package com.mmu.schedulingapp.utility;

import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.service.autorouting.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeliveryWindowAssessorTest {
    private final LocalDate day=LocalDate.of(2026,1,2);
    PlanningSnapshot.DeliveryInput delivery(int id,LocalDate date,String start,String end) {
        return new PlanningSnapshot.DeliveryInput(id,null,null,date,LocalTime.parse(start),LocalTime.parse(end),
                Delivery.PriorityLevel.MEDIUM,Delivery.DeliveryStatus.PENDING,null);
    }
    CalculatedPlan.Stop stop(int id,Integer seconds) { return new CalculatedPlan.Stop(id,1,"estimate",seconds); }
    CalculatedPlan plan(List<PlanningSnapshot.DeliveryInput> deliveries,CalculatedPlan.DriverRoute... routes) {
        // Cutoff/scoring time deliberately differ from the caller's explicit departure.
        return new CalculatedPlan(new PlanningSnapshot(day.minusDays(1),day.minusDays(2).atStartOfDay(),List.of(),deliveries),List.of(routes));
    }
    @Test void waitsForOpeningAndAccumulatesTravelAndServiceIntoLaterLateness() {
        var plan=plan(List.of(delivery(1,day,"09:00","10:00"),delivery(2,day,"09:00","09:15")),
                new CalculatedPlan.DriverRoute(1,List.of(stop(1,900),stop(2,600))));
        var result=DeliveryWindowAssessor.assess(plan,day.atTime(8,0),Duration.ofMinutes(10));
        var first=result.routes().get(0).stops().get(0); var second=result.routes().get(0).stops().get(1);
        assertThat(first.arrival()).isEqualTo(day.atTime(8,15)); assertThat(first.serviceStart()).isEqualTo(day.atTime(9,0));
        assertThat(first.waiting()).isEqualTo(Duration.ofMinutes(45)); assertThat(first.departure()).isEqualTo(day.atTime(9,10));
        assertThat(first.status()).isEqualTo(DeliveryWindowAssessor.Status.WITHIN_WINDOW);
        assertThat(second.arrival()).isEqualTo(day.atTime(9,20)); assertThat(second.lateness()).isEqualTo(Duration.ofMinutes(5));
        assertThat(second.status()).isEqualTo(DeliveryWindowAssessor.Status.LATE);
        assertThat(result.departure()).isEqualTo(day.atTime(8,0)); assertThat(plan.routes().get(0).stops()).hasSize(2);
    }
    @Test void closingTimeIsInclusiveAndServiceMayFinishAfterWindow() {
        var result=DeliveryWindowAssessor.assess(plan(List.of(delivery(1,day,"09:00","10:00")),
                new CalculatedPlan.DriverRoute(1,List.of(stop(1,0)))),day.atTime(10,0),Duration.ofMinutes(30));
        var stop=result.routes().get(0).stops().get(0);
        assertThat(stop.status()).isEqualTo(DeliveryWindowAssessor.Status.WITHIN_WINDOW);
        assertThat(stop.waiting()).isEqualTo(Duration.ZERO); assertThat(stop.lateness()).isEqualTo(Duration.ZERO);
        assertThat(stop.departure()).isEqualTo(day.atTime(10,30));
    }
    @Test void missingEstimateMakesThatStopAndDownstreamUnknownButOtherDriversRemainIndependent() {
        var inputs=java.util.stream.IntStream.rangeClosed(1,4).mapToObj(id -> delivery(id,day,"09:00","17:00")).toList();
        var result=DeliveryWindowAssessor.assess(plan(inputs,
                new CalculatedPlan.DriverRoute(1,List.of(stop(1,0),stop(2,null),stop(3,60))),
                new CalculatedPlan.DriverRoute(2,List.of(stop(4,60)))),day.atTime(9,0),Duration.ZERO);
        assertThat(result.routes().get(0).stops().get(0).status()).isEqualTo(DeliveryWindowAssessor.Status.WITHIN_WINDOW);
        for(var stop:result.routes().get(0).stops().subList(1,3)) {
            assertThat(stop.status()).isEqualTo(DeliveryWindowAssessor.Status.UNKNOWN);
            assertThat(stop.arrival()).isNull(); assertThat(stop.serviceStart()).isNull(); assertThat(stop.departure()).isNull();
            assertThat(stop.waiting()).isNull(); assertThat(stop.lateness()).isNull();
        }
        assertThat(result.routes().get(1).stops().get(0).arrival()).isEqualTo(day.atTime(9,1));
    }
    @Test void travelRollsOverMidnightAndOverdueDatesAreNotRebased() {
        var result=DeliveryWindowAssessor.assess(plan(List.of(delivery(1,day.plusDays(1),"00:00","01:00"),delivery(2,day,"09:00","17:00")),
                new CalculatedPlan.DriverRoute(1,List.of(stop(1,1200),stop(2,0)))),day.atTime(23,50),Duration.ZERO);
        assertThat(result.routes().get(0).stops().get(0).arrival()).isEqualTo(day.plusDays(1).atTime(0,10));
        assertThat(result.routes().get(0).stops().get(0).status()).isEqualTo(DeliveryWindowAssessor.Status.WITHIN_WINDOW);
        assertThat(result.routes().get(0).stops().get(1).lateness()).isEqualTo(Duration.ofHours(7).plusMinutes(10));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={-1,1})
    void rejectsNegativeOrFractionalServiceDuration(long nanos) {
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of()),day.atStartOfDay(),Duration.ofNanos(nanos)));
    }
    @Test void rejectsInvalidCoverageAndWindowsAndNegativeTravelEvenAfterUnknownLeg() {
        var input=delivery(1,day,"09:00","17:00");
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of(input)),day.atStartOfDay(),Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of(input),new CalculatedPlan.DriverRoute(1,List.of(stop(2,0)))),day.atStartOfDay(),Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of(input),new CalculatedPlan.DriverRoute(1,List.of(stop(1,0),stop(1,0)))),day.atStartOfDay(),Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of(delivery(1,day,"17:00","09:00")),new CalculatedPlan.DriverRoute(1,List.of(stop(1,0)))),day.atStartOfDay(),Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan(List.of(input,delivery(2,day,"09:00","17:00")),new CalculatedPlan.DriverRoute(1,List.of(stop(1,null),stop(2,-1)))),day.atStartOfDay(),Duration.ZERO));
    }
    @Test void rejectsTimelineOverflowAndReturnsImmutableResults() {
        var plan=plan(List.of(delivery(1,day,"09:00","17:00")),new CalculatedPlan.DriverRoute(1,List.of(stop(1,60))));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryWindowAssessor.assess(plan,LocalDateTime.MAX,Duration.ZERO));
        var result=DeliveryWindowAssessor.assess(plan,day.atTime(9,0),Duration.ZERO);
        assertThatThrownBy(() -> result.routes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.routes().get(0).stops().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void assessesRealPlannerOutputWithoutMoreProviderCallsOrAssignmentChanges() throws Exception {
        var provider=org.mockito.Mockito.mock(MapsProvider.class);
        org.mockito.Mockito.when(provider.distance(org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> { ((PlanningGuard.Budget)call.getArgument(4)).countProviderCall(); return new DistanceResult(1,"1 minute",60); });
        var warehouse=new PlanningSnapshot.WarehouseInput(1,"warehouse","address",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO);
        var operator=new PlanningSnapshot.OperatorInput(1,"operator","phone","email","van",1.0,com.mmu.schedulingapp.model.Operator.AvailabilityStatus.AVAILABLE,warehouse);
        var recipient=new PlanningSnapshot.RecipientInput(1,"first","last","address","phone",java.math.BigDecimal.ONE,java.math.BigDecimal.ONE,
                com.mmu.schedulingapp.model.Recipient.PriorityLevel.MEDIUM);
        var input=new PlanningSnapshot.DeliveryInput(1,recipient,null,day,LocalTime.of(9,0),LocalTime.of(10,0),
                Delivery.PriorityLevel.MEDIUM,Delivery.DeliveryStatus.PENDING,null);
        var snapshot=new PlanningSnapshot(day,day.atTime(8,0),List.of(operator),List.of(input));
        try(var budget=new PlanningGuard.Budget(new MapsLimits(),() -> 0,() -> {})) {
            var calculated=new RoutePlanner(provider).calculate(snapshot,budget);
            var originalRoutes=calculated.routes();
            var early=DeliveryWindowAssessor.assess(calculated,day.atTime(9,0),Duration.ofMinutes(10));
            var late=DeliveryWindowAssessor.assess(calculated,day.atTime(11,0),Duration.ofMinutes(10));
            assertThat(early.routes().get(0).stops().get(0).status()).isEqualTo(DeliveryWindowAssessor.Status.WITHIN_WINDOW);
            assertThat(late.routes().get(0).stops().get(0).lateness()).isEqualTo(Duration.ofHours(1).plusMinutes(1));
            assertThat(calculated.routes()).isSameAs(originalRoutes);
            assertThat(calculated.snapshot()).isSameAs(snapshot);
        }
        org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).distance(org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.anyDouble(),org.mockito.ArgumentMatchers.any());
    }

}
