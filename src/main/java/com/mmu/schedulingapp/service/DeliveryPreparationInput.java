package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.delivery.*;
import com.mmu.schedulingapp.model.Delivery;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;

/** Shared validation for strict creation and legacy create/edit callers. */
public record DeliveryPreparationInput(Integer recipientId, LocalDate date, LocalTime start,
        LocalTime end, Delivery.PriorityLevel priority, String instructions) {
    public static DeliveryPreparationInput from(DeliveryCreateRequest request) {
        return validated(request.recipientId(), request.scheduledDate(), request.startTimeWindow(),
                request.endTimeWindow(), request.priorityLevel(), request.specialInstructions());
    }
    public static DeliveryPreparationInput from(DeliveryRequestDTO request) {
        if (request.getOperatorId() != null && request.getOperatorId() < 1) throw invalid("Invalid operator reference.");
        return validated(request.getRecipientId(), request.getScheduledDate(), request.getStartTimeWindow(),
                request.getEndTimeWindow(), request.getPriorityLevel(), request.getSpecialInstructions());
    }
    private static DeliveryPreparationInput validated(Integer recipient, LocalDate date, LocalTime start,
            LocalTime end, Delivery.PriorityLevel priority, String instructions) {
        if (recipient == null || recipient < 1) throw invalid("A valid recipient is required.");
        // Match the MySQL DATE range; overdue work remains supported.
        if (date == null || date.getYear() < 1000 || date.getYear() > 9999) throw invalid("A valid scheduled date is required.");
        if (start == null || end == null || !start.isBefore(end) || start.getNano() != 0 || end.getNano() != 0)
            throw invalid("Delivery windows must use whole seconds and end after the start on the same day.");
        if (priority == null) throw invalid("Delivery priority is required.");
        if (instructions != null && instructions.length() > 255) throw invalid("Special instructions must be at most 255 characters.");
        String normalized = instructions == null ? null : instructions.strip();
        return new DeliveryPreparationInput(recipient, date, start, end, priority,
                normalized == null || normalized.isEmpty() ? null : normalized);
    }
    public void apply(Delivery delivery) {
        delivery.setScheduledDate(date); delivery.setStartTimeWindow(start); delivery.setEndTimeWindow(end);
        delivery.setPriorityLevel(priority); delivery.setSpecialInstructions(instructions);
    }
    public static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    @Override public String toString() { return "DeliveryPreparationInput[REDACTED]"; }
}
