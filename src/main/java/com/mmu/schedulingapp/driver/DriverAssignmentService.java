package com.mmu.schedulingapp.driver;

import com.mmu.schedulingapp.dto.delivery.DeliveryResponseDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.mapper.DeliveryMapper;
import com.mmu.schedulingapp.mapper.RouteMapper;
import com.mmu.schedulingapp.repository.DeliveryRepository;
import com.mmu.schedulingapp.repository.RouteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DriverAssignmentService {
    private final RouteRepository routes;
    private final DeliveryRepository deliveries;
    private final com.mmu.schedulingapp.service.DeliveryLifecycleService lifecycle;

    public DriverAssignmentService(RouteRepository routes, DeliveryRepository deliveries,
                                   com.mmu.schedulingapp.service.DeliveryLifecycleService lifecycle) {
        this.routes = routes; this.deliveries = deliveries; this.lifecycle = lifecycle;
    }

    public List<RouteResponseDTO> routes(Long accountId, long authRevision) {
        return routes.findOwnedRoutes(accountId, authRevision).stream().map(RouteMapper::toResponseDTO).toList();
    }

    public RouteResponseDTO route(Long accountId, long authRevision, Integer routeId) {
        return routes.findOwnedRoute(accountId, authRevision, routeId).map(RouteMapper::toResponseDTO)
                .orElseThrow(DriverAssignmentService::notFound);
    }

    public DeliveryResponseDTO delivery(Long accountId, long authRevision, Integer deliveryId) {
        return deliveries.findOwnedDelivery(accountId, authRevision, deliveryId).map(DeliveryMapper::toResponseDTO)
                .orElseThrow(DriverAssignmentService::notFound);
    }

    public record RecipientDetails(String firstName, String lastName, String address, String contactNumber) { }

    public RecipientDetails recipient(Long accountId, long authRevision, Integer deliveryId) {
        var delivery = deliveries.findOwnedDelivery(accountId, authRevision, deliveryId).orElseThrow(DriverAssignmentService::notFound);
        var recipient = delivery.getRecipientId();
        return new RecipientDetails(recipient.getFirstName(), recipient.getLastName(), recipient.getAddress(), recipient.getContactNumber());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryResponseDTO status(Long accountId, long authRevision, Integer deliveryId, String status) {
        if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported driver status.");
        }
        // One database statement uses current persisted account/assignment and source status.
        // Never save an entity that could overwrite a concurrent administrator assignment change.
        var lockedIds = lifecycle.lockOperators(deliveryId);
        if (lockedIds.isEmpty()) throw notFound();
        int updated = deliveries.updateOwnedDeliveryStatus(accountId, authRevision, deliveryId, status);
        var delivery = deliveries.findOwnedDelivery(accountId, authRevision, deliveryId).orElseThrow(DriverAssignmentService::notFound);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is no longer in progress.");
        lifecycle.finalizeRoutes(deliveryId);
        return DeliveryMapper.toResponseDTO(delivery);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
    }
}
