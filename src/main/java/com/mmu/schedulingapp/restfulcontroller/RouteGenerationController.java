package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.route.*;
import com.mmu.schedulingapp.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin/routes")
public class RouteGenerationController {
    private final RouteService service;
    public RouteGenerationController(RouteService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<RouteDirectory> directory(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String scheduledDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(page, size,
                scheduledDate == null ? null : RouteGenerationRequest.date(scheduledDate)));
    }
    @GetMapping("/{id}")
    public ResponseEntity<RouteResponseDTO> read(@PathVariable int id) {
        if (id < 1) throw new IllegalArgumentException("Invalid route reference.");
        return service.getRoute(id).map(route -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(route))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PostMapping("/generate")
    public ResponseEntity<RouteGenerationResult> generate(@Valid @RequestBody RouteGenerationRequest input) {
        long started = System.nanoTime();
        var request = RouteRequestDTO.builder().scheduledDate(input.scheduledDate()).build();
        // The proxied service returns only after its separate commit transaction completes.
        var created = service.createRoutes(request);
        var summaries = created.stream().map(route -> new RouteDirectory.Item(route.getRouteId(),
                route.getOperatorId(), route.getOperatorName(), route.getScheduledDate(), route.getRouteStatus(),
                route.getDeliveries().size(), route.getDeliveries().stream().filter(stop ->
                        "COMPLETED".equals(stop.getStatus()) || "CANCELLED".equals(stop.getStatus())).count())).toList();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new RouteGenerationResult(input.scheduledDate(), summaries,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
    }
}
