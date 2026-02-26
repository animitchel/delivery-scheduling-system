package com.mmu.schedulingapp.mapper;


import com.mmu.schedulingapp.dto.route.RouteRequestDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;

import java.util.List;
import java.util.stream.IntStream;

public class RouteMapper {

    public static Route toEntity(RouteRequestDTO dto, Operator operator, List<Delivery> deliveries) {
        return Route.builder()
                .operator(operator)
                .scheduledDate(dto.getScheduledDate())
                .deliveries(deliveries)
                .routeStatus(Route.RouteStatus.PLANNED)
                .build();
    }

    public static RouteResponseDTO toResponseDTO(Route route) {
        List<RouteResponseDTO.DeliverySummary> summaries = IntStream.range(0, route.getDeliveries().size())
                .mapToObj(i -> {
                    Delivery d = route.getDeliveries().get(i);

                    return RouteResponseDTO.DeliverySummary.builder()
                            .deliveryId(d.getDeliveryId())
                            .recipientId(d.getRecipientId() != null ? d.getRecipientId().getRecipientId() : null)
                            .clientName(d.getRecipientId().getFirstName() + " " + d.getRecipientId().getLastName())
                            .status(d.getDeliveryStatus().name())
                            // use precomputed hop info
                            .fromPrevDistanceKm(d.getFromPrevDistanceKm())
                            .fromPrevDurationText(d.getFromPrevDurationText())
                            .fromPrevDurationSeconds(d.getFromPrevDurationSeconds())
                            .build();
                })
                .toList();

        return RouteResponseDTO.builder()
                .routeId(route.getRouteId())
                .operatorId(route.getOperator().getOperatorId())
                .operatorName(route.getOperator().getName())
                .scheduledDate(route.getScheduledDate())
                .deliveries(summaries)
                .routeStatus(route.getRouteStatus())
                .build();
    }
}
