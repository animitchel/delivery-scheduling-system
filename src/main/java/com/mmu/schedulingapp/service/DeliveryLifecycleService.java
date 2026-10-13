package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;
import com.mmu.schedulingapp.repository.OperatorRepository;
import com.mmu.schedulingapp.repository.RouteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Called within the caller's READ_COMMITTED write transaction. Operator locks serialize
 * final-stop checks across different deliveries; row locks alone cannot do that. */
@Service
public class DeliveryLifecycleService {
    private final OperatorRepository operators;
    private final RouteRepository routes;

    public DeliveryLifecycleService(OperatorRepository operators, RouteRepository routes) {
        this.operators = operators;
        this.routes = routes;
    }

    public java.util.List<Integer> lockOperators(Integer deliveryId) {
        // Stable order also handles contradictory legacy memberships without deadlocking.
        var ids = routes.findOperatorIdsForDelivery(deliveryId);
        ids.forEach(id -> operators.lockById(id));
        return ids;
    }

    public void checkAssignmentUnchanged(Integer deliveryId, java.util.List<Integer> lockedIds) {
        if (!lockedIds.equals(routes.findOperatorIdsForDelivery(deliveryId))) {
            throw conflict("Assignment changed during this request. Reload before trying again.");
        }
    }

    public void finalizeRoutes(Integer deliveryId) {
        for (Route route : routes.findContainingDelivery(deliveryId)) {
            if (route.getRouteStatus() != Route.RouteStatus.IN_PROGRESS || route.getDeliveries().isEmpty()) continue;
            boolean terminal = route.getDeliveries().stream().allMatch(d ->
                    d.getOperatorId() != null && d.getOperatorId().getOperatorId().equals(route.getOperator().getOperatorId())
                    && (d.getDeliveryStatus() == Delivery.DeliveryStatus.COMPLETED
                        || d.getDeliveryStatus() == Delivery.DeliveryStatus.CANCELLED));
            if (!terminal) continue;
            route.setRouteStatus(Route.RouteStatus.COMPLETED);
            routes.saveAndFlush(route);
            Operator operator = route.getOperator();
            if (!routes.existsByOperatorAndRouteStatusNot(operator, Route.RouteStatus.COMPLETED)
                    && operator.getAvailabilityStatus() == Operator.AvailabilityStatus.ON_DELIVERY) {
                operator.setAvailabilityStatus(Operator.AvailabilityStatus.AVAILABLE);
                operators.save(operator);
            }
        }
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
