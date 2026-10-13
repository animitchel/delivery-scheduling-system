package com.mmu.schedulingapp.dto.delivery;

import com.mmu.schedulingapp.model.Delivery;
import java.time.*;
import java.util.List;

public record DeliveryDirectory(List<Item> deliveries, int page, int size, long totalElements, int totalPages) {
    public record Item(Integer deliveryId, Integer recipientId, String firstName, String lastName,
            Integer operatorId, LocalDate scheduledDate, LocalTime startTimeWindow, LocalTime endTimeWindow,
            Delivery.PriorityLevel priorityLevel, Delivery.DeliveryStatus deliveryStatus) { }
}
