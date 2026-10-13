package com.mmu.schedulingapp.service;


import com.mmu.schedulingapp.dto.route.RouteRequestDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import com.mmu.schedulingapp.mapper.RouteMapper;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;
import com.mmu.schedulingapp.repository.DeliveryRepository;
import com.mmu.schedulingapp.repository.OperatorRepository;
import com.mmu.schedulingapp.repository.RouteRepository;
import com.mmu.schedulingapp.service.autorouting.RouteBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final OperatorRepository operatorRepository;
    private final DeliveryRepository deliveryRepository;

    public RouteService(RouteRepository routeRepository,
                        OperatorRepository operatorRepository,
                        DeliveryRepository deliveryRepository
    ) {
        this.routeRepository = routeRepository;
        this.operatorRepository = operatorRepository;
        this.deliveryRepository = deliveryRepository;
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<RouteResponseDTO> createRoutes(RouteRequestDTO dto) {
        List<Operator> operators = operatorRepository.findAvailableIds().stream()
                .map(operatorRepository::lockById).flatMap(Optional::stream)
                .filter(o -> o.getAvailabilityStatus() == Operator.AvailabilityStatus.AVAILABLE)
                .filter(o -> !routeRepository.existsByOperatorAndRouteStatusNot(o, Route.RouteStatus.COMPLETED))
                .toList();
        if (operators.isEmpty()) throw DeliveryLifecycleService.conflict("No available operators without active routes.");
        List<Integer> pendingIds = deliveryRepository.findPendingIds(dto.getScheduledDate());

        if (pendingIds.isEmpty()) {
            throw DeliveryLifecycleService.conflict("No pending deliveries for the requested date or earlier.");
        }

        // Lock deliveries in stable order and recheck after any pending edit/delete commits.
        List<Delivery> pendingDueDeliveries = pendingIds.stream()
                .map(deliveryRepository::lockById).flatMap(Optional::stream)
                .filter(d -> d.getDeliveryStatus() == Delivery.DeliveryStatus.PENDING)
                .filter(d -> d.getScheduledDate() != null && !d.getScheduledDate().isAfter(dto.getScheduledDate()))
                .filter(d -> routeRepository.findContainingDelivery(d.getDeliveryId()).isEmpty())
                .toList();
        if (pendingDueDeliveries.isEmpty()) throw DeliveryLifecycleService.conflict("No unrouted pending deliveries.");


        return distributeDeliveriesAndCreateRoutes(dto, operators, pendingDueDeliveries);




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


    private RouteResponseDTO createAndSaveRouteForDriver(
            RouteRequestDTO dto,
            Operator operator,
            List<Delivery> deliveries) {
        // Build optimized route
        RouteBuilder builder = new RouteBuilder(operator);
        List<Delivery> optimizedRoute = builder.buildOptimizedRoute(deliveries);


        // Update delivery status & metrics
        optimizedRoute.forEach(d -> d.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS));

        // Map and save route
        Route route = RouteMapper.toEntity(dto, operator, optimizedRoute);
        route.setRouteStatus(Route.RouteStatus.IN_PROGRESS);
        operator.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY);

        operatorRepository.save(operator);
        deliveryRepository.saveAll(optimizedRoute);
        routeRepository.save(route);



        return RouteMapper.toResponseDTO(route);
    }


    private List<RouteResponseDTO> distributeDeliveriesAndCreateRoutes(
            RouteRequestDTO dto,
            List<Operator> operators,
            List<Delivery> pendingDeliveries) {
        List<RouteResponseDTO> responses = new ArrayList<>();

        int baseLoad = pendingDeliveries.size() / operators.size();
        int remainder = pendingDeliveries.size() % operators.size();
        int index = 0;

        for (int i = 0; i < operators.size(); i++) {
            int deliveriesToAssign = baseLoad + (i < remainder ? 1 : 0);
            if (deliveriesToAssign == 0) continue;

            Operator operator = operators.get(i);
            List<Delivery> subList = pendingDeliveries.subList(index, index + deliveriesToAssign);

            responses.add(createAndSaveRouteForDriver(dto, operator, subList));
            index += deliveriesToAssign;
        }

        return responses;
    }

}
