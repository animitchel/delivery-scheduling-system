package com.mmu.schedulingapp.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "routes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer routeId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @Column(nullable = false)
    private LocalDate scheduledDate;


    @OneToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "route_deliveries",
        joinColumns = @JoinColumn(name = "route_id"),
        inverseJoinColumns = @JoinColumn(name = "delivery_id")
    )
    @OrderColumn(name = "delivery_order")
    private List<Delivery> deliveries;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus routeStatus = RouteStatus.PLANNED;

    public enum RouteStatus {
        PLANNED,
        IN_PROGRESS,
        COMPLETED
    }
}
