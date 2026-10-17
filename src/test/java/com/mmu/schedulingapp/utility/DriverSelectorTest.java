package com.mmu.schedulingapp.utility;

import com.mmu.schedulingapp.service.autorouting.*;
import com.mmu.schedulingapp.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DriverSelectorTest {
    PlanningSnapshot.OperatorInput operator(int id,double longitude) {
        return new PlanningSnapshot.OperatorInput(id,"driver","phone","email","van",1.0,Operator.AvailabilityStatus.AVAILABLE,
                new PlanningSnapshot.WarehouseInput(id,"depot","address",BigDecimal.ZERO,BigDecimal.valueOf(longitude)));
    }
    PlanningSnapshot.DeliveryInput delivery(int id,double longitude) {
        return new PlanningSnapshot.DeliveryInput(id,new PlanningSnapshot.RecipientInput(id,"first","last","address","phone",BigDecimal.ZERO,
                BigDecimal.valueOf(longitude),Recipient.PriorityLevel.MEDIUM),null,LocalDate.of(2026,1,1),LocalTime.of(9,0),LocalTime.of(17,0),Delivery.PriorityLevel.MEDIUM,Delivery.DeliveryStatus.PENDING,null);
    }
    List<PlanningSnapshot.OperatorInput> select(List<PlanningSnapshot.OperatorInput> operators,List<PlanningSnapshot.DeliveryInput> deliveries) {
        try(var budget=new PlanningGuard.Budget(new MapsLimits(),() -> 0,() -> {})) { return DriverSelector.select(operators,deliveries,budget); }
    }
    @Test void mixedLocationsChooseDriversForDifferentDeliveriesRatherThanLowestIds() {
        var result=select(List.of(operator(1,0),operator(2,10),operator(3,20)),List.of(delivery(1,.1),delivery(2,19.9)));
        assertThat(result).extracting(PlanningSnapshot.OperatorInput::id).containsExactly(1,3);
    }
    @Test void equalDistanceSelectionIsStableAcrossInputOrderAndNeverSelectsADriverTwice() {
        var operators=List.of(operator(3,0),operator(1,0),operator(2,0));
        var deliveries=List.of(delivery(2,1),delivery(1,1));
        var result=select(operators,deliveries);
        assertThat(result).extracting(PlanningSnapshot.OperatorInput::id).containsExactly(1,2);
        assertThat(select(operators.reversed(),deliveries.reversed())).isEqualTo(result);
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void retainsEveryCandidateWhenThereAreAtLeastAsManyDeliveries() {
        var candidates=List.of(operator(2,20),operator(1,0));
        assertThat(select(candidates,List.of(delivery(1,0),delivery(2,0),delivery(3,0))))
                .extracting(PlanningSnapshot.OperatorInput::id).containsExactly(1,2);
    }
    @Test void candidateMatchingIsCoveredByThePlanningDeadline() {
        var limits=new MapsLimits(); limits.setPlanningTimeoutMs(5); var ticks=new java.util.concurrent.atomic.AtomicLong();
        try(var budget=new PlanningGuard.Budget(limits,() -> ticks.getAndAdd(1_000_000),() -> {})) {
            assertThatThrownBy(() -> DriverSelector.select(List.of(operator(1,0),operator(2,10),operator(3,20)),
                    List.of(delivery(1,0),delivery(2,20)),budget)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(504));
        }
    }
}
