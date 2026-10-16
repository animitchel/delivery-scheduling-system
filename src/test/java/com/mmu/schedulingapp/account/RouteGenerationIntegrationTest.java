package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.dto.route.*;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:route-generation;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false","logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class RouteGenerationIntegrationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final TestWorkers workers = new TestWorkers();
    @Autowired RouteService service;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired RecipientRepository recipients;
    @Autowired OperatorRepository operators;
    @Autowired WarehouseRepository warehouses;
    @Autowired AccountRepository accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory factory;
    @Autowired PlanningGuard planning;
    @Autowired MapsLimits limits;
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean MapsProvider maps;
    AccountPrincipal admin, driver;
    Integer operatorId, recipientId, warehouseId, deliveryId;
    static final LocalDate DATE=LocalDate.of(2026,10,13);
    static final String BASE="/api/admin/routes";
    static final String BODY="{\"scheduledDate\":\"2026-10-13\"}";
    @BeforeEach void setup() throws Exception {
        tx(() -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var warehouse=new Warehouse(); warehouse.setName("Depot"); warehouse.setAddress("Depot address"); warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO); warehouseId=warehouses.saveAndFlush(warehouse).getWarehouseId();
            var operator=new Operator(); operator.setName("Driver"); operator.setPhoneNumber("123"); operator.setEmail("generation@test.local"); operator.setWarehouse(warehouse); operatorId=operators.saveAndFlush(operator).getOperatorId();
            var recipient=new Recipient(); recipient.setFirstName("A"); recipient.setLastName("Recipient"); recipient.setAddress("Address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); recipientId=recipients.saveAndFlush(recipient).getRecipientId();
            deliveryId=delivery(DATE).getDeliveryId();
            admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("generation.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
            driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("generation.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false)));
        });
        reset(maps); limits.setMaxDeliveries(3); limits.setMaxProviderCalls(20); limits.setPlanningTimeoutMs(5000);
        when(maps.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ((PlanningGuard.Budget)call.getArgument(4)).countProviderCall(); return new DistanceResult(1,"1 min",60);
        });
    }
    void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(t -> work.run()); }
    Delivery delivery(LocalDate date) {
        var value=new Delivery(); value.setRecipientId(recipients.findById(recipientId).orElseThrow()); value.setScheduledDate(date); value.setStartTimeWindow(LocalTime.of(9,0)); value.setEndTimeWindow(LocalTime.of(12,0)); value.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); return deliveries.saveAndFlush(value);
    }
    int generate() {
        try { return mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andReturn().getResponse().getStatus(); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    void unassigned() {
        assertThat(routes.count()).isZero(); assertThat(deliveries.findAll()).allSatisfy(value -> { assertThat(value.getOperatorId()).isNull(); assertThat(value.getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING); assertThat(value.getFromPrevDistanceKm()).isNull(); });
        assertThat(operators.findAll()).allSatisfy(value -> assertThat(value.getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE));
    }
    @Test void generatesCompactCommittedSummariesForCutoffAndOverdueWorkButNotFutureWork() throws Exception {
        Integer overdue=delivery(DATE.minusDays(1)).getDeliveryId(),future=delivery(DATE.plusDays(1)).getDeliveryId();
        var result=mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.scheduledDate").value(DATE.toString())).andExpect(jsonPath("$.routes[0].stopCount").value(2)).andExpect(jsonPath("$.routes[0].finishedCount").value(0)).andExpect(jsonPath("$.routes[0].routeStatus").value("IN_PROGRESS")).andExpect(jsonPath("$.routes[0].deliveries").doesNotExist()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString()); assertThat(body.get("processingTimeMs").asLong()).isGreaterThanOrEqualTo(0); int id=body.get("routes").get(0).get("routeId").asInt();
        mvc.perform(get(BASE+"/"+id).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries.length()").value(2));
        assertThat(deliveries.findById(overdue).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
        assertThat(deliveries.findById(future).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(operators.findById(operatorId).orElseThrow().getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.ON_DELIVERY);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"scheduledDate\":null}","{\"scheduledDate\":20261013}","{\"scheduledDate\":true}","{\"scheduledDate\":\"2026-02-30\"}","{\"scheduledDate\":\"13/10/2026\"}","{\"scheduledDate\":\"0999-01-01\"}","{\"scheduledDate\":\"10000-01-01\"}","{\"scheduledDate\":\"2026-10-13T00:00:00Z\"}"})
    void rejectsMissingOrMalformedDateWithoutPlanning(String body) throws Exception {
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest()); verifyNoInteractions(maps); unassigned();
    }
    @ParameterizedTest @ValueSource(strings={"operatorId","routeId","routeStatus","deliveries","unknown"})
    void rejectsUnsupportedFieldsBeforePlanning(String field) throws Exception {
        String body=BODY.substring(0,BODY.length()-1)+",\""+field+"\":1}";
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(field)))); verifyNoInteractions(maps); unassigned();
    }
    @Test void rejectsDuplicateDateAndUnsupportedMediaType() throws Exception {
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content("{\"scheduledDate\":\"2026-10-13\",\"scheduledDate\":\"2026-10-14\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()); verifyNoInteractions(maps); unassigned();
    }
    @Test void roleSessionAndCsrfAreRequiredForGenerationAndReads() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized()); mvc.perform(get(BASE).with(user(driver))).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/generate").with(user(driver)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/generate").with(user(admin)).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        var account=accounts.findById(admin.getAccountId()).orElseThrow(); account.changeEnabled(false); accounts.saveAndFlush(account);
        mvc.perform(get(BASE).with(user(admin))).andExpect(status().isUnauthorized()); verifyNoInteractions(maps); unassigned();
    }
    @Test void passwordSetupBlocksGeneration() throws Exception {
        var account=accounts.findById(admin.getAccountId()).orElseThrow(); account.setTemporaryPassword(account.getPasswordHash(),Instant.now().plusSeconds(300)); accounts.saveAndFlush(account);
        mvc.perform(post(BASE+"/generate").with(user(new AccountPrincipal(account))).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.passwordChangeRequired").value(true)); verifyNoInteractions(maps); unassigned();
    }
    @Test void noWorkNoAvailableOperatorAndMissingWarehouseAreClearConflicts() throws Exception {
        var value=operators.findById(operatorId).orElseThrow(); value.setWarehouse(null); operators.saveAndFlush(value);
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Selected operator needs warehouse coordinates before routing."));
        value.setWarehouse(warehouses.findById(warehouseId).orElseThrow()); value.setAvailabilityStatus(Operator.AvailabilityStatus.OFF_DUTY); operators.saveAndFlush(value);
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("No available operators without active routes."));
        deliveries.deleteAll(); deliveries.flush(); mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("No unrouted pending deliveries for the requested date.")); verifyNoInteractions(maps); assertThat(routes.count()).isZero();
    }
    @Test void incompleteLegacySchedulingDataCannotProduceAnAssignment() throws Exception {
        var value=deliveries.findById(deliveryId).orElseThrow(); value.setEndTimeWindow(null); deliveries.saveAndFlush(value);
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Selected delivery needs complete scheduling and location data.")); verifyNoInteractions(maps); unassigned();
    }
    @Test void planningAdmissionAndDeliveryBoundsLeaveNoPartialState() throws Exception {
        try(var first=planning.begin(); var second=planning.begin()) {
            mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isTooManyRequests());
        }
        for(int i=0;i<3;i++) delivery(DATE);
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isPayloadTooLarge()); verifyNoInteractions(maps); unassigned();
    }
    @Test void providerCallAndTimeBudgetsReturnSafeErrorsAndPreservePendingWork() throws Exception {
        delivery(DATE); limits.setMaxProviderCalls(1);
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isUnprocessableEntity()); unassigned();
        limits.setMaxProviderCalls(20); limits.setPlanningTimeoutMs(10);
        doAnswer(call -> { Thread.sleep(20); return new DistanceResult(1,"1 min",60); }).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isGatewayTimeout()); unassigned();
    }
    @Test void providerFailureRetainsTheExistingClearlyEstimatedFallback() throws Exception {
        doThrow(new IllegalStateException("private-provider-url-and-token")).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        var result=mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isCreated()).andReturn();
        int id=json.readTree(result.getResponse().getContentAsString()).get("routes").get(0).get("routeId").asInt();
        mvc.perform(get(BASE+"/"+id).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries[0].fromPrevDurationText").value("Estimated distance; time unavailable")).andExpect(jsonPath("$.deliveries[0].fromPrevDurationSeconds").isEmpty()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-provider"))));
    }
    void barrier(CountDownLatch entered,CountDownLatch release) throws Exception {
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return new DistanceResult(1,"1 min",60); }).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
    }
    @Test void concurrentGenerationRequestsHaveOnlyOneWinningCommit() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1); barrier(entered,release);
        var first=workers.supplyAsync(this::generate); var second=workers.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
        assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        assertThat(routes.count()).isEqualTo(1); assertThat(deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.IN_PROGRESS);
    }
    @Test void changesDuringCalculationInvalidateThePlanBeforeWriting() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); barrier(entered,release);
        var pending=workers.supplyAsync(this::generate);
        try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); tx(() -> recipients.findById(recipientId).orElseThrow().setAddress("Concurrent edit")); }
        finally { release.countDown(); }
        assertThat(pending.get(5,TimeUnit.SECONDS)).isEqualTo(409); unassigned();
    }
    @Test void secondRoutePersistenceFailureRollsBackAllRoutesAndAssignmentsAndHidesSql() throws Exception {
        tx(() -> { var operator=new Operator(); operator.setName("Second"); operator.setPhoneNumber("123"); operator.setEmail("second-generation@test.local"); operator.setWarehouse(warehouses.findById(warehouseId).orElseThrow()); operators.saveAndFlush(operator); delivery(DATE); });
        var calls=new AtomicInteger(); doAnswer(call -> new DistanceResult(1,calls.incrementAndGet()==1 ? "1 min" : "private-provider-"+"x".repeat(65),60)).when(maps).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
        mvc.perform(post(BASE+"/generate").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-provider")))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL")))); unassigned();
    }
    @Test void dateFilteredDirectoryIncludesEveryStatusAndUsesScalarBoundedQueries() throws Exception {
        tx(() -> {
            for(var state:Route.RouteStatus.values()) { var route=new Route(); route.setOperator(operators.findById(operatorId).orElseThrow()); route.setScheduledDate(DATE); route.setRouteStatus(state); route.setDeliveries(new ArrayList<>()); routes.saveAndFlush(route); }
            var other=new Route(); other.setOperator(operators.findById(operatorId).orElseThrow()); other.setScheduledDate(DATE.minusDays(1)); other.setRouteStatus(Route.RouteStatus.COMPLETED); other.setDeliveries(new ArrayList<>()); routes.saveAndFlush(other);
        });
        mvc.perform(get(BASE+"?scheduledDate="+DATE+"&page=1&size=1").with(user(admin))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.routes[0].routeStatus").value("IN_PROGRESS"));
        assertThat(service.directory(0,20,DATE).routes()).extracting(RouteDirectory.Item::routeStatus).containsExactly(Route.RouteStatus.COMPLETED,Route.RouteStatus.IN_PROGRESS,Route.RouteStatus.PLANNED);
        assertThat(service.directory(0,20).totalElements()).isEqualTo(4);
        var stats=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        try { var page=service.directory(0,1,DATE); assertThat(page.routes()).hasSize(1); assertThat(stats.getEntityLoadCount()).isZero(); assertThat(stats.getCollectionFetchCount()).isZero(); assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3); }
        finally { stats.setStatisticsEnabled(false); }
        mvc.perform(get("/api/v1/scheduling-app/routes/directory?scheduledDate="+DATE).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","page=10001","size=0","size=51","page=no","scheduledDate=2026-02-30","scheduledDate=0999-01-01","scheduledDate="})
    void rejectsInvalidDirectoryFilters(String query) throws Exception { mvc.perform(get(BASE+"?"+query).with(user(admin))).andExpect(status().isBadRequest()); }
    @Test void missingRouteAndEmptyFilteredPagesAreExplicit() throws Exception {
        mvc.perform(get(BASE+"/0").with(user(admin))).andExpect(status().isBadRequest()); mvc.perform(get(BASE+"/2147483647").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(get(BASE+"?scheduledDate="+DATE+"&page=10000&size=50").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.routes").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void legacyGenerationRemainsCompatibleAndDirectInvalidDatesDoNotConsumePlanning() throws Exception {
        var invalid=RouteRequestDTO.builder().scheduledDate(null).build(); assertThatThrownBy(() -> service.createRoutes(invalid)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class); verifyNoInteractions(maps);
        mvc.perform(post("/api/v1/scheduling-app/routes").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isCreated()).andExpect(jsonPath("$.routes[0].deliveries.length()").value(1)).andExpect(jsonPath("$.processingTimeMs").exists());
    }
    @ParameterizedTest @ValueSource(strings={"all","operator"})
    void legacyServiceReadsMapStopsWithoutARequestOrCallerTransaction(String read) {
        tx(() -> delivery(DATE)); assertThat(generate()).isEqualTo(201);
        int id=service.directory(0,20).routes().getFirst().routeId();
        var expected=service.getRoute(id).orElseThrow(); clearInvocations(maps);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var result=read.equals("all") ? service.getAllRoutes() : service.getRouteByDriverId(operatorId);
        assertThat(result).hasSize(1); assertThat(result.getFirst()).usingRecursiveComparison().isEqualTo(expected);
        assertThat(result.getFirst().getDeliveries()).hasSize(2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(service.getRoute(id).orElseThrow()).usingRecursiveComparison().isEqualTo(expected);
        verifyNoInteractions(maps);
    }
    @ParameterizedTest @ValueSource(strings={"all","operator"})
    void legacyHttpReadsRetainTheirResponseAndSavedStopOrderWithOsivDisabled(String read) throws Exception {
        tx(() -> delivery(DATE)); assertThat(generate()).isEqualTo(201);
        var expected=service.getRoute(service.directory(0,20).routes().getFirst().routeId()).orElseThrow();
        var endpoint="/api/v1/scheduling-app/routes"+(read.equals("operator") ? "/operator/"+operatorId : "");
        mvc.perform(get(endpoint).with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].routeId").value(expected.getRouteId()))
                .andExpect(jsonPath("$[0].deliveries.length()").value(2))
                .andExpect(jsonPath("$[0].deliveries[0].deliveryId").value(expected.getDeliveries().get(0).getDeliveryId()))
                .andExpect(jsonPath("$[0].deliveries[1].deliveryId").value(expected.getDeliveries().get(1).getDeliveryId()));
    }

    @Test void legacyReadAccessAndEmptyResponsesRemainCompatible() throws Exception {
        var base="/api/v1/scheduling-app/routes";
        for(var endpoint:List.of(base,base+"/operator/"+operatorId)) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(endpoint).with(user(driver))).andExpect(status().isForbidden());
        }
        mvc.perform(get(base).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(base+"/operator/"+operatorId).with(user(admin))).andExpect(status().isNoContent());
        mvc.perform(get(base+"/operator/2147483647").with(user(admin))).andExpect(status().isBadRequest());
        verifyNoInteractions(maps); unassigned();
    }

}
