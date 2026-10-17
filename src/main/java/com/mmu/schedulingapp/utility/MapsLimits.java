package com.mmu.schedulingapp.utility;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import lombok.Getter;
import lombok.Setter;

/** Conservative initial limits, to be tuned using representative staging workloads. */
@Component
@ConfigurationProperties(prefix = "scheduling.maps")
@Validated
@Getter @Setter
public class MapsLimits {
    @Min(1) @Max(30000) private int connectTimeoutMs = 1000;
    @Min(1) @Max(30000) private int readTimeoutMs = 2000;
    @Min(1) @Max(60000) private int requestTimeoutMs = 3000;
    @Min(1024) @Max(1048576) private int maxResponseBytes = 262144;
    @Min(1) @Max(120000) private int planningTimeoutMs = 10000;
    @Min(1) @Max(500) private int maxDeliveries = 50;
    @Min(1) @Max(500) private int maxOperatorCandidates = 500;
    @Min(1) @Max(10000) private int maxProviderCalls = 100;
    @Min(1) @Max(8) private int maxConcurrentPlanning = 2;
}
