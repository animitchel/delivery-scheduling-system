package com.mmu.schedulingapp.service.autorouting;

import java.util.List;

public record CalculatedPlan(PlanningSnapshot snapshot, List<DriverRoute> routes) {
    public CalculatedPlan { routes=List.copyOf(routes); }
    public record DriverRoute(Integer operatorId, List<Stop> stops) {
        public DriverRoute { stops=List.copyOf(stops); }
    }
    public record Stop(Integer deliveryId, double distanceKm, String durationText, Integer durationSeconds) { }
}
