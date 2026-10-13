package com.mmu.schedulingapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.repository.RecipientRepository;
import com.mmu.schedulingapp.service.RecipientService;
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
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ActiveProfiles({"java25-test","local"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:recipient-http;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=true","server.address=127.0.0.1"})
class RecipientManagementHttpTest {
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired RecipientRepository recipients;
    @Autowired RecipientService service;
    @MockitoBean MapsProvider provider;
    HttpClient http;
    String base,csrfHeader,csrfValue;
    Integer id;
    String version;
    static final String BODY="{\"firstName\":\"HTTP\",\"lastName\":\"Recipient\",\"address\":\"HTTP address\",\"contactNumber\":null,\"priorityLevel\":\"MEDIUM\"}";
    @BeforeEach void setup() throws Exception {
        base="http://127.0.0.1:"+port; http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="http.admin."+UUID.randomUUID(); String password="disposable HTTP recipient test passphrase"; accounts.createAdministrator(username,password);
        token(); var login=http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login")).header(csrfHeader,csrfValue).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(username,java.nio.charset.StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString()); assertThat(login.statusCode()).isEqualTo(200); token();
        when(provider.coordinates("HTTP address")).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return Optional.of(new GeocodingService.Coordinate(53.12345678,-2.5));});
        var created=http.send(write("/api/admin/recipients",null).POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(created.statusCode()).isEqualTo(201);
        id=json.readTree(created.body()).get("recipientId").asInt(); version=created.headers().firstValue("ETag").orElseThrow();
    }
    @AfterEach void cleanup() { if(id!=null) recipients.deleteById(id); }
    void token() throws Exception { var value=json.readTree(get("/api/auth/csrf").body());csrfHeader=value.get("headerName").asText();csrfValue=value.get("token").asText(); }
    HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpRequest.Builder write(String path,String etag) {
        var request=HttpRequest.newBuilder(URI.create(base+path)).header(csrfHeader,csrfValue).header("Content-Type","application/json"); if(etag!=null) request.header("If-Match",etag); return request;
    }
    @Test void conditionalHttpHeadersAndCoordinatePreservationWorkThroughRealServer() throws Exception {
        var fetched=get("/api/admin/recipients/"+id); assertThat(fetched.statusCode()).isEqualTo(200); assertThat(fetched.headers().firstValue("ETag")).contains(version); assertThat(fetched.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String edited=BODY.replace("\"HTTP\"","\"Edited\"");
        var updated=http.send(write("/api/admin/recipients/"+id,version).PUT(HttpRequest.BodyPublishers.ofString(edited)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(updated.statusCode()).isEqualTo(200); assertThat(updated.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(version); assertThat(json.readTree(updated.body()).get("latitude").decimalValue()).isEqualByComparingTo(new BigDecimal("53.123457"));
        var stale=http.send(write("/api/admin/recipients/"+id,version).PUT(HttpRequest.BodyPublishers.ofString(edited)).build(),HttpResponse.BodyHandlers.ofString()); assertThat(stale.statusCode()).isEqualTo(412); verify(provider,times(1)).coordinates("HTTP address");
    }
    @Test void requestScopedCachedRecipientCannotOverwriteConcurrentEdit() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(provider.coordinates("Changed HTTP address")).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return Optional.of(new GeocodingService.Coordinate(54,-3));});
        String changed=BODY.replace("HTTP address","Changed HTTP address");
        var pending=http.sendAsync(write("/api/admin/recipients/"+id,version).PUT(HttpRequest.BodyPublishers.ofString(changed)).build(),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); var winning=new RecipientWriteRequest(); winning.setFirstName("Winning edit"); winning.setLastName("Recipient"); winning.setAddress("HTTP address"); winning.setPriorityLevel(RecipientRequestDTO.PriorityLevel.MEDIUM); service.replaceRecipient(id,winning,version);
        } finally { release.countDown(); }
        assertThat(pending.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        var current=recipients.findById(id).orElseThrow(); assertThat(current.getFirstName()).isEqualTo("Winning edit"); assertThat(current.getAddress()).isEqualTo("HTTP address"); assertThat(current.getLatitude()).isEqualByComparingTo("53.123457");
    }
}
