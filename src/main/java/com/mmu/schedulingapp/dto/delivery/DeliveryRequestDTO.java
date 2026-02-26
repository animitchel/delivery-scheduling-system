package com.mmu.schedulingapp.dto.delivery;

import com.mmu.schedulingapp.model.Delivery;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryRequestDTO {

    @NotNull
    private Integer recipientId;

    private Integer operatorId;

    @NotNull(message = "Scheduled date is required")
    private LocalDate scheduledDate;

    @NotNull
    private LocalTime startTimeWindow;

    @NotNull
    private LocalTime endTimeWindow;

    @NotNull
    private Delivery.PriorityLevel priorityLevel;

    private String specialInstructions;

    private Delivery.DeliveryStatus deliveryStatus;;
}
