package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.delivery.DeliveryRequestDTO;
import com.mmu.schedulingapp.dto.delivery.DeliveryResponseDTO;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.service.DeliveryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scheduling-app/deliveries")
public class DeliveryApiController {

    private final DeliveryService deliveryService;

    public DeliveryApiController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryResponseDTO>> getAllDeliveries() {
        List<DeliveryResponseDTO> deliveries = deliveryService.getAllDeliveries();
        if (deliveries.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(deliveries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponseDTO> getDelivery(@PathVariable int id) {
        Optional<DeliveryResponseDTO> optional = deliveryService.getDelivery(id);
        return optional.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<DeliveryResponseDTO> createDelivery(@Valid @RequestBody DeliveryRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deliveryService.createDelivery(dto));
    }


    @PutMapping("/{id}")
    public ResponseEntity<?> updateDelivery(
            @PathVariable int id,
            @RequestBody @Valid DeliveryRequestDTO dto) {

        Optional<DeliveryResponseDTO> updated = deliveryService.updateDelivery(id, dto);

        if (updated.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Only PENDING deliveries can be updated or delivery not found.");
        }

        return ResponseEntity.ok(updated.get());
    }


    @PatchMapping("/{id}")
    public ResponseEntity<?> updateDeliveryStatus(
            @PathVariable int id,
            @RequestBody DeliveryRequestDTO request) {

        Delivery.DeliveryStatus newStatus = request.getDeliveryStatus();

        if (newStatus != Delivery.DeliveryStatus.COMPLETED &&
                newStatus != Delivery.DeliveryStatus.CANCELLED &&
                newStatus != Delivery.DeliveryStatus.IN_PROGRESS
        ) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Status can only be updated to IN_PROGRESS, COMPLETED or CANCELLED.");
        }

        Optional<DeliveryResponseDTO> result = deliveryService.updateDeliveryStatus(id, newStatus);

        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Only deliveries that are IN_PROGRESS can be updated to COMPLETED or CANCELLED.");
        }

        return ResponseEntity.ok(result.get());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DeliveryResponseDTO> deleteDelivery(@PathVariable int id) {
        return deliveryService.deleteDelivery(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
