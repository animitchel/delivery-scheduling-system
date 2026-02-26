package com.mmu.schedulingapp.dto.delivery;

import com.mmu.schedulingapp.model.Delivery;
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
public class DeliveryResponseDTO {

    private Integer deliveryId;
    private Integer recipientId;
    private String clientName;
    private Integer operatorId;
    private String operatorName;
    private LocalDate scheduledDate;
    private LocalTime startTimeWindow;
    private LocalTime endTimeWindow;
    private Delivery.PriorityLevel priorityLevel;
    private Delivery.DeliveryStatus deliveryStatus;
    private String specialInstructions;
}
