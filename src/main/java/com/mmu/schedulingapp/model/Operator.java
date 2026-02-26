package com.mmu.schedulingapp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "operators")
@Data // this is from Lombok, it generates getters/setters/toString/etc.
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer operatorId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "vehicle_type", length = 50)
    private String vehicleType;

    @Column(name = "max_capacity")
    private Double maxCapacity;

    @ManyToOne
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AvailabilityStatus availabilityStatus = AvailabilityStatus.AVAILABLE;

    // Enum definition
    public enum AvailabilityStatus {
        AVAILABLE,
        UNAVAILABLE,
        ON_DELIVERY,
        OFF_DUTY
    }


}
