package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.delivery.*;
import com.mmu.schedulingapp.mapper.DeliveryMapper;
import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.repository.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Optional;

@Service
public class DeliveryPreparationService {
    private final DeliveryRepository deliveries;
    private final RecipientRepository recipients;
    public DeliveryPreparationService(DeliveryRepository deliveries, RecipientRepository recipients) {
        this.deliveries = deliveries; this.recipients = recipients;
    }
    @Transactional
    public DeliveryResponseDTO create(DeliveryCreateRequest request) {
        var input = DeliveryPreparationInput.from(request);
        // Serialize with recipient deletion, retaining the FK rather than accepting a stale reference.
        var recipient = recipients.lockForUpdate(input.recipientId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found."));
        var delivery = new Delivery(); delivery.setRecipientId(recipient);
        delivery.setOperatorId(null); delivery.setDeliveryStatus(Delivery.DeliveryStatus.PENDING);
        input.apply(delivery);
        return DeliveryMapper.toResponseDTO(deliveries.saveAndFlush(delivery));
    }
    @Transactional(readOnly = true)
    public Optional<DeliveryResponseDTO> read(int id) {
        if (id < 1) throw DeliveryPreparationInput.invalid("Invalid delivery reference.");
        return deliveries.findById(id).map(DeliveryMapper::toResponseDTO);
    }
    @Transactional(readOnly = true)
    public DeliveryDirectory directory(int page, int size, LocalDate date, Delivery.DeliveryStatus status, Integer recipient) {
        if (page < 0 || page > 10000 || size < 1 || size > 50 || (recipient != null && recipient < 1)
                || (date != null && (date.getYear() < 1000 || date.getYear() > 9999)))
            throw DeliveryPreparationInput.invalid("Invalid directory filters or page bounds.");
        var results = deliveries.directory(date, status, recipient,
                PageRequest.of(page, size, Sort.by("scheduledDate", "deliveryId")));
        return new DeliveryDirectory(results.getContent(), page, size, results.getTotalElements(), results.getTotalPages());
    }
}
