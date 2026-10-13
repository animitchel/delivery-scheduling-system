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
        int base=snapshot.deliveries().size()/snapshot.operators().size();
        int extra=snapshot.deliveries().size()%snapshot.operators().size(), index=0;
        for(int i=0;i<snapshot.operators().size();i++) {
            budget.check();
            var operator=snapshot.operators().get(i);
            int count=base+(i<extra ? 1 : 0);
            if(count==0) continue;
            var builder=new RouteBuilder(operator,snapshot.scoringTime(),
                    (lat1,lon1,lat2,lon2) -> maps.distance(lat1,lon1,lat2,lon2,budget),budget::check);
            var stops=builder.buildOptimizedRoute(snapshot.deliveries().subList(index,index+count)); index+=count;
            result.add(new CalculatedPlan.DriverRoute(operator.id(),stops));
        }
        budget.check(); return new CalculatedPlan(snapshot,result);
    }
}
