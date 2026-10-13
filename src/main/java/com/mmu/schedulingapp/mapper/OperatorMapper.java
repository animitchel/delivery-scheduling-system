package com.mmu.schedulingapp.mapper;

import com.mmu.schedulingapp.dto.operator.OperatorRequestDTO;
import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Warehouse;

public class OperatorMapper {

    public static Operator toEntity(OperatorRequestDTO dto, Warehouse warehouse) {
        return Operator.builder()
                .name(dto.getName())
                .phoneNumber(dto.getPhoneNumber())
                .email(dto.getEmail())
                .vehicleType(dto.getVehicleType())
                .availabilityStatus(Operator.AvailabilityStatus.AVAILABLE)
                .maxCapacity(dto.getMaxCapacity())
                .warehouse(warehouse)
                .build();
    }

    public static OperatorResponseDTO toResponseDTO(Operator operator) {
        return OperatorResponseDTO.builder()
                .operatorId(operator.getOperatorId())
                .name(operator.getName())
                .phoneNumber(operator.getPhoneNumber())
                .email(operator.getEmail())
                .vehicleType(operator.getVehicleType())
                .availabilityStatus(operator.getAvailabilityStatus())
                .maxCapacity(operator.getMaxCapacity())
                .warehouseId(operator.getWarehouse() != null ? operator.getWarehouse().getWarehouseId() : null)
                .build();
    }

}
