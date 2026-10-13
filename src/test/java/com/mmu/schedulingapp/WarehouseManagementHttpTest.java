package com.mmu.schedulingapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.repository.WarehouseRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles({"java25-test","local"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:warehouse-management-http;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=true","server.address=127.0.0.1"})
class WarehouseManagementHttpTest {
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired WarehouseRepository warehouses;
    HttpClient http;
    String base,header,token;
    static final String PATH="/api/admin/warehouses";
    static final String BODY="{\"name\":\"Depot\",\"address\":\"Depot address\",\"latitude\":53.123456,\"longitude\":-2.5}";
    @BeforeEach void setup() throws Exception {
        warehouses.deleteAll(); warehouses.flush();base="http://127.0.0.1:"+port;
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="warehouse.http."+UUID.randomUUID(),password="disposable warehouse HTTP test passphrase";accounts.createAdministrator(username,password);csrf();
        var login=http.send(write("/api/auth/login").POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).header("Content-Type","application/x-www-form-urlencoded").build(),HttpResponse.BodyHandlers.ofString());assertThat(login.statusCode()).isEqualTo(200);csrf();
    }
    void csrf() throws Exception { var value=json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());header=value.get("headerName").asText();token=value.get("token").asText(); }
    HttpRequest.Builder write(String path) { return HttpRequest.newBuilder(URI.create(base+path)).header(header,token); }
    @Test void realSessionCsrfConditionalWritesAndScalarReadsWorkWithOsivEnabled() throws Exception {
        var anonymous=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+PATH)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(anonymous.statusCode()).isEqualTo(401);
        var denied=http.send(HttpRequest.newBuilder(URI.create(base+PATH)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());assertThat(denied.statusCode()).isEqualTo(403);
        var created=http.send(write(PATH).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(BODY)).build(),HttpResponse.BodyHandlers.ofString());assertThat(created.statusCode()).isEqualTo(201);
        String path=created.headers().firstValue("Location").orElseThrow(),etag=created.headers().firstValue("ETag").orElseThrow();
        var read=http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(read.headers().firstValue("ETag")).contains(etag);assertThat(json.readTree(read.body()).has("assignedDrivers")).isFalse();
        var updated=http.send(write(path).header("If-Match",etag).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(BODY.replace("Depot address","Edited address"))).build(),HttpResponse.BodyHandlers.ofString());assertThat(updated.statusCode()).isEqualTo(200);
        var stale=http.send(write(path).header("If-Match",etag).DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(stale.statusCode()).isEqualTo(412);
        var page=http.send(HttpRequest.newBuilder(URI.create(base+PATH+"?size=1&search=Edited")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(page.statusCode()).isEqualTo(200);assertThat(page.headers().firstValue("Cache-Control")).contains("no-store");assertThat(json.readTree(page.body()).get("totalElements").asInt()).isEqualTo(1);
        var deleted=http.send(write(path).header("If-Match",updated.headers().firstValue("ETag").orElseThrow()).DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(deleted.statusCode()).isEqualTo(204);assertThat(warehouses.count()).isZero();
    }
    @Test void strictHttpPayloadsAndInvalidQueryValuesReturnPrivateSafeErrors() throws Exception {
        for(String body:new String[]{BODY.replace("\"name\":\"Depot\"","\"name\":\"Depot\",\"name\":\"private-value\""),BODY.replace("53.123456","\"53.123456\""),BODY.replace("53.123456","1e9999999"),BODY.replace("\"longitude\":-2.5","\"longitude\":-2.5,\"private-field\":\"private-value\"")}) {
            var response=http.send(write(PATH).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).doesNotContain("private-value","private-field");
        }
        var query=http.send(HttpRequest.newBuilder(URI.create(base+PATH+"?page=private-value")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(query.statusCode()).isEqualTo(400);assertThat(query.body()).doesNotContain("private-value");assertThat(warehouses.count()).isZero();
    }
}
