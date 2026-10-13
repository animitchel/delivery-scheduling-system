package com.mmu.schedulingapp.utility;

import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.model.Warehouse;
import com.mmu.schedulingapp.service.autorouting.RouteBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RouteMapsFallbackTest {
    @Test
    void publicRouteWorkflowKeepsFallbackWhenEnvironmentKeyIsMissing() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, MissingKeyProbe.class.getName()).redirectErrorStream(true);
        // Explicitly disable credentials in the child process: no provider request can be made.
        builder.environment().put("GOOGLE_MAPS_API_KEY", "");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            assertThat(output).contains("fallback-preserved");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static class MissingKeyProbe {
        public static void main(String[] args) {
            Warehouse warehouse = Warehouse.builder().latitude(new BigDecimal("53.48"))
                    .longitude(new BigDecimal("-2.24")).build();
            Operator operator = Operator.builder().operatorId(1).warehouse(warehouse).build();
            Recipient recipient = Recipient.builder().latitude(new BigDecimal("53.50"))
                    .longitude(new BigDecimal("-2.25")).priorityLevel(Recipient.PriorityLevel.MEDIUM).build();
            Delivery delivery = Delivery.builder().deliveryId(1).recipientId(recipient)
                    .scheduledDate(LocalDate.now()).endTimeWindow(LocalTime.MAX)
                    .priorityLevel(Delivery.PriorityLevel.MEDIUM).build();
            var input = com.mmu.schedulingapp.service.autorouting.PlanningSnapshot.DeliveryInput.from(delivery);
            var route = new RouteBuilder(com.mmu.schedulingapp.service.autorouting.PlanningSnapshot.OperatorInput.from(operator),
                    java.time.LocalDateTime.now(), DistanceCalculator::getGoogleMapsDistance, () -> {}).buildOptimizedRoute(List.of(input));
            if (route.size() != 1 || route.get(0).deliveryId() != 1 || delivery.getOperatorId() != null
                    || delivery.getFromPrevDistanceKm() != null || route.get(0).distanceKm() <= 0
                    || !"Estimated distance; time unavailable".equals(route.get(0).durationText())
                    || route.get(0).durationSeconds() != null) {
                throw new AssertionError("Labelled fallback or immutable calculation changed");
            }
            System.out.println("fallback-preserved");
        }
    }
}
