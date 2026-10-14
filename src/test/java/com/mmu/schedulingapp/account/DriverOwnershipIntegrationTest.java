package com.mmu.schedulingapp.account;

import tools.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.repository.OperatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("java25-test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:driver-ownership;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class DriverOwnershipIntegrationTest {
    private static final String PASSWORD = "private integration passphrase";
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String BOUNDARY_PASSWORD = "a".repeat(72);
    private static final String BOUNDARY_HASH = "{bcrypt}" + new BCryptPasswordEncoder(12).encode(BOUNDARY_PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired OperatorRepository operators;
    @Autowired com.mmu.schedulingapp.repository.WarehouseRepository warehouses;
    @Autowired JdbcTemplate jdbc;

    @Autowired com.mmu.schedulingapp.repository.DeliveryRepository deliveries;
    @Autowired com.mmu.schedulingapp.repository.RouteRepository routes;
    @Autowired com.mmu.schedulingapp.repository.RecipientRepository recipients;
    @Autowired com.mmu.schedulingapp.driver.DriverAssignmentService assignments;
    private Integer deliveryA, deliveryB, routeA, routeB, operatorA, operatorB;
    private Long accountA;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @BeforeEach void fixtures() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(transaction -> createFixtures());
    }
    void createFixtures() {
        routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
        accounts.saveAndFlush(new UserAccount("test.admin", HASH, AccountRole.ADMIN, null, false));
        var first = operator("First", "first@example.test");
        var second = operator("Second", "second@example.test");
        operatorA=first.getOperatorId(); operatorB=second.getOperatorId();
        accountA=accounts.saveAndFlush(new UserAccount("test.driver",HASH,AccountRole.DRIVER,first,false)).getId();
        accounts.saveAndFlush(new UserAccount("other.driver",HASH,AccountRole.DRIVER,second,false));
        var a=delivery(first,"Alice"); var b=delivery(second,"Bob");
        deliveryA=a.getDeliveryId(); deliveryB=b.getDeliveryId();
        routeA=route(first,a).getRouteId(); routeB=route(second,b).getRouteId();
    }
    Operator operator(String name,String email) {
        var operator=new Operator(); operator.setName(name); operator.setEmail(email); operator.setPhoneNumber("123");
        return operators.saveAndFlush(operator);
    }
    com.mmu.schedulingapp.model.Delivery delivery(Operator operator,String name) {
        var recipient=new com.mmu.schedulingapp.model.Recipient();
        recipient.setFirstName(name); recipient.setLastName("Recipient"); recipient.setAddress(name+" address");
        recipient.setLatitude(java.math.BigDecimal.ZERO); recipient.setLongitude(java.math.BigDecimal.ZERO); recipient.setContactNumber("123");
        recipients.saveAndFlush(recipient);
        var delivery=new com.mmu.schedulingapp.model.Delivery(); delivery.setRecipientId(recipient); delivery.setOperatorId(operator);
        delivery.setPriorityLevel(com.mmu.schedulingapp.model.Delivery.PriorityLevel.MEDIUM);
        delivery.setDeliveryStatus(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
        return deliveries.saveAndFlush(delivery);
    }
    com.mmu.schedulingapp.model.Route route(Operator operator,com.mmu.schedulingapp.model.Delivery delivery) {
        var route=new com.mmu.schedulingapp.model.Route(); route.setOperator(operator); route.setScheduledDate(java.time.LocalDate.now());
        route.setRouteStatus(com.mmu.schedulingapp.model.Route.RouteStatus.IN_PROGRESS);
        route.setDeliveries(new java.util.ArrayList<>(java.util.List.of(delivery)));
        return routes.saveAndFlush(route);
    }

    record Token(MockHttpSession session, String header, String value) { }
    Token token(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        return new Token((MockHttpSession) result.getRequest().getSession(), body.get("headerName").asText(), body.get("token").asText());
    }
    MvcResult attempt(String username, String password) throws Exception {
        var token = token(null);
        return mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(),token.value())
                .param("username",username).param("password",password)).andReturn();
    }
    MockHttpSession login(String username) throws Exception {
        var result = attempt(username,PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) result.getRequest().getSession();
    }

    @Test void administratorLifecycleApiRejectsDeletionAndInvalidTransitions() throws Exception {
        var session=login("test.admin"); var csrf=token(session);
        mvc.perform(delete("/api/v1/scheduling-app/routes/"+routeA).session(session).header(csrf.header(),csrf.value())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/scheduling-app/routes").session(session).header(csrf.header(),csrf.value())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/scheduling-app/deliveries/"+deliveryA).session(session).header(csrf.header(),csrf.value())).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/scheduling-app/deliveries/"+deliveryA).session(session).header(csrf.header(),csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"IN_PROGRESS\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/scheduling-app/deliveries/"+deliveryA).session(session).header(csrf.header(),csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"COMPLETED\"}")).andExpect(status().isOk());
        assertThat(routes.findById(routeA).orElseThrow().getRouteStatus()).isEqualTo(com.mmu.schedulingapp.model.Route.RouteStatus.COMPLETED);
        mvc.perform(patch("/api/v1/scheduling-app/deliveries/"+deliveryA).session(session).header(csrf.header(),csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"CANCELLED\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/scheduling-app/deliveries/2147483647").session(session).header(csrf.header(),csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"COMPLETED\"}")).andExpect(status().isNotFound());
    }

    @Test void driverSeesOnlyOwnRoutesDeliveryAndNecessaryRecipientData() throws Exception {
        var session=login("test.driver");
        mvc.perform(get("/api/driver/routes").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].routeId").value(routeA))
                .andExpect(jsonPath("$[0].deliveries[0].clientName").value("Alice Recipient"));
        mvc.perform(get("/api/driver/routes/"+routeA).session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/driver/deliveries/"+deliveryA).session(session)).andExpect(status().isOk());
        var result=mvc.perform(get("/api/driver/deliveries/"+deliveryA+"/recipient").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Alice address")).andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist()).andExpect(jsonPath("$.recipientId").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Bob", "password", "account");
    }
    @Test void foreignAndMissingObjectsAreIndistinguishableAndNeverUpdated() throws Exception {
        var session=login("test.driver"); var token=token(session);
        for (String path:java.util.List.of("/routes/"+routeB,"/deliveries/"+deliveryB,"/deliveries/"+deliveryB+"/recipient","/routes/999999","/deliveries/999999/recipient")) {
            mvc.perform(get("/api/driver"+path).session(session)).andExpect(status().isNotFound());
        }
        mvc.perform(patch("/api/driver/deliveries/"+deliveryB+"/status").session(session).header(token.header(),token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"COMPLETED\"}")).andExpect(status().isNotFound());
        assertThat(deliveries.findById(deliveryB).orElseThrow().getDeliveryStatus()).isEqualTo(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
        mvc.perform(get("/api/driver/routes").session(login("other.driver"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].routeId").value(routeB));
    }
    @Test void alternateIdsAndMalformedStatusBodiesDoNotBroadenAccess() throws Exception {
        var session=login("test.driver"); var token=token(session);
        mvc.perform(get("/api/driver/routes/00"+routeB).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/driver/routes").param("operatorId",String.valueOf(operatorB)).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].routeId").value(routeA));
        for(String body:java.util.List.of("{", "{}", "{\"deliveryStatus\":2}", "{\"deliveryStatus\":\"completed\"}",
                "{\"deliveryStatus\":\"COMPLETED\",\"deliveryStatus\":\"PENDING\"}")) {
            mvc.perform(patch("/api/driver/deliveries/"+deliveryA+"/status").session(session).header(token.header(),token.value())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(deliveries.findById(deliveryA).orElseThrow().getDeliveryStatus()).isEqualTo(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
    }
    @Test void anonymousAndAdministratorCannotUseDriverEndpoints() throws Exception {
        mvc.perform(get("/api/driver/routes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/driver/routes").session(login("test.admin"))).andExpect(status().isForbidden());
    }
    @Test void missingCsrfAndExtraUpdateFieldsAreRejected() throws Exception {
        var session=login("test.driver"); var token=token(session);
        mvc.perform(patch("/api/driver/deliveries/"+deliveryA+"/status").session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"COMPLETED\"}")).andExpect(status().isForbidden());
        for (String extra:java.util.List.of("operatorId","recipientId","scheduledDate","specialInstructions")) {
            mvc.perform(patch("/api/driver/deliveries/"+deliveryA+"/status").session(session).header(token.header(),token.value())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\"COMPLETED\",\""+extra+"\":1}")).andExpect(status().isBadRequest());
        }
        assertThat(deliveries.findById(deliveryA).orElseThrow().getDeliveryStatus()).isEqualTo(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> transitions() {
        return java.util.Arrays.stream(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.values()).flatMap(from ->
                java.util.Arrays.stream(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.values()).map(to -> org.junit.jupiter.params.provider.Arguments.of(from,to)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("transitions")
    void driverStatusMatrix(com.mmu.schedulingapp.model.Delivery.DeliveryStatus from,com.mmu.schedulingapp.model.Delivery.DeliveryStatus to) throws Exception {
        jdbc.update("UPDATE deliveries SET delivery_status=? WHERE delivery_id=?",from.name(),deliveryA);
        var session=login("test.driver"); var token=token(session);
        boolean terminalTarget=to==com.mmu.schedulingapp.model.Delivery.DeliveryStatus.COMPLETED || to==com.mmu.schedulingapp.model.Delivery.DeliveryStatus.CANCELLED;
        int expected=!terminalTarget ? 400 : from==com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS ? 200 : 409;
        mvc.perform(patch("/api/driver/deliveries/"+deliveryA+"/status").session(session).header(token.header(),token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryStatus\":\""+to.name()+"\"}")).andExpect(status().is(expected));
        assertThat(deliveries.findById(deliveryA).orElseThrow().getDeliveryStatus()).isEqualTo(expected==200?to:from);
    }
    @Test void conflictingRouteOrUnassignedDeliveryIsHidden() throws Exception {
        var session=login("test.driver");
        jdbc.update("UPDATE deliveries SET operator_id=? WHERE delivery_id=?",operatorB,deliveryA);
        mvc.perform(get("/api/driver/routes").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/driver/routes/"+routeA).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/driver/deliveries/"+deliveryA+"/recipient").session(session)).andExpect(status().isNotFound());
        jdbc.update("UPDATE deliveries SET operator_id=NULL WHERE delivery_id=?",deliveryA);
        mvc.perform(get("/api/driver/deliveries/"+deliveryA).session(session)).andExpect(status().isNotFound());
    }
    @Test void oneContradictoryMemberHidesWholeRouteIncludingOtherDelivery() throws Exception {
        var another=delivery(operators.findById(operatorB).orElseThrow(),"Foreign");
        jdbc.update("INSERT INTO route_deliveries(route_id,delivery_id,delivery_order) VALUES (?,?,1)",routeA,another.getDeliveryId());
        var session=login("test.driver");
        mvc.perform(get("/api/driver/routes").session(session)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/driver/deliveries/"+deliveryA).session(session)).andExpect(status().isNotFound());
    }
    @Test void orphanedRouteMembershipAndEmptyAssignmentsAreHandled() throws Exception {
        jdbc.update("DELETE FROM route_deliveries WHERE delivery_id=?",deliveryA);
        mvc.perform(get("/api/driver/deliveries/"+deliveryA).session(login("test.driver"))).andExpect(status().isNotFound());
        routes.deleteById(routeA);
        mvc.perform(get("/api/driver/routes").session(login("test.driver"))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void operatorRelinkUsesFreshPersistedLinkForExistingSession() throws Exception {
        var session=login("test.driver");
        accounts.delete(accounts.findByUsername("other.driver").orElseThrow()); accounts.flush();
        jdbc.update("UPDATE user_accounts SET operator_id=? WHERE id=?",operatorB,accountA);
        mvc.perform(get("/api/driver/routes").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].routeId").value(routeB));
        mvc.perform(get("/api/driver/deliveries/"+deliveryA).session(session)).andExpect(status().isNotFound());
    }
    @Test void simultaneousTerminalUpdatesHaveExactlyOneWinner() throws Exception {
        var start=new java.util.concurrent.CountDownLatch(1);
        var first=java.util.concurrent.CompletableFuture.supplyAsync(() -> updateAfter(start,"COMPLETED"));
        var second=java.util.concurrent.CompletableFuture.supplyAsync(() -> updateAfter(start,"CANCELLED"));
        start.countDown();
        var results=java.util.List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
        assertThat(results).containsExactlyInAnyOrder(200,409);
    }
    int updateAfter(java.util.concurrent.CountDownLatch start,String status) {
        try { start.await(); assignments.status(accountA,0L,deliveryA,status); return 200; }
        catch(org.springframework.web.server.ResponseStatusException failure) { return failure.getStatusCode().value(); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
    }
    @Test void pendingAdministratorReassignmentCannotBeOverwrittenByDriver() throws Exception {
        try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:driver-ownership","sa","")) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("UPDATE deliveries SET operator_id=? WHERE delivery_id=?")) { statement.setInt(1,operatorB); statement.setInt(2,deliveryA); statement.executeUpdate(); }
            var started=new java.util.concurrent.CountDownLatch(1);
            var result=java.util.concurrent.CompletableFuture.supplyAsync(() -> { started.countDown(); return updateAfter(new java.util.concurrent.CountDownLatch(0),"COMPLETED"); });
            started.await();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.get(200,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            connection.commit();
            assertThat(result.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(deliveries.findById(deliveryA).orElseThrow().getOperatorId().getOperatorId()).isEqualTo(operatorB);
            assertThat(deliveries.findById(deliveryA).orElseThrow().getDeliveryStatus()).isEqualTo(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
        }
    }
}
