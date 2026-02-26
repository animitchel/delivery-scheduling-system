package com.mmu.schedulingapp.dto.warehouse;

import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseResponseDTO {

    private Integer warehouseId;
    private String name;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;

    private List<OperatorResponseDTO> assignedDrivers;
}
