package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.utility.PlanningGuard;
import java.util.*;

/** Local nearest-pair selection among routable candidates, before count-balanced allocation. */
public final class DriverSelector {
    private DriverSelector() { }
    private record Pair(PlanningSnapshot.OperatorInput operator,Integer deliveryId,double distance) { }
    public static List<PlanningSnapshot.OperatorInput> select(List<PlanningSnapshot.OperatorInput> candidates,
            List<PlanningSnapshot.DeliveryInput> deliveries,PlanningGuard.Budget budget) {
        budget.check();
        int target=Math.min(candidates.size(),deliveries.size());
        if(target==0) return List.of();
        if(target==candidates.size()) return candidates.stream().sorted(Comparator.comparing(PlanningSnapshot.OperatorInput::id)).toList();
        var pairs=new ArrayList<Pair>();
        for(var operator:candidates) for(var delivery:deliveries) {
            budget.check();
            var warehouse=operator.warehouse(); var recipient=delivery.recipient();
            pairs.add(new Pair(operator,delivery.id(),GeographicDistance.km(warehouse.latitude().doubleValue(),warehouse.longitude().doubleValue(),
                    recipient.latitude().doubleValue(),recipient.longitude().doubleValue())));
        }
        var order=Comparator.comparingDouble(Pair::distance).thenComparing(pair -> pair.operator().id()).thenComparing(Pair::deliveryId);
        pairs.sort((left,right) -> { budget.check(); return order.compare(left,right); });
        var selected=new TreeMap<Integer,PlanningSnapshot.OperatorInput>();
        Set<Integer> matched=new HashSet<>();
        for(var pair:pairs) {
            budget.check();
            if(!selected.containsKey(pair.operator().id()) && matched.add(pair.deliveryId())) {
                selected.put(pair.operator().id(),pair.operator());
                if(selected.size()==target) break;
            }
        }
        if(selected.size()!=target) throw new IllegalStateException("Incomplete driver selection.");
        return List.copyOf(selected.values());
    }
}
