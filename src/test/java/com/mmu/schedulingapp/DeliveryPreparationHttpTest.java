package com.mmu.schedulingapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.utility.MapsProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles({"java25-test","local"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:delivery-preparation-http;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=true","server.address=127.0.0.1"})
class DeliveryPreparationHttpTest {
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired RecipientRepository recipients;
    @Autowired DeliveryRepository deliveries;
    @MockitoBean MapsProvider maps;
    HttpClient http;
    String base,header,token,body;
    @BeforeEach void setup() throws Exception {
        deliveries.deleteAll(); deliveries.flush(); recipients.deleteAll(); recipients.flush(); reset(maps);
        base="http://127.0.0.1:"+port; http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="delivery.http."+UUID.randomUUID(),password="disposable delivery HTTP test passphrase";
        accounts.createAdministrator(username,password); csrf();
        var login=http.send(write("/api/auth/login").POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).header("Content-Type","application/x-www-form-urlencoded").build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200); csrf();
        var recipient=new Recipient(); recipient.setFirstName("HTTP"); recipient.setLastName("Recipient"); recipient.setAddress("Saved address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); int id=recipients.saveAndFlush(recipient).getRecipientId();
        body="{\"recipientId\":"+id+",\"scheduledDate\":\"2020-01-02\",\"startTimeWindow\":\"09:00\",\"endTimeWindow\":\"12:00\",\"priorityLevel\":\"HIGH\"}";
    }
    void csrf() throws Exception { var result=json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()); header=result.get("headerName").asText(); token=result.get("token").asText(); }
    HttpRequest.Builder write(String path) { return HttpRequest.newBuilder(URI.create(base+path)).header(header,token); }
    @Test void actualSessionAndCsrfCreateAndReadThePreparationContract() throws Exception {
        String path="/api/admin/deliveries";
        var denied=http.send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(denied.statusCode()).isEqualTo(403); assertThat(deliveries.count()).isZero();
        var result=http.send(write(path).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(201); assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        var saved=json.readTree(result.body()); assertThat(saved.get("deliveryStatus").asText()).isEqualTo("PENDING"); assertThat(saved.get("operatorId").isNull()).isTrue();
        var read=http.send(HttpRequest.newBuilder(URI.create(base+result.headers().firstValue("Location").orElseThrow())).GET().build(),HttpResponse.BodyHandlers.ofString()); assertThat(read.statusCode()).isEqualTo(200); assertThat(json.readTree(read.body()).get("deliveryId")).isEqualTo(saved.get("deliveryId"));
        var page=http.send(HttpRequest.newBuilder(URI.create(base+path+"?status=PENDING&scheduledDate=2020-01-02")).GET().build(),HttpResponse.BodyHandlers.ofString()); assertThat(page.statusCode()).isEqualTo(200); assertThat(json.readTree(page.body()).get("totalElements").asInt()).isEqualTo(1); verifyNoInteractions(maps);
    }
    @Test void actualHttpRejectsMalformedFieldsWithoutReturningTheirValues() throws Exception {
        String bad=body.substring(0,body.length()-1)+",\"privateField\":\"private-value-do-not-expose\"}";
        var result=http.send(write("/api/admin/deliveries").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(bad)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(400); assertThat(result.body()).doesNotContain("private-value-do-not-expose"); assertThat(deliveries.count()).isZero(); verifyNoInteractions(maps);
    }
}
