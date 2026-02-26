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
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final RecipientRepository recipientRepository;
    private final OperatorRepository operatorRepository;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           RecipientRepository recipientRepository,
                           OperatorRepository operatorRepository) {
        this.deliveryRepository = deliveryRepository;
        this.recipientRepository = recipientRepository;
        this.operatorRepository = operatorRepository;
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
        Recipient client = recipientRepository.findById(dto.getRecipientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found"));

        Operator operator = (dto.getOperatorId() != null) ?
                operatorRepository.findById(dto.getOperatorId()).orElse(null) : null;

        Delivery delivery = DeliveryMapper.toEntity(dto, client, operator);
        deliveryRepository.save(delivery);
        return DeliveryMapper.toResponseDTO(delivery);
    }


    @Transactional
    public Optional<DeliveryResponseDTO> updateDelivery(int id, DeliveryRequestDTO dto) {
        return deliveryRepository.findById(id).flatMap(existing -> {
            if (existing.getDeliveryStatus() != Delivery.DeliveryStatus.PENDING) {
                return Optional.empty(); // Can't update non-pending deliveries
            }

            Recipient recipient = recipientRepository.findById(dto.getRecipientId())
                    .orElseThrow(() -> new IllegalArgumentException("Client not found"));

            Operator operator = (dto.getOperatorId() != null) ?
                    operatorRepository.findById(dto.getOperatorId()).orElse(null) : null;

            existing.setRecipientId(recipient);
            existing.setOperatorId(operator);
            existing.setScheduledDate(dto.getScheduledDate());
            existing.setStartTimeWindow(dto.getStartTimeWindow());
            existing.setEndTimeWindow(dto.getEndTimeWindow());
            existing.setPriorityLevel(dto.getPriorityLevel());
            existing.setSpecialInstructions(dto.getSpecialInstructions());

            Delivery updated = deliveryRepository.save(existing);
            return Optional.of(DeliveryMapper.toResponseDTO(updated));
        });
    }


    @Transactional
    public Optional<DeliveryResponseDTO> updateDeliveryStatus(int id, Delivery.DeliveryStatus newStatus) {
        return deliveryRepository.findById(id).flatMap(delivery -> {
            if (delivery.getDeliveryStatus() != Delivery.DeliveryStatus.IN_PROGRESS && newStatus != Delivery.DeliveryStatus.IN_PROGRESS) {
                return Optional.empty(); // Can only update if status is IN_PROGRESS
            }

            delivery.setDeliveryStatus(newStatus);
            Delivery updated = deliveryRepository.save(delivery);
            return Optional.of(DeliveryMapper.toResponseDTO(updated));
        });
    }




    @Transactional
    public Optional<DeliveryResponseDTO> deleteDelivery(int id) {
        return deliveryRepository.findById(id).map(delivery -> {
            deliveryRepository.delete(delivery);
            return DeliveryMapper.toResponseDTO(delivery);
        });
    }
}
