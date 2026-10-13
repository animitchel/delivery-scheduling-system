package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.delivery.*;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.service.DeliveryPreparationService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/admin/deliveries")
public class DeliveryPreparationController {
    private final DeliveryPreparationService service;
    public DeliveryPreparationController(DeliveryPreparationService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<DeliveryDirectory> directory(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String scheduledDate,
            @RequestParam(required = false) Delivery.DeliveryStatus status, @RequestParam(required = false) Integer recipientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(page, size,
                scheduledDate == null ? null : DeliveryCreateRequest.date(scheduledDate), status, recipientId));
    }
    @GetMapping("/{id}")
    public ResponseEntity<DeliveryResponseDTO> read(@PathVariable int id) {
        return service.read(id).map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PostMapping
    public ResponseEntity<DeliveryResponseDTO> create(@Valid @RequestBody DeliveryCreateRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/admin/deliveries/" + result.getDeliveryId()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
}
