package com.mmu.schedulingapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "deliveries")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer deliveryId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipient_id")
    private Recipient recipientId;

    @ManyToOne
    @JoinColumn(name = "operator_id")
    private Operator operatorId; // Nullable initially

    private LocalDate scheduledDate;

    private LocalTime startTimeWindow;

    private LocalTime endTimeWindow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriorityLevel priorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    private String specialInstructions;

    public enum PriorityLevel {
        HIGH, MEDIUM, LOW
    }

    @Column(name = "from_prev_distance_km")
    private Double fromPrevDistanceKm;

    @Column(name = "from_prev_duration_text", length = 64)
    private String fromPrevDurationText;

    @Column(name = "from_prev_duration_seconds")
    private Integer fromPrevDurationSeconds;


    public enum DeliveryStatus {
        PENDING, IN_PROGRESS, COMPLETED, CANCELLED
    }
}
