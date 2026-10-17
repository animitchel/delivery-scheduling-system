package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.utility.DistanceResult;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

/** Ranks candidates locally, then enriches selected legs; persisted entities are never changed. */
public class RouteBuilder {
    @FunctionalInterface public interface DistanceLookup {
        DistanceResult get(double lat1, double lon1, double lat2, double lon2) throws Exception;
    }
    private final PlanningSnapshot.OperatorInput operator;
    private final LocalDateTime scoringTime;
    private final DistanceLookup lookup;
    private final Runnable checkDeadline;
    private final Map<String,DistanceResult> legCache=new HashMap<>();
    public RouteBuilder(PlanningSnapshot.OperatorInput operator, LocalDateTime scoringTime,
                        DistanceLookup lookup, Runnable checkDeadline) {
        this.operator=operator; this.scoringTime=scoringTime; this.lookup=lookup; this.checkDeadline=checkDeadline;
    }
    public List<CalculatedPlan.Stop> buildOptimizedRoute(List<PlanningSnapshot.DeliveryInput> deliveries) {
        List<CalculatedPlan.Stop> result=new ArrayList<>();
        List<PlanningSnapshot.DeliveryInput> remaining=new ArrayList<>(deliveries);
        double currentLat=operator.warehouse().latitude().doubleValue(), currentLon=operator.warehouse().longitude().doubleValue();
        while(!remaining.isEmpty()) {
            checkDeadline.run();
            PlanningSnapshot.DeliveryInput best=null;
            double bestScore=Double.MAX_VALUE;
            for(var d:remaining) {
                checkDeadline.run();
                double km=GeographicDistance.km(currentLat,currentLon,d.recipient().latitude().doubleValue(),d.recipient().longitude().doubleValue());
                double score=DeliveryScore.calculate(d,km,scoringTime).total();
                if(score<bestScore || (Double.compare(score,bestScore)==0 && (best==null || d.id()<best.id()))) { best=d; bestScore=score; }
            }
            if(best==null) throw new IllegalStateException("No usable route candidate.");
            List<PlanningSnapshot.DeliveryInput> group=new ArrayList<>();
            var iterator=remaining.iterator();
            while(iterator.hasNext()) {
                var d=iterator.next();
                checkDeadline.run();
                if(GeographicDistance.km(currentLat,currentLon,d.recipient().latitude().doubleValue(),d.recipient().longitude().doubleValue())<0.001) {
                    group.add(d); iterator.remove();
                }
            }
            if(group.isEmpty()) { group.add(best); remaining.remove(best); }
            // Keep the existing sub-metre grouping, but apply the same priority/urgency policy.
            group.sort((left,right) -> {
                checkDeadline.run();
                int scoreOrder=Double.compare(DeliveryScore.calculate(left,0,scoringTime).total(),
                        DeliveryScore.calculate(right,0,scoringTime).total());
                return scoreOrder!=0 ? scoreOrder : Integer.compare(left.id(),right.id());
            });
            for(var d:group) {
                var leg=getLeg(currentLat,currentLon,d.recipient().latitude().doubleValue(),d.recipient().longitude().doubleValue());
                result.add(new CalculatedPlan.Stop(d.id(),leg.getDistanceKm(),leg.getDurationText(),leg.getDurationSeconds()));
                currentLat=d.recipient().latitude().doubleValue(); currentLon=d.recipient().longitude().doubleValue();
            }
        }
        return List.copyOf(result);
    }
    private DistanceResult getLeg(double lat1,double lon1,double lat2,double lon2) {
        checkDeadline.run();
        String key=lat1+","+lon1+"->"+lat2+","+lon2;
        if(legCache.containsKey(key)) return legCache.get(key);
        DistanceResult result;
        try { result=lookup.get(lat1,lon1,lat2,lon2); }
        catch(ResponseStatusException limit) { throw limit; }
        catch(Exception failure) {
            checkDeadline.run();
            double km=GeographicDistance.km(lat1,lon1,lat2,lon2);
            result=new DistanceResult(km,"Estimated distance; time unavailable",null);
        }
        legCache.put(key,result); return result;
    }
}
