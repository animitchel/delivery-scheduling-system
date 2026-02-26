package com.mmu.schedulingapp.mapper;

import com.mmu.schedulingapp.dto.delivery.DeliveryRequestDTO;
import com.mmu.schedulingapp.dto.delivery.DeliveryResponseDTO;
import com.mmu.schedulingapp.model.*;

public class DeliveryMapper {

    public static Delivery toEntity(DeliveryRequestDTO dto, Recipient client, Operator operator) {
        return Delivery.builder()
                .recipientId(client)
                .operatorId(operator)
                .scheduledDate(dto.getScheduledDate())
                .startTimeWindow(dto.getStartTimeWindow())
                .endTimeWindow(dto.getEndTimeWindow())
                .priorityLevel(dto.getPriorityLevel())
                .deliveryStatus(Delivery.DeliveryStatus.PENDING)
                .specialInstructions(dto.getSpecialInstructions())
                .build();
    }

    public static DeliveryResponseDTO toResponseDTO(Delivery delivery) {
        return DeliveryResponseDTO.builder()
                .deliveryId(delivery.getDeliveryId())
                .recipientId(delivery.getRecipientId().getRecipientId())
                .clientName(delivery.getRecipientId().getFirstName() + " " + delivery.getRecipientId().getLastName())
                .operatorId(delivery.getOperatorId() != null ? delivery.getOperatorId().getOperatorId() : null)
                .operatorName(delivery.getOperatorId() != null ? delivery.getOperatorId().getName() : null)
                .scheduledDate(delivery.getScheduledDate())
                .startTimeWindow(delivery.getStartTimeWindow())
                .endTimeWindow(delivery.getEndTimeWindow())
                .priorityLevel(delivery.getPriorityLevel())
                .deliveryStatus(delivery.getDeliveryStatus())
                .specialInstructions(delivery.getSpecialInstructions())
                .build();
    }
}
