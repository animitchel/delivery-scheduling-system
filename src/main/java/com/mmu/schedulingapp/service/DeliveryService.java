package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.delivery.DeliveryRequestDTO;
import com.mmu.schedulingapp.dto.delivery.DeliveryResponseDTO;
import com.mmu.schedulingapp.mapper.DeliveryMapper;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.DeliveryRepository;
import com.mmu.schedulingapp.repository.OperatorRepository;
import com.mmu.schedulingapp.repository.RecipientRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final RecipientRepository recipientRepository;
    private final OperatorRepository operatorRepository;
    private final DeliveryLifecycleService lifecycle;
    private final com.mmu.schedulingapp.repository.RouteRepository routes;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           RecipientRepository recipientRepository,
                           OperatorRepository operatorRepository,
                           DeliveryLifecycleService lifecycle,
                           com.mmu.schedulingapp.repository.RouteRepository routes) {
        this.deliveryRepository = deliveryRepository;
        this.recipientRepository = recipientRepository;
        this.operatorRepository = operatorRepository;
        this.lifecycle = lifecycle;
        this.routes = routes;
    }

    public List<DeliveryResponseDTO> getAllDeliveries() {
        return deliveryRepository.findAll().stream()
                .map(DeliveryMapper::toResponseDTO)
                .toList();
    }

    public Optional<DeliveryResponseDTO> getDelivery(int id) {
        return deliveryRepository.findById(id).map(DeliveryMapper::toResponseDTO);
    }

    @Transactional
    public DeliveryResponseDTO createDelivery(DeliveryRequestDTO dto) {
        var input = DeliveryPreparationInput.from(dto);
        Recipient client = recipientRepository.findById(input.recipientId())
                .orElseThrow(() -> DeliveryPreparationInput.invalid("Recipient not found."));

        Operator operator = (dto.getOperatorId() != null) ?
                operatorRepository.findById(dto.getOperatorId()).orElseThrow(() -> DeliveryPreparationInput.invalid("Operator not found.")) : null;

        Delivery delivery = DeliveryMapper.toEntity(dto, client, operator);
        input.apply(delivery);
        deliveryRepository.save(delivery);
        return DeliveryMapper.toResponseDTO(delivery);
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DeliveryResponseDTO> updateDelivery(int id, DeliveryRequestDTO dto) {
        var input = DeliveryPreparationInput.from(dto);
        var lockedIds = lifecycle.lockOperators(id);
        return deliveryRepository.lockById(id).flatMap(existing -> {
            lifecycle.checkAssignmentUnchanged(id, lockedIds);
            if (!routes.findContainingDelivery(id).isEmpty()) {
                throw DeliveryLifecycleService.conflict("Routed deliveries cannot be edited.");
            }
            if (existing.getDeliveryStatus() != Delivery.DeliveryStatus.PENDING) {
                return Optional.empty(); // Can't update non-pending deliveries
            }

            Recipient recipient = recipientRepository.findById(dto.getRecipientId())
                    .orElseThrow(() -> DeliveryPreparationInput.invalid("Recipient not found."));

            Operator operator = (dto.getOperatorId() != null) ?
                    operatorRepository.findById(dto.getOperatorId()).orElseThrow(() -> DeliveryPreparationInput.invalid("Operator not found.")) : null;

            existing.setRecipientId(recipient);
            existing.setOperatorId(operator);
            input.apply(existing);

            Delivery updated = deliveryRepository.save(existing);
            return Optional.of(DeliveryMapper.toResponseDTO(updated));
        });
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DeliveryResponseDTO> updateDeliveryStatus(int id, Delivery.DeliveryStatus newStatus) {
        var lockedIds = lifecycle.lockOperators(id);
        return deliveryRepository.lockById(id).map(delivery -> {
            lifecycle.checkAssignmentUnchanged(id, lockedIds);
            // Starting work belongs to route assignment, not an arbitrary status patch.
            if (delivery.getDeliveryStatus() != Delivery.DeliveryStatus.IN_PROGRESS
                    || (newStatus != Delivery.DeliveryStatus.COMPLETED && newStatus != Delivery.DeliveryStatus.CANCELLED)) {
                throw DeliveryLifecycleService.conflict("Only in-progress deliveries can become completed or cancelled.");
            }
            var memberships = routes.findContainingDelivery(id);
            if (memberships.size() != 1 || memberships.get(0).getRouteStatus() != com.mmu.schedulingapp.model.Route.RouteStatus.IN_PROGRESS
                    || delivery.getOperatorId() == null
                    || !delivery.getOperatorId().getOperatorId().equals(memberships.get(0).getOperator().getOperatorId())) {
                throw DeliveryLifecycleService.conflict("Delivery must belong to one active route with a matching operator.");
            }
            delivery.setDeliveryStatus(newStatus);
            deliveryRepository.saveAndFlush(delivery);
            lifecycle.finalizeRoutes(id);
            return DeliveryMapper.toResponseDTO(delivery);
        });
    }




    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<DeliveryResponseDTO> deleteDelivery(int id) {
        var lockedIds = lifecycle.lockOperators(id);
        return deliveryRepository.lockById(id).map(delivery -> {
            lifecycle.checkAssignmentUnchanged(id, lockedIds);
            if (delivery.getDeliveryStatus() != Delivery.DeliveryStatus.PENDING || !routes.findContainingDelivery(id).isEmpty()) {
                throw DeliveryLifecycleService.conflict("Only unrouted pending deliveries can be deleted.");
            }
            deliveryRepository.delete(delivery);
            return DeliveryMapper.toResponseDTO(delivery);
        });
    }
}
