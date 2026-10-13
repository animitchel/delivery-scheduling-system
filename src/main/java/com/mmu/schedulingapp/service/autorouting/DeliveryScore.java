package com.mmu.schedulingapp.service.autorouting;

import java.time.Duration;
import java.time.LocalDateTime;

/** The existing heuristic evaluated at one captured time, with no entity mutation or logging. */
public record DeliveryScore(double total, double urgency) {
    public static DeliveryScore calculate(PlanningSnapshot.DeliveryInput delivery, double distanceKm, LocalDateTime now) {
        long minutes=Duration.between(now,LocalDateTime.of(delivery.scheduledDate(),delivery.endWindow())).toMinutes();
        double hours=minutes/60.0;
        double urgency=hours < 0 ? hours*3 : hours;
        double priority=3*(rank(delivery.recipient().priority().name())+rank(delivery.priority().name()));
        return new DeliveryScore(priority+2*distanceKm+urgency,urgency);
    }
    private static int rank(String priority) { return switch(priority) { case "HIGH" -> 1; case "MEDIUM" -> 2; case "LOW" -> 3; default -> 0; }; }
}
