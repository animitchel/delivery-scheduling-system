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

    public DriverAssignmentService(RouteRepository routes, DeliveryRepository deliveries) {
        this.routes = routes; this.deliveries = deliveries;
    }

    public List<RouteResponseDTO> routes(Long accountId) {
        return routes.findOwnedRoutes(accountId).stream().map(RouteMapper::toResponseDTO).toList();
    }

    public RouteResponseDTO route(Long accountId, Integer routeId) {
        return routes.findOwnedRoute(accountId, routeId).map(RouteMapper::toResponseDTO)
                .orElseThrow(DriverAssignmentService::notFound);
    }

    public DeliveryResponseDTO delivery(Long accountId, Integer deliveryId) {
        return deliveries.findOwnedDelivery(accountId, deliveryId).map(DeliveryMapper::toResponseDTO)
                .orElseThrow(DriverAssignmentService::notFound);
    }

    public record RecipientDetails(String firstName, String lastName, String address, String contactNumber) { }

    public RecipientDetails recipient(Long accountId, Integer deliveryId) {
        var delivery = deliveries.findOwnedDelivery(accountId, deliveryId).orElseThrow(DriverAssignmentService::notFound);
        var recipient = delivery.getRecipientId();
        return new RecipientDetails(recipient.getFirstName(), recipient.getLastName(), recipient.getAddress(), recipient.getContactNumber());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryResponseDTO status(Long accountId, Integer deliveryId, String status) {
        if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported driver status.");
        }
        // One database statement uses current persisted account/assignment and source status.
        // Never save an entity that could overwrite a concurrent administrator assignment change.
        int updated = deliveries.updateOwnedDeliveryStatus(accountId, deliveryId, status);
        var delivery = deliveries.findOwnedDelivery(accountId, deliveryId).orElseThrow(DriverAssignmentService::notFound);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery is no longer in progress.");
        return DeliveryMapper.toResponseDTO(delivery);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found.");
    }
}
