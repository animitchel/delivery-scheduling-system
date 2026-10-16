package com.mmu.schedulingapp.service.autorouting;

import com.mmu.schedulingapp.utility.PlanningGuard;
import java.util.*;

/** Greedy nearest-warehouse pairs subject to balanced stop-count quotas; not vehicle capacity. */
final class DriverAllocator {
    record Assignment(PlanningSnapshot.OperatorInput operator,List<PlanningSnapshot.DeliveryInput> deliveries) {
        Assignment { deliveries=List.copyOf(deliveries); }
    }
    private record Candidate(int operatorIndex,PlanningSnapshot.DeliveryInput delivery,double distance) { }
    static List<Assignment> allocate(PlanningSnapshot snapshot,PlanningGuard.Budget budget) {
        budget.check();
        var operators=snapshot.operators().stream().sorted(Comparator.comparing(PlanningSnapshot.OperatorInput::id)).toList();
        int base=snapshot.deliveries().size()/operators.size(), extra=snapshot.deliveries().size()%operators.size();
        var candidates=new ArrayList<Candidate>();
        var allocations=new ArrayList<List<PlanningSnapshot.DeliveryInput>>();
        for(int i=0;i<operators.size();i++) {
            allocations.add(new ArrayList<>());
            var warehouse=operators.get(i).warehouse();
            for(var delivery:snapshot.deliveries()) {
                budget.check();
                var recipient=delivery.recipient();
                candidates.add(new Candidate(i,delivery,GeographicDistance.km(warehouse.latitude().doubleValue(),warehouse.longitude().doubleValue(),
                        recipient.latitude().doubleValue(),recipient.longitude().doubleValue())));
            }
        }
        var order=Comparator.comparingDouble(Candidate::distance).thenComparingInt(Candidate::operatorIndex)
                .thenComparing(candidate -> candidate.delivery().id());
        candidates.sort((left,right) -> { budget.check(); return order.compare(left,right); });
        Set<Integer> assigned=new HashSet<>();
        for(var candidate:candidates) {
            budget.check();
            var allocation=allocations.get(candidate.operatorIndex());
            int quota=base+(candidate.operatorIndex()<extra ? 1 : 0);
            if(allocation.size()<quota && assigned.add(candidate.delivery().id())) allocation.add(candidate.delivery());
        }
        if(assigned.size()!=snapshot.deliveries().size()) throw new IllegalStateException("Incomplete delivery allocation.");
        var result=new ArrayList<Assignment>();
        for(int i=0;i<operators.size();i++) {
            if(!allocations.get(i).isEmpty()) result.add(new Assignment(operators.get(i),allocations.get(i)));
        }
        budget.check(); return List.copyOf(result);
    }
}
