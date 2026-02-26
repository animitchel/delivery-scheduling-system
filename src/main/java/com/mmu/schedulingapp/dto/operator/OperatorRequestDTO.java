package com.mmu.schedulingapp.dto.operator;

import com.mmu.schedulingapp.model.Operator;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorRequestDTO {
    private Integer operatorId;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Phone number should be valid")
    private String phoneNumber;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    private String vehicleType;

    @NotNull(message = "Max capacity is required")
    @PositiveOrZero(message = "Max capacity must be positive")
    private Double maxCapacity;

    @NotBlank(message = "Warehouse name is required")
    private String warehouseName;

    @NotBlank
    private Operator.AvailabilityStatus availabilityStatus;

}
