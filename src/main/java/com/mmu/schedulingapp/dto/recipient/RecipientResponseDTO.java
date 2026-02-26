package com.mmu.schedulingapp.dto.recipient;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientResponseDTO {

    private Integer recipientId;

    private String firstName;

    private String lastName;

    private String address;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private String contactNumber;

    private PriorityLevel priorityLevel;

    private LocalDateTime createdAt;

    public enum PriorityLevel {
        HIGH,
        MEDIUM,
        LOW
    }
}
