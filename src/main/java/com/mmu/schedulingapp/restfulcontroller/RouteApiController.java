package com.mmu.schedulingapp.restfulcontroller;


import com.mmu.schedulingapp.dto.route.RouteRequestDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scheduling-app/routes")
public class RouteApiController {

    private final RouteService routeService;

    public RouteApiController(RouteService routeService) {
        this.routeService = routeService;
    }


    @GetMapping
    public ResponseEntity<List<RouteResponseDTO>> getAllRoutes() {
        List<RouteResponseDTO> routes = routeService.getAllRoutes();
        if (routes.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(routes);
    }


    @GetMapping("/{id}")
    public ResponseEntity<RouteResponseDTO> getRoute(@PathVariable int id) {
        Optional<RouteResponseDTO> optionalRoute = routeService.getRoute(id);
        return optionalRoute
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }


    @GetMapping("/operator/{operatorId}")
    public ResponseEntity<List<RouteResponseDTO>> getRouteByDriverId(@PathVariable int operatorId) {
        List<RouteResponseDTO> operatorRoutes = routeService.getRouteByDriverId(operatorId);
        if (operatorRoutes.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(operatorRoutes);
    }


    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoutes(@Valid @RequestBody RouteRequestDTO dto) {
        long startTime = System.currentTimeMillis();

        List<RouteResponseDTO> createdRoutes = routeService.createRoutes(dto);

        long endTime = System.currentTimeMillis();
        long processingTime = endTime - startTime; // in ms

        Map<String, Object> response = new HashMap<>();
        response.put("routes", createdRoutes);
        response.put("processingTimeMs", processingTime);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<RouteResponseDTO> deleteRoute(@PathVariable int id) {
        return routeService.deleteRoute(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping
    public ResponseEntity<List<RouteResponseDTO>> deleteAllRoutes() {
        List<RouteResponseDTO> deletedRoutes = routeService.deleteAllRoutes();
        if (deletedRoutes.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(deletedRoutes);
    }

}
