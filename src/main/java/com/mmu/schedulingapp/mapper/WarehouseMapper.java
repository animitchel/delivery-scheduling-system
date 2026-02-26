package com.mmu.schedulingapp.mapper;

import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import com.mmu.schedulingapp.dto.warehouse.WarehouseRequestDTO;
import com.mmu.schedulingapp.dto.warehouse.WarehouseResponseDTO;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Warehouse;

import java.util.List;

public class WarehouseMapper {

    public static Warehouse toEntity(WarehouseRequestDTO dto) {
        return Warehouse.builder()
                .name(dto.getName())
                .address(dto.getAddress())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .build();
    }

    public static WarehouseResponseDTO toResponseDTO(Warehouse warehouse) {
        return WarehouseResponseDTO.builder()
                .warehouseId(warehouse.getWarehouseId())
                .name(warehouse.getName())
                .address(warehouse.getAddress())
                .latitude(warehouse.getLatitude())
                .longitude(warehouse.getLongitude())
                .assignedDrivers(mapDrivers(warehouse.getAssignedOperators()))
                .build();
    }

    private static List<OperatorResponseDTO> mapDrivers(List<Operator> operators) {
        if (operators == null) return List.of();
        return operators.stream()
                .map(OperatorMapper::toResponseDTO)
                .toList();
    }
}
