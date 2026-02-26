package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.mapper.WarehouseMapper;
import com.mmu.schedulingapp.dto.warehouse.*;
import com.mmu.schedulingapp.model.Warehouse;
import com.mmu.schedulingapp.repository.WarehouseRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    public List<WarehouseResponseDTO> getAllWarehouses() {
        return warehouseRepository.findAll()
                .stream()
                .map(WarehouseMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    public Optional<WarehouseResponseDTO> getWarehouse(int id) {
        return warehouseRepository.findById(id)
                .map(WarehouseMapper::toResponseDTO);
    }

    @Transactional
    public WarehouseResponseDTO createWarehouse(WarehouseRequestDTO dto) {
        Warehouse warehouse = WarehouseMapper.toEntity(dto);
        Warehouse saved = warehouseRepository.save(warehouse);
        return WarehouseMapper.toResponseDTO(saved);
    }

    @Transactional
    public Optional<WarehouseResponseDTO> updateWarehouse(int id, WarehouseRequestDTO dto) {
        return warehouseRepository.findById(id).map(existingWarehouse -> {
            existingWarehouse.setName(dto.getName());
            existingWarehouse.setAddress(dto.getAddress());
            existingWarehouse.setLatitude(dto.getLatitude());
            existingWarehouse.setLongitude(dto.getLongitude());

            Warehouse updated = warehouseRepository.save(existingWarehouse);
            return WarehouseMapper.toResponseDTO(updated);
        });
    }

    public Optional<WarehouseResponseDTO> deleteWarehouse(int id) {
        return warehouseRepository.findById(id).map(warehouse -> {
            warehouseRepository.delete(warehouse);
            return WarehouseMapper.toResponseDTO(warehouse);
        });
    }
}
