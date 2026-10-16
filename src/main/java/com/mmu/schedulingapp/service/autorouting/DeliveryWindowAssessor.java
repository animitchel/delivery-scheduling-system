package com.mmu.schedulingapp.service.autorouting;

import java.time.*;
import java.util.*;

/** Pure, opt-in timeline assessment. Does not assign, persist, reorder or call providers. */
public final class DeliveryWindowAssessor {
    private DeliveryWindowAssessor() { }
    public enum Status { WITHIN_WINDOW, LATE, UNKNOWN }
    public record StopAssessment(Integer deliveryId,Status status,LocalDateTime arrival,
            LocalDateTime serviceStart,LocalDateTime departure,Duration waiting,Duration lateness) { }
    public record RouteAssessment(Integer operatorId,List<StopAssessment> stops) {
        public RouteAssessment { stops=List.copyOf(stops); }
    }
    public record Assessment(LocalDateTime departure,Duration serviceDuration,List<RouteAssessment> routes) {
        public Assessment { routes=List.copyOf(routes); }
    }

    /** Windows constrain service start, inclusive of closing time; service may finish later. */
    public static Assessment assess(CalculatedPlan plan,LocalDateTime departure,Duration serviceDuration) {
        Objects.requireNonNull(plan); Objects.requireNonNull(departure); Objects.requireNonNull(serviceDuration);
        if(serviceDuration.isNegative() || serviceDuration.getNano()!=0) throw invalid("Service duration must be nonnegative whole seconds.");
        Map<Integer,PlanningSnapshot.DeliveryInput> deliveries=new HashMap<>();
        for(var delivery:plan.snapshot().deliveries()) {
            if(delivery.scheduledDate()==null || delivery.startWindow()==null || delivery.endWindow()==null
                    || !delivery.startWindow().isBefore(delivery.endWindow())) throw invalid("Delivery window is invalid.");
            if(deliveries.put(delivery.id(),delivery)!=null) throw invalid("Duplicate snapshot delivery.");
        }
        Set<Integer> seen=new HashSet<>();
        // Validate before calculation, including legs following a missing travel estimate.
        for(var route:plan.routes()) for(var stop:route.stops()) {
            if(!deliveries.containsKey(stop.deliveryId()) || !seen.add(stop.deliveryId())) throw invalid("Route delivery coverage is invalid.");
            if(stop.durationSeconds()!=null && stop.durationSeconds()<0) throw invalid("Travel duration must be nonnegative.");
        }
        if(seen.size()!=deliveries.size()) throw invalid("Route delivery coverage is incomplete.");
        var routes=new ArrayList<RouteAssessment>();
        try {
            for(var route:plan.routes()) {
                LocalDateTime cursor=departure;
                var stops=new ArrayList<StopAssessment>();
                for(var stop:route.stops()) {
                    if(cursor==null || stop.durationSeconds()==null) {
                        cursor=null;
                        stops.add(new StopAssessment(stop.deliveryId(),Status.UNKNOWN,null,null,null,null,null));
                        continue;
                    }
                    var delivery=deliveries.get(stop.deliveryId());
                    var opening=delivery.scheduledDate().atTime(delivery.startWindow());
                    var closing=delivery.scheduledDate().atTime(delivery.endWindow());
                    var arrival=cursor.plusSeconds(stop.durationSeconds());
                    var start=arrival.isBefore(opening) ? opening : arrival;
                    var waiting=Duration.between(arrival,start);
                    var late=start.isAfter(closing) ? Duration.between(closing,start) : Duration.ZERO;
                    cursor=start.plus(serviceDuration);
                    stops.add(new StopAssessment(stop.deliveryId(),late.isZero() ? Status.WITHIN_WINDOW : Status.LATE,
                            arrival,start,cursor,waiting,late));
                }
                routes.add(new RouteAssessment(route.operatorId(),stops));
            }
        } catch(DateTimeException | ArithmeticException failure) {
            throw invalid("Timeline exceeds the supported date range.");
        }
        return new Assessment(departure,serviceDuration,routes);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
