package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.repository.OperatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
        "spring.datasource.url=jdbc:h2:mem:session-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class SessionSecurityIntegrationTest {
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

    @BeforeEach void fixtures() {
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

    @ParameterizedTest
    @ValueSource(strings={"operators","recipients","warehouses","deliveries","routes"})
    void anonymousCannotReadSchedulingResources(String resource) throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/"+resource)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required."));
    }
    @Test void anonymousCannotReadIdentityAndHtmlRedirectsToLogin() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/html/admin.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/html/login.html"));
        mvc.perform(get("/html/operators.html")).andExpect(status().isFound());
        mvc.perform(get("/html/login.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("loginForm")));
    }
    @Test void loginNormalizesUsernameErasesCredentialsAndRotatesSession() throws Exception {
        var token = token(null); String oldId = token.session().getId();
        mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(),token.value())
                .param("username","  TEST.ADMIN  ").param("password",PASSWORD)).andExpect(status().isOk());
        assertThat(token.session().getId()).isNotEqualTo(oldId);
        var context = (SecurityContext) token.session().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((AccountPrincipal)context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/auth/me").session(token.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.admin")).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.operatorId").isEmpty()).andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"test.unknown","test.disabled","bad username"})
    void unknownDisabledAndInvalidNamesHaveGenericLoginFailure(String username) throws Exception {
        var result = attempt(username,PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"error\":\"Invalid username or password.\"}");
        assertThat(result.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }
    @Test void incorrectPasswordCannotAuthenticate() throws Exception {
        assertThat(attempt("test.admin","incorrect long password").getResponse().getStatus()).isEqualTo(401);
    }
    @Test void loginRequiresCsrfAndDoesNotAcceptGetOrBasicAuth() throws Exception {
        mvc.perform(post("/api/auth/login").param("username","test.admin").param("password",PASSWORD)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/login").param("username","test.admin").param("password",PASSWORD)).andExpect(status().isUnauthorized());
        String credentials = java.util.Base64.getEncoder().encodeToString(("test.admin:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/scheduling-app/routes").header("Authorization","Basic "+credentials)).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings={"operators","recipients","warehouses","deliveries","routes"})
    void driverCannotReadSchedulingResources(String resource) throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/"+resource).session(login("test.driver"))).andExpect(status().isForbidden());
    }
    @Test void driverIdentityAndDriverPageAvailableButManagementAccessDenied() throws Exception {
        var session = login("test.driver"); var token = token(session);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.operatorId").isNumber());
        mvc.perform(get("/html/admin.html").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/html/operators.html").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/scheduling-app/operators").session(session).header(token.header(),token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/scheduling-app/routes").session(session).header(token.header(),token.value())).andExpect(status().isForbidden());
    }
    @Test void administratorCanReadPagesAndCreateOperatorWithCsrf() throws Exception {
        var warehouse = new com.mmu.schedulingapp.model.Warehouse();
        warehouse.setName("Test warehouse"); warehouse.setAddress("Test address");
        warehouse.setLatitude(java.math.BigDecimal.ZERO); warehouse.setLongitude(java.math.BigDecimal.ZERO);
        warehouses.saveAndFlush(warehouse);
        var session = login("test.admin"); var token = token(session);
        mvc.perform(get("/html/admin.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/html/operators.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/scheduling-app/operators").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/scheduling-app/operators").session(session).header(token.header(),token.value())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New driver\",\"phoneNumber\":\"1234567890\",\"email\":\"new@example.test\",\"maxCapacity\":10,\"warehouseName\":\"Test warehouse\",\"availabilityStatus\":\"AVAILABLE\"}"))
                .andExpect(status().isCreated());
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT","PATCH","DELETE"})
    void allUnsafeSchedulingMethodsRequireCsrf(String method) throws Exception {
        var session = login("test.admin");
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),"/api/v1/scheduling-app/operators/1")
                .session(session).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void badTokenAndAnonymousTokenFromBeforeLoginAreRejected() throws Exception {
        var token = token(null);
        mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(),token.value()).param("username","test.admin").param("password",PASSWORD)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(token.session()).header(token.header(),token.value())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(token.session()).header(token.header(),"invalid-token")).andExpect(status().isForbidden());
        var fresh = token(token.session());
        mvc.perform(post("/api/auth/logout").session(fresh.session()).header(fresh.header(),fresh.value())).andExpect(status().isNoContent());
        assertThat(fresh.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test void getLogoutDoesNotInvalidateAndPostWithoutCsrfIsDenied() throws Exception {
        var session = login("test.admin");
        mvc.perform(get("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }
    @Test void disablingAnAccountRevokesItsExistingSession() throws Exception {
        var session = login("test.admin");
        jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE username='test.admin'");
        mvc.perform(get("/api/v1/scheduling-app/operators").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void deletingAndRecreatingUsernameCannotReusePreviousIdentity() throws Exception {
        var session = login("test.admin");
        accounts.delete(accounts.findByUsername("test.admin").orElseThrow()); accounts.flush();
        accounts.saveAndFlush(new UserAccount("test.admin",HASH,AccountRole.ADMIN,null,false));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void changingRoleRevokesPreviousPrivileges() throws Exception {
        var session = login("test.admin"); var driver = accounts.findByUsername("test.driver").orElseThrow();
        Integer operatorId = driver.getOperator().getOperatorId(); accounts.delete(driver); accounts.flush();
        jdbc.update("UPDATE user_accounts SET role='DRIVER',operator_id=? WHERE username='test.admin'",operatorId);
        mvc.perform(get("/api/v1/scheduling-app/routes").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void rejectsOverlengthAndMalformedUnicodeLoginRepresentations() throws Exception {
        accounts.saveAndFlush(new UserAccount("boundary.admin",BOUNDARY_HASH,AccountRole.ADMIN,null,false));
        assertThat(attempt("boundary.admin",BOUNDARY_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(attempt("boundary.admin",BOUNDARY_PASSWORD+"suffix").getResponse().getStatus()).isEqualTo(401);
        String replacement = "a".repeat(14)+"?";
        accounts.saveAndFlush(new UserAccount("unicode.admin","{bcrypt}"+new BCryptPasswordEncoder(12).encode(replacement),AccountRole.ADMIN,null,false));
        assertThat(attempt("unicode.admin","a".repeat(14)+(char)0xD800).getResponse().getStatus()).isEqualTo(401);
        assertThat(attempt("unicode.admin","a".repeat(14)+(char)0xDC00).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void noWildcardCorsAndUnknownResourcesDenied() throws Exception {
        mvc.perform(get("/api/auth/csrf").header("Origin","https://other.example")).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/unknown").session(login("test.admin"))).andExpect(status().isForbidden());
    }
}
