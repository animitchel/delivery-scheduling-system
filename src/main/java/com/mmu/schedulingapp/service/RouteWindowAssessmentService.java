package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.route.*;
import com.mmu.schedulingapp.repository.RouteRepository;
import com.mmu.schedulingapp.service.autorouting.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;

@Service
public class RouteWindowAssessmentService {
    private final RouteRepository routes;
    public RouteWindowAssessmentService(RouteRepository routes) { this.routes=routes; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RouteWindowAssessment assess(int id,RouteWindowAssessmentRequest request) {
        if(id<1 || request.departure()==null || request.serviceDurationSeconds()==null || request.serviceDurationSeconds()<0)
            throw new IllegalArgumentException("Invalid assessment assumptions.");
        var header=routes.assessmentHeader(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Route not found."));
        var saved=routes.assessmentStops(id,PageRequest.of(0,501));
        if(saved.size()>500) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Route exceeds the assessment stop limit.");
        var inputs=new ArrayList<PlanningSnapshot.DeliveryInput>();
        var legs=new ArrayList<CalculatedPlan.Stop>();
        for(int i=0;i<saved.size();i++) {
            var stop=saved.get(i);
            if(stop.stopOrder()==null || stop.stopOrder()!=i || stop.scheduledDate()==null || stop.startWindow()==null || stop.endWindow()==null
                    || !stop.startWindow().isBefore(stop.endWindow()) || stop.startWindow().getNano()!=0 || stop.endWindow().getNano()!=0
                    || stop.durationSeconds()!=null && stop.durationSeconds()<0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Saved route has invalid assessment data.");
            // Window-only adapter, never used for assignment/commit; no related entities are loaded.
            inputs.add(new PlanningSnapshot.DeliveryInput(stop.deliveryId(),null,null,stop.scheduledDate(),stop.startWindow(),stop.endWindow(),null,null,null));
            legs.add(new CalculatedPlan.Stop(stop.deliveryId(),0,null,stop.durationSeconds()));
        }
        var plan=new CalculatedPlan(new PlanningSnapshot(header.scheduledDate(),request.departure(),List.of(),inputs),
                List.of(new CalculatedPlan.DriverRoute(header.operatorId(),legs)));
        var assessed=DeliveryWindowAssessor.assess(plan,request.departure(),Duration.ofSeconds(request.serviceDurationSeconds())).routes().get(0).stops();
        var result=new ArrayList<RouteWindowAssessment.Stop>();
        for(int i=0;i<assessed.size();i++) {
            var stop=assessed.get(i); var input=saved.get(i);
            result.add(new RouteWindowAssessment.Stop(stop.deliveryId(),i+1,input.scheduledDate().atTime(input.startWindow()),
                    input.scheduledDate().atTime(input.endWindow()),stop.status(),stop.arrival(),stop.serviceStart(),stop.departure(),
                    stop.waiting()==null ? null : stop.waiting().getSeconds(),stop.lateness()==null ? null : stop.lateness().getSeconds()));
        }
        return new RouteWindowAssessment(header.routeId(),header.operatorId(),header.routeStatus(),true,"SAVED_TRAVEL_ESTIMATES",
                "SERVICE_START_INCLUSIVE",request.departure(),request.serviceDurationSeconds(),result);
    }
}
