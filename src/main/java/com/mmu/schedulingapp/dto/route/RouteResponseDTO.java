package com.mmu.schedulingapp.dto.route;


import com.mmu.schedulingapp.model.Route;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResponseDTO {

    private Integer routeId;

    private Integer operatorId;
    private String operatorName;

    private LocalDate scheduledDate;

    private List<DeliverySummary> deliveries;

    private Route.RouteStatus routeStatus;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeliverySummary {
        private Integer deliveryId;
        private Integer recipientId;
        private String clientName;
        private String status;

        private Double fromPrevDistanceKm;
        private String fromPrevDurationText;
        private Integer fromPrevDurationSeconds;
    }
}
