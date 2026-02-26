package com.mmu.schedulingapp.dto.recipient;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientRequestDTO {
    private Integer recipientId;

    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must not exceed 50 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must not exceed 50 characters")
    private String lastName;

    @NotBlank(message = "Address is required")
    private String address;

    @Digits(integer = 3, fraction = 6, message = "Latitude must be a valid coordinate")
    private BigDecimal latitude;

    @Digits(integer = 3, fraction = 6, message = "Longitude must be a valid coordinate")
    private BigDecimal longitude;

    @Size(max = 20, message = "Contact number must not exceed 20 characters")
    private String contactNumber;

    @NotNull(message = "Priority level is required")
    private PriorityLevel priorityLevel;

    public enum PriorityLevel {
        HIGH,
        MEDIUM,
        LOW
    }
}
