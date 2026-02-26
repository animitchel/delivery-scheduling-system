package com.mmu.schedulingapp.dto.route;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteRequestDTO {

    private Integer operatorId;

    @NotNull(message = "Scheduled date is required")
    private LocalDate scheduledDate;

}
