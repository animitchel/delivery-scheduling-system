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
        try {
            var builder = new ProcessBuilder(System.getProperty("session.browser.node"), "scripts/test-session-browser.cjs", base);
            builder.environment().put("NODE_PATH", System.getProperty("session.browser.modules"));
            builder.environment().put("SESSION_BROWSER_EXECUTABLE", System.getProperty("session.browser.executable"));
            builder.inheritIO();
            var process = builder.start();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Browser verification timed out.");
            }
            assertThat(process.exitValue()).isZero();
        } finally { recipients.deleteById(recipient.getRecipientId()); }
    }

}
