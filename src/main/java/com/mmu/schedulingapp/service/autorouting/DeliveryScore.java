package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.model.Delivery;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Component
public class DeliveryScore {

    private Delivery delivery;

    private Integer recipientPriorityLevelScore;
    private Integer deliveryPriorityLevelScore;

    private LocalTime deliveryEndTimeWindow;

    // precomputed distance (from RouteBuilder)
    private double distanceKm;

    public double getTotalPriorityScore() {
        int priorityImportance = 3;
        int distanceImportance = 2;

        int priorityLevelScore = priorityImportance * (recipientPriorityLevelScore + deliveryPriorityLevelScore);
        double distanceScore = distanceImportance * distanceKm;
        double timeUrgency = getTimeUrgency();

        System.out.println(
                "deliveryId:" + this.delivery.getDeliveryId()
                        + ", priorityLevelScore: " + priorityLevelScore
                        + ", distanceScore: " + distanceScore
                        + ", timeUrgency: " + timeUrgency);

        // Lower total score = better candidate
        return priorityLevelScore + distanceScore + timeUrgency;
    }

    public double getTimeUrgency() {
        int timeUrgencyImportance = 1;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = LocalDateTime.of(delivery.getScheduledDate(), deliveryEndTimeWindow);
        Duration duration = Duration.between(now, deadline);

        return timeUrgencyImportance * getTimeLeftOrOverdue(duration);
    }

    private double getTimeLeftOrOverdue(Duration duration) {
        int urgencyScore = 3; // amplify penalty if overdue
        double hoursUntilDeadline = duration.toMinutes() / 60.0;

        if (hoursUntilDeadline < 0) {
            return hoursUntilDeadline * urgencyScore; // overdue penalty
        }
        return hoursUntilDeadline;
    }
}
