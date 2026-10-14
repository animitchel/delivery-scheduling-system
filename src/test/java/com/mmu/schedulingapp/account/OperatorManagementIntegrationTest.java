package com.mmu.schedulingapp.account;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:operator-management;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
class OperatorManagementIntegrationTest {
    @Autowired OperatorRepository operators;
    @Autowired WarehouseRepository warehouses;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired RecipientRepository recipients;
    @Autowired AccountRepository accounts;
    @Autowired OperatorService service;
    @Autowired MockMvc mvc;
    AccountPrincipal admin,driver;
    Integer id,warehouseId;
    static final String BASE="/api/admin/operators";
    @BeforeEach void fixtures() {
        routes.deleteAll();routes.flush();deliveries.deleteAll();deliveries.flush();accounts.deleteAll();accounts.flush();operators.deleteAll();operators.flush();recipients.deleteAll();recipients.flush();warehouses.deleteAll();warehouses.flush();
        var warehouse=new Warehouse();warehouse.setName("Depot");warehouse.setAddress("Depot address");warehouse.setLatitude(BigDecimal.ZERO);warehouse.setLongitude(BigDecimal.ZERO);warehouseId=warehouses.saveAndFlush(warehouse).getWarehouseId();
        var operator=new Operator();operator.setName("Original");operator.setPhoneNumber("+447123456789");operator.setEmail("original@example.test");operator.setVehicleType("Van");operator.setMaxCapacity(25.0);operator.setWarehouse(warehouse);id=operators.saveAndFlush(operator).getOperatorId();
        admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("operator.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
        var driverOperator=new Operator();driverOperator.setName("Other driver");driverOperator.setPhoneNumber("+447123456780");driverOperator.setEmail("other@example.test");operators.saveAndFlush(driverOperator);
        driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("operator.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,driverOperator,false)));
    }
    String body() { return "{\"name\":\"Edited\",\"phoneNumber\":\"+447123456789\",\"email\":\"original@example.test\",\"vehicleType\":\"Van\",\"maxCapacity\":25,\"warehouseId\":"+warehouseId+",\"availabilityStatus\":\"AVAILABLE\"}"; }
    String etag() { return service.snapshot(id).orElseThrow().etag(); }
    void putBody(String body,int expected) throws Exception { mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag()).contentType("application/json").content(body)).andExpect(status().is(expected)); }
    @Test void createReadUpdateAndConditionalDeleteUseScalarContract() throws Exception {
        var created=mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body().replace("original@example.test","new@example.test"))).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.warehouseId").value(warehouseId)).andReturn();
        mvc.perform(get(created.getResponse().getHeader("Location")).with(user(admin))).andExpect(status().isOk()).andExpect(header().string("ETag",created.getResponse().getHeader("ETag")));
        String before=etag();putBody(body(),200);
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",before)).andExpect(status().isPreconditionFailed());
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag())).andExpect(status().isNoContent());assertThat(operators.existsById(id)).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"operatorId","warehouseName","password","routes"})
    void unknownFieldsFailWithoutWrites(String field) throws Exception { mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body().replace("{","{\""+field+"\":\"private-value\","))).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-value"))));assertThat(operators.count()).isEqualTo(2); }
    @ParameterizedTest @ValueSource(strings={"-1","null","\"25\"","true","1e999"})
    void invalidCapacityCannotBePersisted(String value) throws Exception { putBody(body().replace("\"maxCapacity\":25","\"maxCapacity\":"+value),400); }
    @Test void duplicateKeysTypesAndFieldLimitsAreRejected() throws Exception {
        for(String body:List.of(body().replace("\"name\":\"Edited\"","\"name\":\"Edited\",\"name\":\"Other\""),body().replace("\"Edited\"","3"),body().replace("Edited","x".repeat(101)),body().replace("original@example.test","invalid-email"),body().replace("+447123456789","123"),body().replace("\"warehouseId\":"+warehouseId,"\"warehouseId\":1.5"),body().replace("Van","x".repeat(51)))) putBody(body,400);
    }
    @Test void directoryIsBoundedAndEscapesSearchWildcards() throws Exception {
        var operator=operators.findById(id).orElseThrow();operator.setName("Literal_%!");operators.saveAndFlush(operator);
        mvc.perform(get(BASE).param("size","1").param("search","_%!").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.operators.length()").value(1)).andExpect(jsonPath("$.operators[0].operatorId").value(id)).andExpect(jsonPath("$.operators[0].password").doesNotExist());
        for(String query:List.of("?page=-1","?page=10001","?page=bad","?size=51","?size=0","?search="+"x".repeat(101))) mvc.perform(get(BASE+query).with(user(admin))).andExpect(status().isBadRequest());
    }
    @Test void requiredAndMalformedPreconditionsCannotWrite() throws Exception {
        mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(body())).andExpect(status().isPreconditionRequired());
        for(String tag:List.of("*","W/"+etag(),etag()+","+etag())) mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",tag)).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf())).andExpect(status().isPreconditionRequired());
    }
    @Test void duplicateEmailAndUnavailableWarehousesReturnSafeFailures() throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body())).andExpect(status().isConflict());
        putBody(body().replace("\"warehouseId\":"+warehouseId,"\"warehouseId\":2147483647"),400);
    }
    @ParameterizedTest @ValueSource(strings={"PLANNED","IN_PROGRESS"})
    void assignedRoutesBlockOperationalChangesButAllowContactEdits(String status) throws Exception {
        var operator=operators.findById(id).orElseThrow();operator.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY);operators.saveAndFlush(operator);
        var route=new Route();route.setOperator(operator);route.setScheduledDate(LocalDate.now());route.setRouteStatus(Route.RouteStatus.valueOf(status));route.setDeliveries(new ArrayList<>());routes.saveAndFlush(route);
        String active=body().replace("AVAILABLE","ON_DELIVERY");putBody(active,200);
        for(String changed:List.of(active.replace("ON_DELIVERY","AVAILABLE"),active.replace("Van","Bike"),active.replace("\"maxCapacity\":25","\"maxCapacity\":26"))) putBody(changed,409);
        mvc.perform(patch("/api/v1/scheduling-app/operators/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"availabilityStatus\":\"AVAILABLE\"}")).andExpect(status().isConflict());
    }
    @Test void onDeliveryCannotBeManuallyIntroducedOrCleared() throws Exception {
        putBody(body().replace("AVAILABLE","ON_DELIVERY"),409);
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body().replace("AVAILABLE","ON_DELIVERY"))).andExpect(status().isConflict());
        var operator=operators.findById(id).orElseThrow();operator.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY);operators.saveAndFlush(operator);putBody(body(),409);
    }
    @Test void accountsAndCompletedRouteHistoryProtectDeletion() throws Exception {
        var operator=operators.findById(id).orElseThrow();var route=new Route();route.setOperator(operator);route.setScheduledDate(LocalDate.now());route.setRouteStatus(Route.RouteStatus.COMPLETED);route.setDeliveries(new ArrayList<>());routes.saveAndFlush(route);
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag())).andExpect(status().isConflict());routes.deleteAll();routes.flush();
        accounts.saveAndFlush(new UserAccount("linked.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false));
        mvc.perform(delete("/api/v1/scheduling-app/operators/"+id).with(user(admin)).with(csrf())).andExpect(status().isConflict());assertThat(operators.existsById(id)).isTrue();
    }
    @Test void legacyCreationRejectsSelectedIdsAndPatchSharesValidation() throws Exception {
        String legacy=body().replace("\"warehouseId\":"+warehouseId,"\"warehouseName\":\"Depot\"");
        mvc.perform(post("/api/v1/scheduling-app/operators").with(user(admin)).with(csrf()).contentType("application/json").content(legacy.replace("{","{\"operatorId\":"+id+","))).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/scheduling-app/operators/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"maxCapacity\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/scheduling-app/operators/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"name\":\"Safe edit\"}")).andExpect(status().isOk());
    }
    @Test void roleSessionCsrfAndPasswordSetupGuardsRemainEnforced() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());mvc.perform(get(BASE).with(user(driver))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(user(admin)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(delete(BASE+"/"+id).with(user(driver)).with(csrf()).header("If-Match",etag())).andExpect(status().isForbidden());
        var account=accounts.findByUsername(admin.getUsername()).orElseThrow();account.changeEnabled(false);accounts.saveAndFlush(account);mvc.perform(get(BASE).with(user(admin))).andExpect(status().isUnauthorized());
        account.changeEnabled(true);account.setTemporaryPassword(account.getPasswordHash(),java.time.Instant.now().plusSeconds(3600));accounts.saveAndFlush(account);mvc.perform(get(BASE).with(user(new AccountPrincipal(account)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }
    @Test void orphanUnfinishedDeliveriesBlockOperationalEditsAndTerminalHistoryBlocksDeletion() throws Exception {
        var recipient=new Recipient();recipient.setFirstName("Test");recipient.setLastName("Recipient");recipient.setAddress("Address");recipient.setLatitude(BigDecimal.ZERO);recipient.setLongitude(BigDecimal.ZERO);recipients.saveAndFlush(recipient);
        var delivery=new Delivery();delivery.setRecipientId(recipient);delivery.setOperatorId(operators.findById(id).orElseThrow());delivery.setScheduledDate(LocalDate.now());delivery.setStartTimeWindow(java.time.LocalTime.MIN);delivery.setEndTimeWindow(java.time.LocalTime.MAX);delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);delivery.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS);deliveries.saveAndFlush(delivery);
        putBody(body().replace("AVAILABLE","OFF_DUTY"),409);
        delivery.setDeliveryStatus(Delivery.DeliveryStatus.COMPLETED);deliveries.saveAndFlush(delivery);
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag())).andExpect(status().isConflict());assertThat(deliveries.existsById(delivery.getDeliveryId())).isTrue();
    }
    @Test void activeWarehouseTransferAndAmbiguousLegacyWarehouseNamesAreBlocked() throws Exception {
        var other=new Warehouse();other.setName("Depot");other.setAddress("Other address");other.setLatitude(BigDecimal.ONE);other.setLongitude(BigDecimal.ONE);int otherId=warehouses.saveAndFlush(other).getWarehouseId();
        mvc.perform(patch("/api/v1/scheduling-app/operators/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"warehouseName\":\"Depot\"}")).andExpect(status().isConflict());
        var route=new Route();route.setOperator(operators.findById(id).orElseThrow());route.setScheduledDate(LocalDate.now());route.setRouteStatus(Route.RouteStatus.PLANNED);route.setDeliveries(new ArrayList<>());routes.saveAndFlush(route);
        putBody(body().replace("\"warehouseId\":"+warehouseId,"\"warehouseId\":"+otherId),409);
    }
    @Test void invalidIDsMissingRecordsAndUnsupportedContentTypesKeepTheirStatus() throws Exception {
        mvc.perform(get(BASE+"/0").with(user(admin))).andExpect(status().isBadRequest());mvc.perform(get(BASE+"/2147483647").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("text/plain").content(body())).andExpect(status().isUnsupportedMediaType());
        putBody(body().replace("AVAILABLE","UNKNOWN"),400);
    }

}
