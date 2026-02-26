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
import jakarta.transaction.Transactional;
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

    @Transactional
    public List<RouteResponseDTO> createRoutes(RouteRequestDTO dto) {
        List<RouteResponseDTO> responses = new ArrayList<>();
        List<Delivery> pendingDueDeliveries = deliveryRepository
                .findByScheduledDateLessThanEqualAndDeliveryStatus(
                        dto.getScheduledDate(), Delivery.DeliveryStatus.PENDING
                );

        if (pendingDueDeliveries.isEmpty()) {
            throw new IllegalStateException(
                    "Error creating Route, No pending deliveries found for today or earlier."
            );
        }

        // Get all available drivers
        List<Operator> operators = operatorRepository.findAllByAvailabilityStatus(
                Operator.AvailabilityStatus.AVAILABLE);

        if (operators.isEmpty()) {
            throw new IllegalStateException("No Drivers found, please create driver(s).");
        }


        return distributeDeliveriesAndCreateRoutes(dto, operators, pendingDueDeliveries);




    }

    @Transactional
    public Optional<RouteResponseDTO> deleteRoute(int id) {
        return routeRepository.findById(id).map(route -> {
            updateDeliveryOperatorStatusAndDeleteRoute(route);
            return RouteMapper.toResponseDTO(route);
        });
    }

    private void updateDeliveryOperatorStatusAndDeleteRoute(Route route) {
        route.getDeliveries().forEach(delivery -> {
            delivery.setOperatorId(null);
            delivery.setDeliveryStatus(Delivery.DeliveryStatus.PENDING);
            delivery.setFromPrevDistanceKm(null);
            delivery.setFromPrevDurationSeconds(null);
            delivery.setFromPrevDurationText(null);

        });
        deliveryRepository.saveAll(route.getDeliveries());
        Operator operator = route.getOperator();
        operator.setAvailabilityStatus(Operator.AvailabilityStatus.AVAILABLE);
        operatorRepository.save(operator);
        routeRepository.delete(route);
    }


    @Transactional
    public List<RouteResponseDTO> deleteAllRoutes() {
        List<Route> routes = routeRepository.findAll();
        List<RouteResponseDTO> deletedRoutes = new ArrayList<>();

        for (Route route : routes) {
            // Reset deliveries
            updateDeliveryOperatorStatusAndDeleteRoute(route);

            // Collect DTO for response
            deletedRoutes.add(RouteMapper.toResponseDTO(route));
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
