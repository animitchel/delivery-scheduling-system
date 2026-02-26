package com.mmu.schedulingapp.mapper;

import com.mmu.schedulingapp.dto.recipient.RecipientRequestDTO;
import com.mmu.schedulingapp.dto.recipient.RecipientResponseDTO;
import com.mmu.schedulingapp.model.Recipient;

import java.time.LocalDateTime;

public class RecipientMapper {

    public static Recipient toEntity(RecipientRequestDTO dto) {
        if (dto == null) return null;

        return Recipient.builder()
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .address(dto.getAddress())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .contactNumber(dto.getContactNumber())
                .priorityLevel(Recipient.PriorityLevel.valueOf(dto.getPriorityLevel().name()))
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static RecipientResponseDTO toResponseDTO(Recipient entity) {
        if (entity == null) return null;

        return RecipientResponseDTO.builder()
                .recipientId(entity.getRecipientId())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .address(entity.getAddress())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .contactNumber(entity.getContactNumber())
                .priorityLevel(RecipientResponseDTO.PriorityLevel.valueOf(entity.getPriorityLevel().name()))
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
