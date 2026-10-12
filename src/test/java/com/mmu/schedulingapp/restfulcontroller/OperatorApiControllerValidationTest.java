package com.mmu.schedulingapp.restfulcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.dto.operator.OperatorRequestDTO;
import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import com.mmu.schedulingapp.exception.GlobalExceptionHandler;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.service.OperatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OperatorApiControllerValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RecordingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new RecordingService();
        mvc = standaloneSetup(new OperatorApiController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<Arguments> validRequests() {
        return writeMethods().flatMap(method -> Stream.of(Operator.AvailabilityStatus.values())
                .map(status -> Arguments.of(method, status)));
    }

    static Stream<HttpMethod> writeMethods() {
        return Stream.of(HttpMethod.POST, HttpMethod.PUT);
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    void acceptsEveryAvailabilityStatus(HttpMethod method, Operator.AvailabilityStatus availability) throws Exception {
        mvc.perform(request(method, path(method)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload(availability.name()))))
                .andExpect(status().is(method.equals(HttpMethod.POST) ? 201 : 200))
                .andExpect(jsonPath("operatorId").value(1));
        assertThat(service.calls).isEqualTo(1);
        assertThat(service.lastRequest.getAvailabilityStatus()).isEqualTo(availability);
    }

    @ParameterizedTest
    @MethodSource("writeMethods")
    void missingStatusReturnsFieldErrorWithoutCallingService(HttpMethod method) throws Exception {
        var body = new HashMap<>(payload("AVAILABLE"));
        body.remove("availabilityStatus");
        mvc.perform(request(method, path(method)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("availabilityStatus").value("Availability status is required"));
        assertThat(service.calls).isZero();
    }

    @ParameterizedTest
    @MethodSource("writeMethods")
    void invalidEnumReturnsBadRequestWithoutCallingService(HttpMethod method) throws Exception {
        mvc.perform(request(method, path(method)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload("INVALID"))))
                .andExpect(status().isBadRequest());
        assertThat(service.calls).isZero();
    }

    private static String path(HttpMethod method) {
        return "/api/v1/scheduling-app/operators" + (method.equals(HttpMethod.PUT) ? "/1" : "");
    }

    private static Map<String, Object> payload(String availability) {
        return Map.of("name", "Test Operator", "phoneNumber", "+447123456789",
                "email", "driver@example.com", "maxCapacity", 25.0,
                "warehouseName", "Test Warehouse", "availabilityStatus", availability);
    }

    private static class RecordingService extends OperatorService {
        private int calls;
        private OperatorRequestDTO lastRequest;
        RecordingService() { super(null, null); }
        @Override public OperatorResponseDTO createDriver(OperatorRequestDTO dto) { return record(dto); }
        @Override public Optional<OperatorResponseDTO> updateDriver(int id, OperatorRequestDTO dto) {
            return Optional.of(record(dto));
        }
        private OperatorResponseDTO record(OperatorRequestDTO dto) {
            calls++;
            lastRequest = dto;
            return OperatorResponseDTO.builder().operatorId(1).name(dto.getName()).build();
        }
    }
}
