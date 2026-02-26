package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.operator.OperatorRequestDTO;
import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import com.mmu.schedulingapp.mapper.OperatorMapper;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Warehouse;
import com.mmu.schedulingapp.repository.OperatorRepository;
import com.mmu.schedulingapp.repository.WarehouseRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class OperatorService {

    private final OperatorRepository operatorRepository;
    private final WarehouseRepository warehouseRepository;

    public OperatorService(OperatorRepository operatorRepository, WarehouseRepository warehouseRepository) {
        this.operatorRepository = operatorRepository;
        this.warehouseRepository = warehouseRepository;
    }

    //    @Transactional(readOnly = true)
    public List<OperatorResponseDTO> getAllDriverDTOs() {
        return operatorRepository.findAll()
                .stream()
                .map(OperatorMapper::toResponseDTO)
                .toList();
    }

    public Optional<OperatorResponseDTO> getDriver(int id) {
        return operatorRepository.findById(id).map(OperatorMapper::toResponseDTO);
    }


    @Transactional
    public OperatorResponseDTO createDriver(OperatorRequestDTO dto) {
        Warehouse warehouse = null;
        if (dto.getWarehouseName() != null) {
            warehouse = warehouseRepository.findByName(dto.getWarehouseName())
                    .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + dto.getWarehouseName()));
        }

        Operator operator = OperatorMapper.toEntity(dto, warehouse);
        operatorRepository.save(operator);
        return OperatorMapper.toResponseDTO(operator);
    }



    @Transactional
    public Optional<OperatorResponseDTO> updateDriver(int id, OperatorRequestDTO dto) {
        return operatorRepository.findById(id).map(existingDriver -> {

            Warehouse warehouse = null;
            if (dto.getWarehouseName() != null) {
                warehouse = warehouseRepository.findByName(dto.getWarehouseName())
                        .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + dto.getWarehouseName()));
            }
            // Apply changes
            existingDriver.setName(dto.getName());
            existingDriver.setPhoneNumber(dto.getPhoneNumber());
            existingDriver.setEmail(dto.getEmail());
            existingDriver.setVehicleType(dto.getVehicleType());
            existingDriver.setMaxCapacity(dto.getMaxCapacity());
            existingDriver.setAvailabilityStatus(dto.getAvailabilityStatus());
            existingDriver.setWarehouse(warehouse);
            Operator saved = operatorRepository.save(existingDriver);
            return OperatorMapper.toResponseDTO(saved);
        });
    }

    @Transactional
    public Optional<OperatorResponseDTO> patchDriver(int id, OperatorRequestDTO dto) {
        return operatorRepository.findById(id).map(existingDriver -> {

            // Required fields cannot be set to null
            if (dto.getName() != null) {
                if (dto.getName().isBlank()) {
                    throw new IllegalArgumentException("Name cannot be empty");
                }
                existingDriver.setName(dto.getName());
            }

            if (dto.getPhoneNumber() != null) {
                if (dto.getPhoneNumber().isBlank()) {
                    throw new IllegalArgumentException("Phone number cannot be empty");
                }
                existingDriver.setPhoneNumber(dto.getPhoneNumber());
            }

            if (dto.getEmail() != null) {
                if (dto.getEmail().isBlank()) {
                    throw new IllegalArgumentException("Email cannot be empty");
                }
                existingDriver.setEmail(dto.getEmail());
            }

            // Optional fields
            if (dto.getVehicleType() != null) {
                existingDriver.setVehicleType(dto.getVehicleType());
            }

            if (dto.getMaxCapacity() != null) {
                existingDriver.setMaxCapacity(dto.getMaxCapacity());
            }

            if (dto.getAvailabilityStatus() != null) {
                existingDriver.setAvailabilityStatus(dto.getAvailabilityStatus());
            }

            if (dto.getWarehouseName() != null) {
                Warehouse warehouse = warehouseRepository.findByName(dto.getWarehouseName())
                        .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + dto.getWarehouseName()));
                existingDriver.setWarehouse(warehouse);
            }

            Operator saved = operatorRepository.save(existingDriver);
            return OperatorMapper.toResponseDTO(saved);
        });
    }


    @Transactional
    public Optional<OperatorResponseDTO> deleteDriver(int id){
        return operatorRepository.findById(id).map(driver -> {
            operatorRepository.delete(driver);
            return OperatorMapper.toResponseDTO(driver);
        });

    }


}
