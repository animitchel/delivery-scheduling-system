package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Value-only input: no entities, proxies or persistence resources escape the read transaction. */
public record PlanningSnapshot(LocalDate requestedDate, LocalDateTime scoringTime,
                               List<OperatorInput> operators, List<DeliveryInput> deliveries) {
    public PlanningSnapshot { operators=List.copyOf(operators); deliveries=List.copyOf(deliveries); }
    public record WarehouseInput(Integer id, String name, String address, BigDecimal latitude, BigDecimal longitude) {
        public static WarehouseInput from(Warehouse w) {
            return w == null ? null : new WarehouseInput(w.getWarehouseId(),w.getName(),w.getAddress(),w.getLatitude(),w.getLongitude());
        }
    }
    public record OperatorInput(Integer id, String name, String phone, String email, String vehicleType,
                                Double capacity, Operator.AvailabilityStatus availability, WarehouseInput warehouse) {
        public static OperatorInput from(Operator o) {
            return new OperatorInput(o.getOperatorId(),o.getName(),o.getPhoneNumber(),o.getEmail(),o.getVehicleType(),
                    o.getMaxCapacity(),o.getAvailabilityStatus(),WarehouseInput.from(o.getWarehouse()));
        }
    }
    public record RecipientInput(Integer id, String firstName, String lastName, String address, String contact,
                                 BigDecimal latitude, BigDecimal longitude, Recipient.PriorityLevel priority) {
        public static RecipientInput from(Recipient p) {
            return new RecipientInput(p.getRecipientId(),p.getFirstName(),p.getLastName(),p.getAddress(),p.getContactNumber(),
                    p.getLatitude(),p.getLongitude(),p.getPriorityLevel());
        }
    }
    public record DeliveryInput(Integer id, RecipientInput recipient, Integer operatorId, LocalDate scheduledDate,
                                LocalTime startWindow, LocalTime endWindow, Delivery.PriorityLevel priority,
                                Delivery.DeliveryStatus status, String instructions) {
        public static DeliveryInput from(Delivery d) {
            return new DeliveryInput(d.getDeliveryId(),RecipientInput.from(d.getRecipientId()),
                    d.getOperatorId()==null ? null : d.getOperatorId().getOperatorId(),d.getScheduledDate(),
                    d.getStartTimeWindow(),d.getEndTimeWindow(),d.getPriorityLevel(),d.getDeliveryStatus(),d.getSpecialInstructions());
        }
    }
}
