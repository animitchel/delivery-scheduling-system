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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RouteResponseDTO> getAllRoutes() {
        return routeRepository.findAll()
                .stream()
                .map(RouteMapper::toResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public com.mmu.schedulingapp.dto.route.RouteDirectory directory(int page, int size) {
        return directory(page, size, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public com.mmu.schedulingapp.dto.route.RouteDirectory directory(int page, int size, java.time.LocalDate date) {
        if (date != null) requirePlanningDate(date);
        if (page < 0 || page > 10000 || size < 1 || size > 50) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid route page or size.");
        }
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        var headers = date == null ? routeRepository.directory(pageable) : routeRepository.directoryForDate(date, pageable);
        var ids = headers.getContent().stream().map(com.mmu.schedulingapp.dto.route.RouteHeader::routeId).toList();
        var counts = ids.isEmpty() ? java.util.Map.<Integer, RouteRepository.StopCounts>of()
                : routeRepository.directoryCounts(ids).stream().collect(java.util.stream.Collectors.toMap(RouteRepository.StopCounts::getRouteId, java.util.function.Function.identity()));
        var items = headers.getContent().stream().map(header -> {
            var count = counts.get(header.routeId());
            return new com.mmu.schedulingapp.dto.route.RouteDirectory.Item(header.routeId(), header.operatorId(), header.operatorName(),
                    header.scheduledDate(), header.routeStatus(), count == null ? 0 : count.getStopCount(), count == null ? 0 : count.getFinishedCount());
        }).toList();
        return new com.mmu.schedulingapp.dto.route.RouteDirectory(items, page, size, headers.getTotalElements(), headers.getTotalPages());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<RouteResponseDTO> getRoute(int id) {
        return routeRepository.findById(id).map(RouteMapper::toResponseDTO);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RouteResponseDTO> getRouteByDriverId(int driverId) {
        Operator operator = operatorRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("Driver not found with ID: " + driverId));
        return routeRepository.findByOperator(operator).stream().map(RouteMapper::toResponseDTO).toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public List<RouteResponseDTO> createRoutes(RouteRequestDTO dto) {
        requirePlanningDate(dto.getScheduledDate());
        try(var budget=planning.begin()) {
            var snapshot=transactions.snapshot(dto.getScheduledDate(),budget);
            var plan=planner.calculate(snapshot,budget);
            return transactions.commit(plan,budget);
        }
    }

    private static void requirePlanningDate(java.time.LocalDate date) {
        if (date == null || date.getYear() < 1000 || date.getYear() > 9999)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "A planning date between years 1000 and 9999 is required.");
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
