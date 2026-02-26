package com.mmu.schedulingapp.service;


import com.mmu.schedulingapp.dto.recipient.RecipientRequestDTO;
import com.mmu.schedulingapp.dto.recipient.RecipientResponseDTO;
import com.mmu.schedulingapp.mapper.RecipientMapper;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.RecipientRepository;
import com.mmu.schedulingapp.utility.GeocodingService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class RecipientService {

    private final RecipientRepository recipientRepository;

    public RecipientService(RecipientRepository recipientRepository) {
        this.recipientRepository = recipientRepository;
    }

    public List<RecipientResponseDTO> getAllRecipientDTOs() {
        return recipientRepository.findAll()
                .stream()
                .map(RecipientMapper::toResponseDTO)
                .toList();
    }

    public List<RecipientResponseDTO> findByIds(List<Integer> ids) {
        return recipientRepository.findAllById(ids)
                .stream()
                .map(RecipientMapper::toResponseDTO)
                .toList();
    }

    public Optional<RecipientResponseDTO> getRecipient(int id) {
        return recipientRepository.findById(id)
                .map(RecipientMapper::toResponseDTO);
    }

    @Transactional
    public RecipientResponseDTO createRecipient(RecipientRequestDTO dto) {

        // This sets the latitude and longitude in the db once
        Optional<GeocodingService.Coordinate> coord = GeocodingService.getCoordinates(dto.getAddress());


        coord.ifPresentOrElse(
            c -> {
                BigDecimal latitude = BigDecimal.valueOf(c.getLatitude());
                BigDecimal longitude = BigDecimal.valueOf(c.getLongitude());
                dto.setLatitude(latitude);
                dto.setLongitude(longitude);
                System.out.println("Coordinates set: " + latitude + ", " + longitude);
            },

            () -> {
                throw new IllegalArgumentException("No coordinates found for address: " + dto.getAddress());
            }

        );

        Recipient recipient = RecipientMapper.toEntity(dto);
        recipientRepository.save(recipient);
        return RecipientMapper.toResponseDTO(recipient);
    }

    @Transactional
    public Optional<RecipientResponseDTO> updateRecipient(int id, RecipientRequestDTO dto) {
        return recipientRepository.findById(id).map(existingRecipient -> {
            existingRecipient.setFirstName(dto.getFirstName());
            existingRecipient.setLastName(dto.getLastName());
            existingRecipient.setAddress(dto.getAddress());
            existingRecipient.setLatitude(dto.getLatitude());
            existingRecipient.setLongitude(dto.getLongitude());
            existingRecipient.setContactNumber(dto.getContactNumber());
            existingRecipient.setPriorityLevel(Recipient.PriorityLevel.valueOf(dto.getPriorityLevel().name()));

            Recipient updated = recipientRepository.save(existingRecipient);
            return RecipientMapper.toResponseDTO(updated);
        });
    }

    @Transactional
    public Optional<RecipientResponseDTO> deleteRecipient(int id) {
        return recipientRepository.findById(id).map(recipient -> {
            recipientRepository.delete(recipient);
            return RecipientMapper.toResponseDTO(recipient);
        });
    }
}
