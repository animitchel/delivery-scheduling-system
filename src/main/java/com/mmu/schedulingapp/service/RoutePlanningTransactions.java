package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.mapper.RouteMapper;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.autorouting.*;
import com.mmu.schedulingapp.utility.PlanningGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

/** Separate proxied read/write boundaries; provider calls are deliberately absent from this bean. */
@Service
public class RoutePlanningTransactions {
    private final OperatorRepository operators;
    private final DeliveryRepository deliveries;
    private final RouteRepository routes;
    private final EntityManager entities;
    private final Clock clock;
    public RoutePlanningTransactions(OperatorRepository operators,DeliveryRepository deliveries,RouteRepository routes,EntityManager entities,Clock clock) {
        this.operators=operators; this.deliveries=deliveries; this.routes=routes; this.entities=entities;
        this.clock=clock.withZone(ZoneId.systemDefault());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
    public PlanningSnapshot snapshot(LocalDate date,PlanningGuard.Budget budget) {
        entities.clear(); // Also isolate reads from any OSIV persistence context.
        var ids=deliveries.findPendingIds(date,PageRequest.of(0,budget.maxDeliveries()+1));
        if(ids.size()>budget.maxDeliveries()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Too many deliveries for one route-planning operation.");
        if(ids.isEmpty()) throw DeliveryLifecycleService.conflict("No unrouted pending deliveries for the requested date.");
        var deliveryInputs=ids.stream().map(id -> PlanningSnapshot.DeliveryInput.from(deliveries.findById(id).orElseThrow(RoutePlanningTransactions::stale))).toList();
        var candidateIds=operators.findPlanningIds(PageRequest.of(0,budget.maxOperatorCandidates()+1));
        if(candidateIds.size()>budget.maxOperatorCandidates()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Too many operator candidates for one route-planning operation.");
        if(candidateIds.isEmpty()) throw DeliveryLifecycleService.conflict("No available operators without active routes.");
        var candidates=candidateIds.stream().map(id -> {
            budget.check();
            return PlanningSnapshot.OperatorInput.from(operators.findById(id).orElseThrow(RoutePlanningTransactions::stale));
        }).filter(o -> o.warehouse()!=null && o.warehouse().latitude()!=null && o.warehouse().longitude()!=null).toList();
        if(candidates.isEmpty()) throw DeliveryLifecycleService.conflict("Selected operator needs warehouse coordinates before routing.");
        for(var d:deliveryInputs) {
            if(d.scheduledDate()==null || d.endWindow()==null || d.priority()==null || d.recipient().priority()==null
                    || d.recipient().latitude()==null || d.recipient().longitude()==null)
                throw DeliveryLifecycleService.conflict("Selected delivery needs complete scheduling and location data.");
        }
        var operatorInputs=DriverSelector.select(candidates,deliveryInputs,budget);
        budget.check();
        return new PlanningSnapshot(date,LocalDateTime.now(clock),operatorInputs,deliveryInputs);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED,propagation=Propagation.REQUIRES_NEW)
    public List<RouteResponseDTO> commit(CalculatedPlan plan,PlanningGuard.Budget budget) {
        entities.clear(); // A request-scoped EntityManager must not reuse snapshot-era entities.
        budget.check();
        validateCoverage(plan);
        Map<Integer,Operator> lockedOperators=new TreeMap<>();
        for(var input:plan.snapshot().operators().stream().sorted(Comparator.comparing(PlanningSnapshot.OperatorInput::id)).toList()) {
            lockedOperators.put(input.id(),operators.lockById(input.id()).orElseThrow(RoutePlanningTransactions::stale)); budget.check();
        }
        Map<Integer,Delivery> lockedDeliveries=new TreeMap<>();
        for(var input:plan.snapshot().deliveries().stream().sorted(Comparator.comparing(PlanningSnapshot.DeliveryInput::id)).toList()) {
            lockedDeliveries.put(input.id(),deliveries.lockById(input.id()).orElseThrow(RoutePlanningTransactions::stale)); budget.check();
        }
        // Eager related objects may have been read before waiting on their locks. Refresh under
        // WRITE locks so validation sees their latest committed fields, then keeps them stable.
        var warehouses=new TreeMap<Integer,Warehouse>();
        for(var o:lockedOperators.values()) if(o.getWarehouse()!=null) warehouses.put(o.getWarehouse().getWarehouseId(),o.getWarehouse());
        for(var w:warehouses.values()) { entities.refresh(w,LockModeType.PESSIMISTIC_WRITE); budget.check(); }
        var recipients=new TreeMap<Integer,Recipient>();
        for(var d:lockedDeliveries.values()) recipients.put(d.getRecipientId().getRecipientId(),d.getRecipientId());
        for(var p:recipients.values()) { entities.refresh(p,LockModeType.PESSIMISTIC_WRITE); budget.check(); }
        for(var input:plan.snapshot().operators()) {
            var actual=lockedOperators.get(input.id());
            if(!input.equals(PlanningSnapshot.OperatorInput.from(actual)) || actual.getAvailabilityStatus()!=Operator.AvailabilityStatus.AVAILABLE
                    || routes.existsByOperatorAndRouteStatusNot(actual,Route.RouteStatus.COMPLETED)) throw stale();
        }
        for(var input:plan.snapshot().deliveries()) {
            var actual=lockedDeliveries.get(input.id());
            if(!input.equals(PlanningSnapshot.DeliveryInput.from(actual)) || actual.getDeliveryStatus()!=Delivery.DeliveryStatus.PENDING
                    || !routes.findContainingDelivery(input.id()).isEmpty()) throw stale();
        }
        budget.check();
        List<RouteResponseDTO> responses=new ArrayList<>();
        for(var planned:plan.routes()) {
            var operator=lockedOperators.get(planned.operatorId());
            List<Delivery> members=new ArrayList<>();
            for(var stop:planned.stops()) {
                var d=lockedDeliveries.get(stop.deliveryId()); d.setOperatorId(operator); d.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS);
                d.setFromPrevDistanceKm(stop.distanceKm()); d.setFromPrevDurationText(stop.durationText()); d.setFromPrevDurationSeconds(stop.durationSeconds()); members.add(d);
            }
            operator.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY);
            var route=new Route(); route.setOperator(operator); route.setScheduledDate(plan.snapshot().requestedDate());
            route.setRouteStatus(Route.RouteStatus.IN_PROGRESS); route.setDeliveries(members);
            routes.saveAndFlush(route); responses.add(RouteMapper.toResponseDTO(route)); budget.check();
        }
        return responses; // Proxy commits before the admission slot is released; no post-commit deadline error.
    }
    private static void validateCoverage(CalculatedPlan plan) {
        Set<Integer> operators=new HashSet<>(), deliveries=new HashSet<>();
        for(var route:plan.routes()) {
            if(route.stops().isEmpty() || !operators.add(route.operatorId())) throw new IllegalStateException("Invalid calculated route coverage.");
            for(var stop:route.stops()) if(!deliveries.add(stop.deliveryId())) throw new IllegalStateException("Duplicate calculated delivery.");
        }
        var expectedOperators=new HashSet<>(plan.snapshot().operators().stream().map(PlanningSnapshot.OperatorInput::id).toList());
        var expectedDeliveries=new HashSet<>(plan.snapshot().deliveries().stream().map(PlanningSnapshot.DeliveryInput::id).toList());
        if(!operators.equals(expectedOperators) || !deliveries.equals(expectedDeliveries)) throw new IllegalStateException("Incomplete calculated route coverage.");
    }
    private static ResponseStatusException stale() {
        return DeliveryLifecycleService.conflict("Planning inputs changed. Reload deliveries and operators before requesting a new plan.");
    }
}
