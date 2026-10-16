package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.route.*;
import com.mmu.schedulingapp.service.RouteWindowAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/routes")
public class RouteWindowAssessmentController {
    private final RouteWindowAssessmentService service;
    public RouteWindowAssessmentController(RouteWindowAssessmentService service) { this.service=service; }
    @PostMapping("/{id}/assessment")
    public ResponseEntity<RouteWindowAssessment> assess(@PathVariable int id,@Valid @RequestBody RouteWindowAssessmentRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.assess(id,request));
    }
}
