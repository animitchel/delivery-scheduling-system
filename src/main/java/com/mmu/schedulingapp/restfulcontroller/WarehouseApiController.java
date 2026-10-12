package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.service.WarehouseService;
import com.mmu.schedulingapp.dto.warehouse.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scheduling-app/warehouses")
public class WarehouseApiController {

    private final WarehouseService warehouseService;

    public WarehouseApiController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    // Get all warehouses
    @GetMapping
    public ResponseEntity<List<WarehouseResponseDTO>> getAllWarehouses() {
        List<WarehouseResponseDTO> warehouses = warehouseService.getAllWarehouses();
        return ResponseEntity.ok(warehouses);
    }

    // Get a warehouse by ID
    @GetMapping("/{id}")
    public ResponseEntity<WarehouseResponseDTO> getWarehouse(@PathVariable int id) {
        Optional<WarehouseResponseDTO> warehouse = warehouseService.getWarehouse(id);
        return warehouse.map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build());
    }

    // Create a new warehouse
    @PostMapping
    public ResponseEntity<WarehouseResponseDTO> createWarehouse(@Valid @RequestBody WarehouseRequestDTO dto) {
        WarehouseResponseDTO saved = warehouseService.createWarehouse(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // Update a warehouse
    @PutMapping("/{id}")
    public ResponseEntity<WarehouseResponseDTO> updateWarehouse(
            @PathVariable int id,
            @RequestBody @Valid WarehouseRequestDTO dto) {
        return warehouseService.updateWarehouse(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Delete a warehouse
    @DeleteMapping("/{id}")
    public ResponseEntity<WarehouseResponseDTO> deleteWarehouse(@PathVariable int id) {
        return warehouseService.deleteWarehouse(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
