package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.databind.*;
import com.mmu.schedulingapp.SchedulingAppApplication;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.utility.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL/Flyway and HTTP journey; provider calls are fixtures, never paid Google requests. */
@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class ManagedDevelopmentMySqlTest {
    static final String PASSWORD="fresh managed administrator passphrase";
    String url;
    final ObjectMapper json = new ObjectMapper();
    @TestConfiguration(proxyBeanMethods=false)
    static class StubMaps {
        @Bean @Primary MapsProvider fixtureMaps() throws Exception {
            var maps=mock(MapsProvider.class);
            when(maps.coordinates(anyString())).thenReturn(Optional.of(new GeocodingService.Coordinate(53.1,-2.1)));
            when(maps.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> {
                ((PlanningGuard.Budget)call.getArgument(4)).countProviderCall();
                return new DistanceResult(1,"1 min",60);
            });
            return maps;
        }
    }
    @BeforeEach void database() throws Exception {
        String name="managed_journey_"+UUID.randomUUID().toString().replace("-","");
        try(var db=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var statement=db.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
        }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test",name);
    }
    @AfterEach void cleanup() throws Exception {
        if(url!=null) try(var db=DriverManager.getConnection(url,"root","disposable-test-password");var statement=db.createStatement()) {
            statement.execute("DROP DATABASE "+db.getCatalog());
        }
    }
    ConfigurableApplicationContext start(boolean provision) {
        var builder=new SpringApplicationBuilder(SchedulingAppApplication.class,StubMaps.class)
                .web(provision?WebApplicationType.NONE:WebApplicationType.SERVLET)
                .profiles(provision?new String[]{"java25-test","local","schema-managed","provision-admin"}:new String[]{"java25-test","local","schema-managed"});
        return builder.run("--spring.config.location=classpath:/","--spring.datasource.url="+url,
                "--spring.datasource.username=root","--spring.datasource.password=disposable-test-password",
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                "--ACCOUNT_BOOTSTRAP_USERNAME=fresh.admin","--ACCOUNT_BOOTSTRAP_PASSWORD="+PASSWORD,
                "--server.address=127.0.0.1","--server.port=0","--logging.level.root=OFF","--spring.main.banner-mode=off");
    }
    int historyRows() throws Exception {
        try(var db=DriverManager.getConnection(url,"root","disposable-test-password");var statement=db.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE")) {
            rows.next(); return rows.getInt(1);
        }
    }
    @Test void freshProvisionSchedulingCompletionAndRestartPreserveManagedState() throws Exception {
        String hash;
        try(var app=start(true)) {
            var accounts=app.getBean(AccountRepository.class);
            assertThat(accounts.count()).isEqualTo(1);
            hash=accounts.findByUsername("fresh.admin").orElseThrow().getPasswordHash();
        }
        try(var app=start(true)) {
            assertThat(app.getBean(AccountRepository.class).count()).isEqualTo(1);
            assertThat(app.getBean(AccountRepository.class).findByUsername("fresh.admin").orElseThrow().getPasswordHash()).isEqualTo(hash);
        }
        int deliveryId,routeId,operatorId;
        try(var app=start(false)) {
            String base="http://127.0.0.1:"+((org.springframework.boot.web.server.context.WebServerApplicationContext)app).getWebServer().getPort();
            var admin=new Session(base); admin.login("fresh.admin",PASSWORD);
            assertThat(admin.get("/app/admin/deliveries").statusCode()).isEqualTo(200);
            int warehouseId=admin.post("/api/admin/warehouses","{\"name\":\"Fresh depot\",\"address\":\"Test depot\",\"latitude\":53.1,\"longitude\":-2.1}").path("warehouseId").asInt();
            operatorId=admin.post("/api/admin/operators","{\"name\":\"Fresh driver\",\"phoneNumber\":\"+447123456789\",\"email\":\"fresh.driver@example.test\",\"vehicleType\":\"Van\",\"maxCapacity\":100,\"warehouseId\":"+warehouseId+",\"availabilityStatus\":\"AVAILABLE\"}").path("operatorId").asInt();
            int recipientId=admin.post("/api/admin/recipients","{\"firstName\":\"Fresh\",\"lastName\":\"Recipient\",\"address\":\"Test address\",\"priorityLevel\":\"MEDIUM\"}").path("recipientId").asInt();
            deliveryId=admin.post("/api/admin/deliveries","{\"recipientId\":"+recipientId+",\"scheduledDate\":\"2026-10-14\",\"startTimeWindow\":\"09:00\",\"endTimeWindow\":\"12:00\",\"priorityLevel\":\"MEDIUM\",\"specialInstructions\":\"Use side entrance\"}").path("deliveryId").asInt();
            assertThat(warehouseId).isPositive(); assertThat(operatorId).isPositive(); assertThat(recipientId).isPositive(); assertThat(deliveryId).isPositive();
            // Existing account-onboarding APIs have separate regressions; provide an eligible driver identity here.
            app.getBean(AccountService.class).createDriver("fresh.driver",PASSWORD,operatorId);
            var generated=admin.post("/api/admin/routes/generate","{\"scheduledDate\":\"2026-10-14\"}").path("routes");
            assertThat(generated.size()).isEqualTo(1); routeId=generated.get(0).path("routeId").asInt();
            var driver=new Session(base); driver.login("fresh.driver",PASSWORD);
            var response=driver.send("PATCH","/api/driver/deliveries/"+deliveryId+"/status","{\"deliveryStatus\":\"COMPLETED\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("deliveryStatus").asText()).isEqualTo("COMPLETED");
            assertThat(app.getBean(RouteRepository.class).findById(routeId).orElseThrow().getRouteStatus()).isEqualTo(Route.RouteStatus.COMPLETED);
            assertThat(app.getBean(OperatorRepository.class).findById(operatorId).orElseThrow().getAvailabilityStatus()).isEqualTo(Operator.AvailabilityStatus.AVAILABLE);
        }
        try(var app=start(false)) {
            assertThat(app.getBean(DeliveryRepository.class).findById(deliveryId).orElseThrow().getDeliveryStatus()).isEqualTo(Delivery.DeliveryStatus.COMPLETED);
            assertThat(app.getBean(RouteRepository.class).findById(routeId).orElseThrow().getRouteStatus()).isEqualTo(Route.RouteStatus.COMPLETED);
            assertThat(app.getBean(AccountRepository.class).count()).isEqualTo(2);
        }
        assertThat(historyRows()).isEqualTo(1);
    }
    class Session {
        final String base;
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        Session(String base) { this.base=base; }
        HttpResponse<String> get(String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        HttpResponse<String> send(String method,String path,String body) throws Exception {
            var csrf=json.readTree(get("/api/auth/csrf").body());
            return client.send(HttpRequest.newBuilder(URI.create(base+path)).header(csrf.path("headerName").asText(),csrf.path("token").asText())
                    .header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode post(String path,String body) throws Exception {
            var response=send("POST",path,body);
            assertThat(response.statusCode()).as(path).isEqualTo(201);
            return json.readTree(response.body());
        }
        void login(String username,String password) throws Exception {
            var csrf=json.readTree(get("/api/auth/csrf").body());
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login"))
                    .header(csrf.path("headerName").asText(),csrf.path("token").asText()).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
}
