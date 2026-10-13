package com.mmu.schedulingapp.service;


import com.mmu.schedulingapp.dto.route.RouteRequestDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.mapper.RouteMapper;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;
import com.mmu.schedulingapp.repository.DeliveryRepository;
import com.mmu.schedulingapp.repository.OperatorRepository;
import com.mmu.schedulingapp.repository.RouteRepository;
import com.mmu.schedulingapp.service.autorouting.RoutePlanner;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final OperatorRepository operatorRepository;
    private final RoutePlanningTransactions transactions;
    private final RoutePlanner planner;
    private final com.mmu.schedulingapp.utility.PlanningGuard planning;

    @org.springframework.beans.factory.annotation.Autowired
    public RouteService(RouteRepository routes, OperatorRepository operators, RoutePlanningTransactions transactions,
                        RoutePlanner planner, com.mmu.schedulingapp.utility.PlanningGuard planning) {
        this.routeRepository=routes; this.operatorRepository=operators;
        this.transactions=transactions; this.planner=planner; this.planning=planning;
    }

    public RouteService(RouteRepository routes, OperatorRepository operators, DeliveryRepository deliveries) {
        this(routes,operators,null,null,null);
    }

    public List<RouteResponseDTO> getAllRoutes() {
        return routeRepository.findAll()
                .stream()
                .map(RouteMapper::toResponseDTO)
                .toList();
    }

    public Optional<RouteResponseDTO> getRoute(int id) {
        return routeRepository.findById(id).map(RouteMapper::toResponseDTO);
    }

    public List<RouteResponseDTO> getRouteByDriverId(int driverId) {
        Operator operator = operatorRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found with ID: " + driverId));
        return routeRepository.findByOperator(operator).stream().map(RouteMapper::toResponseDTO).toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public List<RouteResponseDTO> createRoutes(RouteRequestDTO dto) {
        try(var budget=planning.begin()) {
            var snapshot=transactions.snapshot(dto.getScheduledDate(),budget);
            var plan=planner.calculate(snapshot,budget);
            return transactions.commit(plan,budget);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<RouteResponseDTO> deleteRoute(int id) {
        return routeRepository.findById(id).map(route -> {
            requireDeletable(route);
            var response = RouteMapper.toResponseDTO(route);
            routeRepository.delete(route);
            return response;
        });
    }

    private void requireDeletable(Route route) {
        if (route.getRouteStatus() != Route.RouteStatus.PLANNED || !route.getDeliveries().isEmpty()) {
            throw DeliveryLifecycleService.conflict("Only empty planned routes can be deleted; active and historical routes are preserved.");
        }
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RouteResponseDTO> deleteAllRoutes() {
        List<Route> routes = routeRepository.findAll();
        // Preflight the whole batch before deleting anything.
        routes.forEach(this::requireDeletable);
        List<RouteResponseDTO> deletedRoutes = new ArrayList<>();
        for (Route route : routes) {
            deletedRoutes.add(RouteMapper.toResponseDTO(route));
            routeRepository.delete(route);
        }

        return deletedRoutes;
    }


}
