package com.mmu.schedulingapp;

import tools.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles({"java25-test","local"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:route-generation-http;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=true","server.address=127.0.0.1"})
class RouteGenerationHttpTest {
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired WarehouseRepository warehouses;
    @MockitoBean MapsProvider maps;
    HttpClient http;
    String base,header,token;
    static final String BODY="{\"scheduledDate\":\"2026-10-13\"}";
    @BeforeEach void setup() throws Exception {
        routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush(); reset(maps);
        base="http://127.0.0.1:"+port; http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="route.http."+UUID.randomUUID(),password="disposable route HTTP test passphrase"; accounts.createAdministrator(username,password); csrf();
        var login=http.send(write("/api/auth/login").POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).header("Content-Type","application/x-www-form-urlencoded").build(),HttpResponse.BodyHandlers.ofString()); assertThat(login.statusCode()).isEqualTo(200); csrf();
        var warehouse=new Warehouse(); warehouse.setName("Depot"); warehouse.setAddress("Depot"); warehouse.setLatitude(BigDecimal.ZERO); warehouse.setLongitude(BigDecimal.ZERO); warehouses.saveAndFlush(warehouse);
        var operator=new Operator(); operator.setName("HTTP driver"); operator.setEmail("http."+UUID.randomUUID()+"@test.local"); operator.setPhoneNumber("123"); operator.setWarehouse(warehouse); operators.saveAndFlush(operator);
        var recipient=new Recipient(); recipient.setFirstName("HTTP"); recipient.setLastName("Recipient"); recipient.setAddress("Saved address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); recipients.saveAndFlush(recipient);
        var delivery=new Delivery(); delivery.setRecipientId(recipient); delivery.setScheduledDate(LocalDate.of(2026,10,12)); delivery.setStartTimeWindow(LocalTime.of(9,0)); delivery.setEndTimeWindow(LocalTime.of(12,0)); delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(delivery);
        when(maps.distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); ((PlanningGuard.Budget)call.getArgument(4)).countProviderCall(); return new DistanceResult(1,"1 min",60); });
    }
    void csrf() throws Exception { var value=json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()); header=value.get("headerName").asText(); token=value.get("token").asText(); }
    HttpRequest.Builder write(String path) { return HttpRequest.newBuilder(URI.create(base+path)).header(header,token); }
    @Test void actualSessionAndCsrfGenerateCommittedRoutesAndReadDateFilteredResults() throws Exception {
        var denied=http.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/routes/generate")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(denied.statusCode()).isEqualTo(403); assertThat(routes.count()).isZero();
        var response=http.send(write("/api/admin/routes/generate").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store"); var saved=json.readTree(response.body()); int id=saved.get("routes").get(0).get("routeId").asInt(); assertThat(saved.get("routes").get(0).has("deliveries")).isFalse();
        var read=http.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/routes/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString()); assertThat(read.statusCode()).isEqualTo(200); assertThat(json.readTree(read.body()).get("deliveries").get(0).get("status").asText()).isEqualTo("IN_PROGRESS");
        var page=http.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/routes?scheduledDate=2026-10-13")).GET().build(),HttpResponse.BodyHandlers.ofString()); assertThat(page.statusCode()).isEqualTo(200); assertThat(json.readTree(page.body()).get("totalElements").asInt()).isEqualTo(1); assertThat(routes.count()).isEqualTo(1);
    }
    @Test void actualHttpRejectsDuplicatesAndUnsupportedFieldsBeforeCallingMaps() throws Exception {
        for(var body:java.util.List.of("{\"scheduledDate\":\"2026-10-13\",\"scheduledDate\":\"2026-10-14\"}","{\"scheduledDate\":\"2026-10-13\",\"privateField\":\"private-value-do-not-expose\"}")) {
            var response=http.send(write("/api/admin/routes/generate").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isEqualTo(400); assertThat(response.body()).doesNotContain("private-value-do-not-expose");
        }
        assertThat(routes.count()).isZero(); verifyNoInteractions(maps);
    }
    @Test void savedRouteAssessmentUsesActualSessionWithoutCallingMapsAgain() throws Exception {
        var generated=http.send(write("/api/admin/routes/generate").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(generated.statusCode()).isEqualTo(201);
        int id=json.readTree(generated.body()).get("routes").get(0).get("routeId").asInt();
        var before=http.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/routes/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString()).body();
        var response=http.send(write("/api/admin/routes/"+id+"/assessment").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"departure\":\"2026-10-12T08:00:00\",\"serviceDurationSeconds\":600}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var assessment=json.readTree(response.body()); assertThat(assessment.get("hypothetical").asBoolean()).isTrue();
        assertThat(assessment.get("stops").get(0).get("waitingSeconds").asLong()).isEqualTo(3540);
        assertThat(assessment.get("stops").get(0).get("status").asText()).isEqualTo("WITHIN_WINDOW");
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base+"/api/admin/routes/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(before);
        verify(maps,times(1)).distance(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
    }

}
