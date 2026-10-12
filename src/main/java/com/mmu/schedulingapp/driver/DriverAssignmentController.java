package com.mmu.schedulingapp.driver;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mmu.schedulingapp.account.AccountPrincipal;
import com.mmu.schedulingapp.dto.delivery.DeliveryResponseDTO;
import com.mmu.schedulingapp.dto.route.RouteResponseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/driver")
public class DriverAssignmentController {
    private final DriverAssignmentService assignments;

    public DriverAssignmentController(DriverAssignmentService assignments) { this.assignments = assignments; }

    public static final class StatusRequest {
        @NotBlank
        @Pattern(regexp = "COMPLETED|CANCELLED")
        private String deliveryStatus;
        private boolean receivedStatus;

        @JsonProperty("deliveryStatus")
        public void setDeliveryStatus(String status) {
            if (receivedStatus) throw new IllegalArgumentException("Duplicate deliveryStatus is not allowed.");
            receivedStatus = true;
            deliveryStatus = status;
        }

        public String deliveryStatus() { return deliveryStatus; }

        @JsonAnySetter
        public void rejectExtraField(String field, Object value) {
            throw new IllegalArgumentException("Only deliveryStatus may be supplied.");
        }
    }

    @GetMapping("/routes")
    public List<RouteResponseDTO> routes(@AuthenticationPrincipal AccountPrincipal account) {
        return assignments.routes(account.getAccountId());
    }

    @GetMapping("/routes/{routeId}")
    public RouteResponseDTO route(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Integer routeId) {
        return assignments.route(account.getAccountId(), routeId);
    }

    @GetMapping("/deliveries/{deliveryId}")
    public DeliveryResponseDTO delivery(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Integer deliveryId) {
        return assignments.delivery(account.getAccountId(), deliveryId);
    }

    @GetMapping("/deliveries/{deliveryId}/recipient")
    public DriverAssignmentService.RecipientDetails recipient(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Integer deliveryId) {
        return assignments.recipient(account.getAccountId(), deliveryId);
    }

    @PatchMapping("/deliveries/{deliveryId}/status")
    public DeliveryResponseDTO status(@AuthenticationPrincipal AccountPrincipal account, @PathVariable Integer deliveryId,
                                      @Valid @RequestBody StatusRequest request) {
        return assignments.status(account.getAccountId(), deliveryId, request.deliveryStatus());
    }
}
