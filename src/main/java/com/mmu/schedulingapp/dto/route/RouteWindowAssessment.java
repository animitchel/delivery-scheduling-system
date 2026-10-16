package com.mmu.schedulingapp.dto.route;

import com.mmu.schedulingapp.model.Route;
import com.mmu.schedulingapp.service.autorouting.DeliveryWindowAssessor;
import java.time.*;
import java.util.List;

public record RouteWindowAssessment(Integer routeId,Integer operatorId,Route.RouteStatus routeStatus,
        boolean hypothetical,String estimateSource,String windowPolicy,LocalDateTime departure,
        long serviceDurationSeconds,List<Stop> stops) {
    public RouteWindowAssessment { stops=List.copyOf(stops); }
    public record Stop(Integer deliveryId,int position,LocalDateTime windowStart,LocalDateTime windowEnd,
            DeliveryWindowAssessor.Status status,LocalDateTime arrival,LocalDateTime serviceStart,
            LocalDateTime departure,Long waitingSeconds,Long latenessSeconds) { }
    public record SavedStop(Integer deliveryId,LocalDate scheduledDate,LocalTime startWindow,
            LocalTime endWindow,Integer durationSeconds,Integer stopOrder) { }
}
