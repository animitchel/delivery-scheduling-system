package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CollectionApiControllerTest {
    static Stream<Arguments> emptyResources() { return resources(false); }
    static Stream<Arguments> populatedResources() { return resources(true); }

    @ParameterizedTest
    @MethodSource("emptyResources")
    void emptyCollectionReturnsJsonArray(String path, Object controller) throws Exception {
        standaloneSetup(controller).build().perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @MethodSource("populatedResources")
    void populatedCollectionStillReturnsJsonArray(String path, Object controller) throws Exception {
        standaloneSetup(controller).build().perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @ParameterizedTest
    @MethodSource("emptyResources")
    void missingIndividualResourceRemainsNotFound(String path, Object controller) throws Exception {
        standaloneSetup(controller).build().perform(get(path + "/999"))
                .andExpect(status().isNotFound());
    }

    private static Stream<Arguments> resources(boolean populated) {
        Recipient recipient = Recipient.builder().recipientId(1).firstName("Test").lastName("Recipient")
                .priorityLevel(Recipient.PriorityLevel.MEDIUM).build();
        Operator operator = Operator.builder().operatorId(1).name("Test Operator").build();
        Warehouse warehouse = Warehouse.builder().warehouseId(1).name("Test Warehouse").build();
        Delivery delivery = Delivery.builder().deliveryId(1).recipientId(recipient)
                .deliveryStatus(Delivery.DeliveryStatus.PENDING).build();
        Route route = Route.builder().routeId(1).operator(operator).deliveries(List.of())
                .routeStatus(Route.RouteStatus.PLANNED).build();
        return Stream.of(
                Arguments.of("/api/v1/scheduling-app/recipients", new RecipientApiController(
                        new RecipientService(repository(RecipientRepository.class, populated, recipient)))),
                Arguments.of("/api/v1/scheduling-app/operators", new OperatorApiController(
                        new OperatorService(repository(OperatorRepository.class, populated, operator), null))),
                Arguments.of("/api/v1/scheduling-app/warehouses", new WarehouseApiController(
                        new WarehouseService(repository(WarehouseRepository.class, populated, warehouse)))),
                Arguments.of("/api/v1/scheduling-app/deliveries", new DeliveryApiController(
                        new DeliveryService(repository(DeliveryRepository.class, populated, delivery), null, null, null, null))),
                Arguments.of("/api/v1/scheduling-app/routes", new RouteApiController(
                        new RouteService(repository(RouteRepository.class, populated, route), null, null))));
    }

    // Only implement repository reads exercised by these HTTP contract tests.
    // Every other operation fails immediately, so no persistence or provider work can occur.
    private static <T> T repository(Class<T> type, boolean populated, Object entity) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> populated ? List.of(entity) : List.of();
                    case "findById" -> Optional.empty();
                    default -> throw new AssertionError("Unexpected repository call: " + method.getName());
                }));
    }
}
