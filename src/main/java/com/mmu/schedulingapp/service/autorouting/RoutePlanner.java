package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.utility.MapsProvider;
import com.mmu.schedulingapp.utility.PlanningGuard;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

@Component
public class RoutePlanner {
    private final MapsProvider maps;
    public RoutePlanner(MapsProvider maps) { this.maps=maps; }
    public CalculatedPlan calculate(PlanningSnapshot snapshot, PlanningGuard.Budget budget) {
        var result=new ArrayList<CalculatedPlan.DriverRoute>();
        for(var assignment:DriverAllocator.allocate(snapshot,budget)) {
            budget.check();
            var operator=assignment.operator();
            var builder=new RouteBuilder(operator,snapshot.scoringTime(),
                    (lat1,lon1,lat2,lon2) -> maps.distance(lat1,lon1,lat2,lon2,budget),budget::check);
            var stops=builder.buildOptimizedRoute(assignment.deliveries());
            result.add(new CalculatedPlan.DriverRoute(operator.id(),stops));
        }
        budget.check(); return new CalculatedPlan(snapshot,result);
    }
}
