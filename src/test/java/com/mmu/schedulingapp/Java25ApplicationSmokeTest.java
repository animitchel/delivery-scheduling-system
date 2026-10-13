package com.mmu.schedulingapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"java25-test", "local"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:java25-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop", "server.address=127.0.0.1"})
class Java25ApplicationSmokeTest {
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired com.mmu.schedulingapp.repository.RecipientRepository recipients;
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @LocalServerPort int port;
    private HttpClient http;
    private String base;

    @BeforeAll void signInThroughRunningServer() throws Exception {
        base = "http://127.0.0.1:" + port;
        String password = "smoke test administrator passphrase";
        accounts.createAdministrator("smoke.admin", password);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        http = HttpClient.newBuilder().cookieHandler(cookies).build();
        var csrfResponse = get("/api/auth/csrf");
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        assertThat(csrfResponse.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        var csrf = json.readTree(csrfResponse.body());
        String body = "username=smoke.admin&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        var login = http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login"))
                .header(csrf.get("headerName").asText(),csrf.get("token").asText())
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void startsOnJava25AndServesAdminPageAfterRealLogin() throws Exception {
        assertThat(Runtime.version().feature()).isEqualTo(25);
        var response = get("/html/admin.html");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Scheduling App", "recipientForm", "deliveryForm", "logoutButton");
    }
    @ParameterizedTest @ValueSource(strings={"operators","recipients","warehouses","deliveries","routes"})
    void servesEmptyCollectionsThroughAuthenticatedRunningApplication(String resource) throws Exception {
        var response = get("/api/v1/scheduling-app/"+resource);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }
    @Test void runningServerRejectsAnonymousSchedulingRequests() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/api/v1/scheduling-app/routes")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="session.browser.node", matches=".+")
    void browserLoginCsrfRenderingAndLogout() throws Exception {
        var recipient = new com.mmu.schedulingapp.model.Recipient();
        recipient.setFirstName("<img src=x onerror=window.__injected=true>");
        recipient.setLastName("Test"); recipient.setAddress("Test address");
        recipient.setLatitude(java.math.BigDecimal.ZERO); recipient.setLongitude(java.math.BigDecimal.ZERO);
        recipients.saveAndFlush(recipient);
        var driverFixtures = seedDriverFixtures(recipient);
        try {
            var builder = new ProcessBuilder(System.getProperty("session.browser.node"), "scripts/test-session-browser.cjs", base);
            builder.environment().put("NODE_PATH", System.getProperty("session.browser.modules"));
            builder.environment().put("SESSION_BROWSER_EXECUTABLE", System.getProperty("session.browser.executable"));
            builder.environment().put("DRIVER_TEST_OTHER_ROUTE", String.valueOf(driverFixtures.otherRoute()));
            builder.environment().put("DRIVER_TEST_OTHER_DELIVERY", String.valueOf(driverFixtures.otherDelivery()));
            builder.redirectErrorStream(true);
            var process = builder.start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Browser verification timed out.");
            }
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage("Browser verification failed: %s",output).isZero();
        } finally { clearDriverFixtures(); }
    }

    record DriverBrowserFixtures(Integer otherRoute, Integer otherDelivery) { }
    DriverBrowserFixtures seedDriverFixtures(com.mmu.schedulingapp.model.Recipient recipient) {
        return new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(transaction -> {
            var operators = context.getBean(com.mmu.schedulingapp.repository.OperatorRepository.class);
            var deliveries = context.getBean(com.mmu.schedulingapp.repository.DeliveryRepository.class);
            var routes = context.getBean(com.mmu.schedulingapp.repository.RouteRepository.class);
            Integer otherRoute = null, otherDelivery = null;
            for (int index = 0; index < 5; index++) {
                var operator = new com.mmu.schedulingapp.model.Operator();
                operator.setName("Browser driver " + index); operator.setEmail("browser" + index + "@example.test"); operator.setPhoneNumber("123"); operators.saveAndFlush(operator);
                if (index == 4) { operator.setName("<img src=x onerror=window.__injected=true>"); operators.saveAndFlush(operator); continue; }
                if (index == 3) { operator.setName("Onboarding operator"); operators.saveAndFlush(operator); continue; }
                String username = index == 0 ? "smoke.driver" : index == 1 ? "other.driver" : "empty.driver";
                accounts.createDriver(username, "smoke test administrator passphrase", operator.getOperatorId());
                for (int routeIndex = 0; routeIndex < (index == 0 ? 2 : index == 1 ? 1 : 0); routeIndex++) {
                    var delivery = new com.mmu.schedulingapp.model.Delivery();
                    delivery.setRecipientId(recipients.findById(recipient.getRecipientId()).orElseThrow()); delivery.setOperatorId(operator);
                    delivery.setDeliveryStatus(com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS); delivery.setPriorityLevel(com.mmu.schedulingapp.model.Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(delivery);
                    var route = new com.mmu.schedulingapp.model.Route(); route.setOperator(operator); route.setScheduledDate(java.time.LocalDate.now().plusDays(routeIndex));
                    route.setRouteStatus(com.mmu.schedulingapp.model.Route.RouteStatus.IN_PROGRESS); route.setDeliveries(new java.util.ArrayList<>(java.util.List.of(delivery))); routes.saveAndFlush(route);
                    if (index == 1) { otherRoute = route.getRouteId(); otherDelivery = delivery.getDeliveryId(); }
                }
            }
            for (int index=0; index<21; index++) accounts.createAdministrator("pagination.admin"+index,"smoke test administrator passphrase");
            return new DriverBrowserFixtures(otherRoute, otherDelivery);
        });
    }
    void clearDriverFixtures() {
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(transaction -> {
            var routes=context.getBean(com.mmu.schedulingapp.repository.RouteRepository.class); routes.deleteAll(); routes.flush();
            var deliveries=context.getBean(com.mmu.schedulingapp.repository.DeliveryRepository.class); deliveries.deleteAll(); deliveries.flush();
            var repository=context.getBean(com.mmu.schedulingapp.account.AccountRepository.class);
            for (String name: java.util.List.of("smoke.driver","other.driver","empty.driver","onboarding.driver")) repository.findByUsername(name).ifPresent(repository::delete);
            for (var account: repository.findAll()) if (account.getUsername().startsWith("pagination.admin")) repository.delete(account);
            repository.flush();
            context.getBean(com.mmu.schedulingapp.account.AccountAuditRepository.class).deleteAll();
            var operators=context.getBean(com.mmu.schedulingapp.repository.OperatorRepository.class); operators.deleteAll(); operators.flush();
            recipients.deleteAll(); recipients.flush();
        });
    }

}
