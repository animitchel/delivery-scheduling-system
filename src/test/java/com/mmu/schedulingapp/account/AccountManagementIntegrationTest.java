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
        "spring.datasource.url=jdbc:h2:mem:account-management;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class AccountManagementIntegrationTest {
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter handlerAdapter;

    @Test
    void mvcUsesJackson3ForStrictWriteRequests() {
        var converters = handlerAdapter.getMessageConverters();
        assertThat(converters).anyMatch(converter ->
                converter instanceof org.springframework.http.converter.json.JacksonJsonHttpMessageConverter);
        for (var requestType : java.util.List.of(
                com.mmu.schedulingapp.dto.delivery.DeliveryCreateRequest.class,
                com.mmu.schedulingapp.dto.route.RouteGenerationRequest.class,
                com.mmu.schedulingapp.dto.warehouse.WarehouseWriteRequest.class,
                com.mmu.schedulingapp.dto.operator.OperatorWriteRequest.class,
                com.mmu.schedulingapp.dto.recipient.RecipientWriteRequest.class)) {
            var selected = converters.stream().filter(converter -> converter.canRead(requestType, MediaType.APPLICATION_JSON))
                    .findFirst().orElseThrow();
            assertThat(selected).isInstanceOf(org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class);
            assertThat(converters.stream().filter(converter -> converter.canRead(requestType, MediaType.APPLICATION_JSON)))
                    .noneMatch(converter -> converter.getClass().getName().contains("MappingJackson2"));
        }
    }

    @Test
    void jsonPreservesIsoDatesTimesAndExactCoordinateDecimals() {
        var warehouse = json.readValue("{\"name\":\"Warehouse\",\"address\":\"Saved address\",\"latitude\":53.123456,\"longitude\":-2.654321}",
                com.mmu.schedulingapp.dto.warehouse.WarehouseWriteRequest.class);
        assertThat(warehouse.getLatitude()).isEqualByComparingTo("53.123456");
        assertThat(warehouse.getLongitude()).isEqualByComparingTo("-2.654321");
        var encoded = json.readTree(json.writeValueAsString(java.util.Map.of(
                "date", java.time.LocalDate.of(2026, 10, 14),
                "time", java.time.LocalTime.of(9, 30, 15),
                "instant", java.time.Instant.parse("2026-10-14T09:30:15Z"),
                "latitude", warehouse.getLatitude())));
        assertThat(encoded.get("date").asString()).isEqualTo("2026-10-14");
        assertThat(encoded.get("time").asString()).isEqualTo("09:30:15");
        assertThat(encoded.get("instant").asString()).isEqualTo("2026-10-14T09:30:15Z");
        assertThat(encoded.get("latitude").decimalValue()).isEqualByComparingTo("53.123456");
    }

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

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AccountAuditRepository audits;
    @Autowired AccountManagementService management;
    @Autowired com.mmu.schedulingapp.repository.RouteRepository routes;
    @Autowired com.mmu.schedulingapp.repository.DeliveryRepository deliveries;
    @Autowired com.mmu.schedulingapp.repository.RecipientRepository recipients;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    Integer assignedDelivery;
    @BeforeEach void fixtures() {
        routes.deleteAll(); deliveries.deleteAll(); recipients.deleteAll();
        audits.deleteAll();
        accounts.deleteAll();
        operators.deleteAll();
        warehouses.deleteAll();
        accounts.saveAndFlush(new UserAccount("test.admin", HASH, AccountRole.ADMIN, null, false));
        var operator = new Operator();
        operator.setName("Driver"); operator.setPhoneNumber("123"); operator.setEmail("security@example.test");
        operators.saveAndFlush(operator);
        accounts.saveAndFlush(new UserAccount("test.driver", HASH, AccountRole.DRIVER, operator, false));
        accounts.saveAndFlush(new UserAccount("test.disabled", HASH, AccountRole.ADMIN, null, false));
        jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE username='test.disabled'");
        var spare=new Operator(); spare.setName("Spare"); spare.setPhoneNumber("123"); spare.setEmail("spare@example.test"); operators.saveAndFlush(spare);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            var recipient=new com.mmu.schedulingapp.model.Recipient(); recipient.setFirstName("First"); recipient.setLastName("Recipient"); recipient.setAddress("Address"); recipient.setLatitude(java.math.BigDecimal.ZERO); recipient.setLongitude(java.math.BigDecimal.ZERO); recipients.saveAndFlush(recipient);
            var delivery=new com.mmu.schedulingapp.model.Delivery(); delivery.setOperatorId(operators.findById(operator.getOperatorId()).orElseThrow()); delivery.setRecipientId(recipient); delivery.setPriorityLevel(com.mmu.schedulingapp.model.Delivery.PriorityLevel.MEDIUM); delivery.setDeliveryStatus(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS); deliveries.saveAndFlush(delivery); assignedDelivery=delivery.getDeliveryId();
            var route=new com.mmu.schedulingapp.model.Route(); route.setOperator(delivery.getOperatorId()); route.setScheduledDate(java.time.LocalDate.now()); route.setDeliveries(new java.util.ArrayList<>(java.util.List.of(delivery))); routes.saveAndFlush(route);
        });
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

    Long driverId() { return accounts.findByUsername("test.driver").orElseThrow().getId(); }
    Integer spareOperator() { return operators.findAll().stream().filter(operator -> operator.getName().equals("Spare")).findFirst().orElseThrow().getOperatorId(); }
    MvcResult mutate(MockHttpSession session,String path,org.springframework.http.HttpMethod method,java.util.Map<String,Object> body,int expected) throws Exception {
        var csrf=token(session);
        return mvc.perform(request(method,path).session(session).header(csrf.header(),csrf.value())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn();
    }
    MvcResult create(MockHttpSession session,String username,Integer operator,int expected) throws Exception {
        return mutate(session,"/api/admin/accounts/drivers",org.springframework.http.HttpMethod.POST,
                java.util.Map.of("username",username,"operatorId",operator,"currentPassword",PASSWORD),expected);
    }
    String reset(MockHttpSession session) throws Exception {
        var result=mutate(session,"/api/admin/accounts/"+driverId()+"/reset-password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",PASSWORD),200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return json.readTree(result.getResponse().getContentAsString()).get("temporaryPassword").asText();
    }
    @Test void eligibleOperatorsExcludeEveryLinkedAccountEvenWhenDisabledOrOffPage() throws Exception {
        var session=login("test.admin");
        jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE username='test.driver'");
        for(int index=0;index<20;index++) accounts.saveAndFlush(new UserAccount("paging.admin"+index,HASH,AccountRole.ADMIN,null,false));
        var late=new Operator(); late.setName("Late linked operator"); late.setPhoneNumber("123"); late.setEmail("late@example.test"); operators.saveAndFlush(late);
        accounts.saveAndFlush(new UserAccount("late.driver",HASH,AccountRole.DRIVER,late,false));
        mvc.perform(get("/api/admin/accounts").param("size","20").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(20)).andExpect(jsonPath("$.totalElements").value(24));
        var result=mvc.perform(get("/api/admin/accounts/eligible-operators").param("size","1").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operators.length()").value(1))
                .andExpect(jsonPath("$.operators[0].operatorId").value(spareOperator())).andExpect(jsonPath("$.totalElements").value(1)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password", "driver@example.test");
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("search","SpArE").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("search","Driver").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operators").isEmpty());
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("page","1").param("size","1").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operators").isEmpty());
    }
    @Test void eligibleOperatorsAndAccountsAssetsRequireAdministratorAndValidBounds() throws Exception {
        mvc.perform(get("/api/admin/accounts/eligible-operators")).andExpect(status().isUnauthorized());
        var driver=login("test.driver");
        mvc.perform(get("/api/admin/accounts/eligible-operators").session(driver)).andExpect(status().isForbidden());
        mvc.perform(get("/html/accounts.html").session(driver)).andExpect(status().isForbidden());
        mvc.perform(get("/js/accounts.js").session(driver)).andExpect(status().isForbidden());
        mvc.perform(get("/css/accounts.css").session(driver)).andExpect(status().isForbidden());
        mvc.perform(get("/html/accounts.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/app/login"));
        var admin=login("test.admin");
        mvc.perform(get("/html/accounts.html").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/js/accounts.js").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/css/accounts.css").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("size","101").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("page","-1").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/accounts/eligible-operators").param("search","a".repeat(101)).session(admin)).andExpect(status().isBadRequest());
    }
    @Test void listIsBoundedAndContainsNoCredentialState() throws Exception {
        var session=login("test.admin");
        var result=mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(3)).andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(HASH,PASSWORD,"passwordHash","authRevision","temporaryPassword");
        mvc.perform(get("/api/admin/accounts").param("size","101").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/accounts").param("page","-1").session(session)).andExpect(status().isBadRequest());
    }
    @Test void administratorsCanCreateOnlyTemporaryDriverAccounts() throws Exception {
        var session=login("test.admin");
        var result=create(session," NEW.DRIVER ",spareOperator(),201);
        var response=json.readTree(result.getResponse().getContentAsString());
        String temporary=response.get("temporaryPassword").asText();
        assertThat(temporary).hasSize(32); assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        var account=accounts.findByUsername("new.driver").orElseThrow();
        assertThat(account.getRole()).isEqualTo(AccountRole.DRIVER); assertThat(account.isPasswordChangeRequired()).isTrue();
        assertThat(account.getAuthenticationRevision()).isEqualTo(1);
        assertThat(account.getTemporaryPasswordExpiresAt()).isAfter(java.time.Instant.now().plusSeconds(86000));
        assertThat(account.getPasswordHash()).doesNotContain(temporary);
        assertThat(attempt("new.driver",temporary).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits.findAll()).hasSize(1);
        assertThat(audits.findAll().getFirst().getAction()).isEqualTo("DRIVER_CREATED");
    }
    @Test void anonymousDriversAndCsrfFreeMutationsAreDenied() throws Exception {
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/accounts").session(login("test.driver"))).andExpect(status().isForbidden());
        create(login("test.driver"),"new.driver",spareOperator(),403);
        mvc.perform(post("/api/admin/accounts/drivers").session(login("test.admin")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void wrongAdministratorPasswordAndImmutableFieldsAreDenied() throws Exception {
        var session=login("test.admin");
        mutate(session,"/api/admin/accounts/drivers",org.springframework.http.HttpMethod.POST,
                java.util.Map.of("username","new.driver","operatorId",spareOperator(),"currentPassword","incorrect long password"),403);
        for(String field:java.util.List.of("role","enabled","passwordHash","authRevision","bootstrapSlot")) {
            mutate(session,"/api/admin/accounts/drivers",org.springframework.http.HttpMethod.POST,
                    java.util.Map.of("username","new.driver","operatorId",spareOperator(),"currentPassword",PASSWORD,field,"ADMIN"),400);
        }
        assertThat(accounts.count()).isEqualTo(3); assertThat(audits.count()).isZero();
    }
    @Test void duplicateUsernameAndOperatorCannotCreateExtraAccounts() throws Exception {
        var session=login("test.admin");
        create(session,"test.driver",spareOperator(),409);
        create(session,"new.driver",accounts.findByUsername("test.driver").orElseThrow().getOperator().getOperatorId(),409);
        assertThat(accounts.count()).isEqualTo(3); assertThat(audits.count()).isZero();
    }
    @Test void everyManagementMutationRejectsAdministratorTargets() throws Exception {
        var session=login("test.admin"); Long id=accounts.findByUsername("test.admin").orElseThrow().getId();
        mutate(session,"/api/admin/accounts/"+id+"/enabled",org.springframework.http.HttpMethod.PATCH,java.util.Map.of("enabled",false,"currentPassword",PASSWORD),404);
        mutate(session,"/api/admin/accounts/"+id+"/reset-password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",PASSWORD),404);
        assertThat(accounts.findById(id).orElseThrow().isEnabled()).isTrue(); assertThat(audits.count()).isZero();
    }
    @Test void disableThenEnablePermanentlyRevokesBothOldSessions() throws Exception {
        var admin=login("test.admin"); var one=login("test.driver"); var two=login("test.driver");
        mutate(admin,"/api/admin/accounts/"+driverId()+"/enabled",org.springframework.http.HttpMethod.PATCH,java.util.Map.of("enabled",false,"currentPassword",PASSWORD),200);
        assertThat(attempt("test.driver",PASSWORD).getResponse().getStatus()).isEqualTo(401);
        mutate(admin,"/api/admin/accounts/"+driverId()+"/enabled",org.springframework.http.HttpMethod.PATCH,java.util.Map.of("enabled",true,"currentPassword",PASSWORD),200);
        assertThat(accounts.findById(driverId()).orElseThrow().getAuthenticationRevision()).isEqualTo(2);
        mvc.perform(get("/api/auth/me").session(one)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/driver/routes").session(two)).andExpect(status().isUnauthorized());
        assertThat(attempt("test.driver",PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(audits.count()).isEqualTo(2);
    }
    @Test void resetRevokesOldSessionsAndRestrictsTemporaryLogin() throws Exception {
        var old=login("test.driver"); String temporary=reset(login("test.admin"));
        mvc.perform(get("/api/auth/me").session(old)).andExpect(status().isUnauthorized());
        assertThat(attempt("test.driver",PASSWORD).getResponse().getStatus()).isEqualTo(401);
        var logged=attempt("test.driver",temporary); assertThat(logged.getResponse().getStatus()).isEqualTo(200);
        var session=(MockHttpSession)logged.getRequest().getSession();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        mvc.perform(get("/api/driver/routes").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/html/operators.html").session(session)).andExpect(status().isFound()).andExpect(redirectedUrl("/html/change-password.html"));
        mvc.perform(get("/html/change-password.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isForbidden());
        var token=token(session);
        mvc.perform(post("/api/auth/logout").session(session).header(token.header(),token.value())).andExpect(status().isNoContent());
    }
    @Test void expiredTemporaryCredentialRejectsLoginAndExistingSession() throws Exception {
        String temporary=reset(login("test.admin")); var logged=attempt("test.driver",temporary);
        var session=(MockHttpSession)logged.getRequest().getSession();
        jdbc.update("UPDATE user_accounts SET temporary_password_expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),driverId());
        assertThat(attempt("test.driver",temporary).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void passwordSetupRevokesEverySessionAndEnablesFreshNormalLogin() throws Exception {
        String temporary=reset(login("test.admin"));
        var one=(MockHttpSession)attempt("test.driver",temporary).getRequest().getSession();
        var two=(MockHttpSession)attempt("test.driver",temporary).getRequest().getSession();
        String replacement="new private permanent passphrase";
        mutate(one,"/api/auth/password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",temporary,"newPassword",replacement),204);
        assertThat(one.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(two)).andExpect(status().isUnauthorized());
        assertThat(attempt("test.driver",temporary).getResponse().getStatus()).isEqualTo(401);
        var logged=attempt("test.driver",replacement); assertThat(logged.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/driver/routes").session((MockHttpSession)logged.getRequest().getSession())).andExpect(status().isOk());
        var account=accounts.findById(driverId()).orElseThrow(); assertThat(account.isPasswordChangeRequired()).isFalse();
        assertThat(account.getTemporaryPasswordExpiresAt()).isNull(); assertThat(account.getAuthenticationRevision()).isEqualTo(2);
    }
    @Test void failedPasswordChangesLeaveCredentialAndRevisionUnchanged() throws Exception {
        var session=login("test.driver");
        mutate(session,"/api/auth/password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword","incorrect long password","newPassword","valid replacement passphrase"),403);
        for(String replacement:java.util.List.of("short",PASSWORD,"é".repeat(37))) {
            mutate(session,"/api/auth/password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",PASSWORD,"newPassword",replacement),400);
        }
        var csrf=token(session);
        String malformed="{\"currentPassword\":\""+PASSWORD+"\",\"newPassword\":\""+"a".repeat(14)+Character.toString(92)+"uD800\"}";
        mvc.perform(post("/api/auth/password").session(session).header(csrf.header(),csrf.value()).contentType(MediaType.APPLICATION_JSON).content(malformed)).andExpect(status().isBadRequest());
        assertThat(accounts.findById(driverId()).orElseThrow().getPasswordHash()).isEqualTo(HASH);
        assertThat(accounts.findById(driverId()).orElseThrow().getAuthenticationRevision()).isZero(); assertThat(audits.count()).isZero();
    }
    @Test void ordinaryPasswordChangeAlsoRevokesSessionsAndRequiresCurrentPassword() throws Exception {
        var one=login("test.driver"); var two=login("test.driver");
        mutate(one,"/api/auth/password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",PASSWORD,"newPassword","new ordinary user passphrase"),204);
        mvc.perform(get("/api/auth/me").session(two)).andExpect(status().isUnauthorized());
        assertThat(attempt("test.driver",PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void auditFailureRollsBackPasswordAndRevision() throws Exception {
        var session=login("test.admin");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("private failure")).when(audits).saveAndFlush(org.mockito.ArgumentMatchers.any(AccountAuditEvent.class));
        mutate(session,"/api/admin/accounts/"+driverId()+"/reset-password",org.springframework.http.HttpMethod.POST,java.util.Map.of("currentPassword",PASSWORD),409);
        var account=accounts.findById(driverId()).orElseThrow(); assertThat(account.getPasswordHash()).isEqualTo(HASH);
        assertThat(account.getAuthenticationRevision()).isZero(); assertThat(account.isPasswordChangeRequired()).isFalse(); assertThat(audits.count()).isZero();
    }
    @Test void requestAndTemporaryCredentialStringRepresentationsHidePasswords() {
        var request=new AccountRequests.ChangePassword(); request.setCurrentPassword(PASSWORD); request.setNewPassword("another private password");
        assertThat(request.toString()).doesNotContain(PASSWORD,"another private password");
        var credential=new AccountManagementService.TemporaryCredential(new AccountSummary(1L,"driver.one",AccountRole.DRIVER,1,true),PASSWORD,java.time.Instant.now());
        assertThat(credential.toString()).doesNotContain(PASSWORD);
    }
    @Test void capturedOldRevisionCannotReadOrWriteDriverAssignments() throws Exception {
        long before=accounts.findById(driverId()).orElseThrow().getAuthenticationRevision();
        assertThat(contextAssignments.routes(driverId(),before)).hasSize(1);
        var admin=login("test.admin");
        mutate(admin,"/api/admin/accounts/"+driverId()+"/enabled",org.springframework.http.HttpMethod.PATCH,java.util.Map.of("enabled",false,"currentPassword",PASSWORD),200);
        mutate(admin,"/api/admin/accounts/"+driverId()+"/enabled",org.springframework.http.HttpMethod.PATCH,java.util.Map.of("enabled",true,"currentPassword",PASSWORD),200);
        assertThat(contextAssignments.routes(driverId(),before)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contextAssignments.status(driverId(),before,assignedDelivery,"COMPLETED"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(deliveries.findById(assignedDelivery).orElseThrow().getDeliveryStatus()).isEqualTo(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS);
        assertThat(contextAssignments.routes(driverId(),2L)).hasSize(1);
    }
    @Autowired com.mmu.schedulingapp.driver.DriverAssignmentService contextAssignments;
}
