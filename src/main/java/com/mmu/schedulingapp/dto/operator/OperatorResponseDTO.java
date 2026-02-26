package com.mmu.schedulingapp.dto.operator;

import com.mmu.schedulingapp.model.Operator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorResponseDTO {
    private Integer operatorId;
    private String name;
    private String phoneNumber;
    private String email;
    private String vehicleType;
    private Double maxCapacity;
    private Operator.AvailabilityStatus availabilityStatus;
    private Integer warehouseId;
}
