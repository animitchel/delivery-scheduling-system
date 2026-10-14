package com.mmu.schedulingapp;
import tools.jackson.databind.ObjectMapper;
import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.model.Warehouse;
import com.mmu.schedulingapp.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.util.UUID;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
@ActiveProfiles({"java25-test","local"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:operator-management-http;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=true","server.address=127.0.0.1"})
class OperatorManagementHttpTest {
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired OperatorRepository operators;
    @Autowired WarehouseRepository warehouses;
    HttpClient http;
    String base,header,token,body;
    @BeforeEach void setup() throws Exception {
        operators.deleteAll();operators.flush();warehouses.deleteAll();warehouses.flush();
        var warehouse=new Warehouse();warehouse.setName("Depot");warehouse.setAddress("Depot address");warehouse.setLatitude(BigDecimal.ZERO);warehouse.setLongitude(BigDecimal.ZERO);int warehouseId=warehouses.saveAndFlush(warehouse).getWarehouseId();
        body="{\"name\":\"Driver\",\"phoneNumber\":\"+447123456789\",\"email\":\"driver@example.test\",\"vehicleType\":\"Van\",\"maxCapacity\":25,\"warehouseId\":"+warehouseId+",\"availabilityStatus\":\"AVAILABLE\"}";
        base="http://127.0.0.1:"+port;http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="operator.http."+UUID.randomUUID(),password="disposable operator HTTP test passphrase";accounts.createAdministrator(username,password);csrf();
        var login=http.send(write("/api/auth/login").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());assertThat(login.statusCode()).isEqualTo(200);csrf();
    }
    void csrf() throws Exception { var value=json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());header=value.get("headerName").asText();token=value.get("token").asText(); }
    HttpRequest.Builder write(String path) { return HttpRequest.newBuilder(URI.create(base+path)).header(header,token); }
    @Test void realSessionConditionalWritesAndStrictPayloadsWorkWithOsivEnabled() throws Exception {
        String basePath="/api/admin/operators";
        var denied=http.send(HttpRequest.newBuilder(URI.create(base+basePath)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(denied.statusCode()).isEqualTo(403);
        var invalid=http.send(write(basePath).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.replace("{","{\"private-field\":\"private-value\","))).build(),HttpResponse.BodyHandlers.ofString());assertThat(invalid.statusCode()).isEqualTo(400);assertThat(invalid.body()).doesNotContain("private-value");
        var created=http.send(write(basePath).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertThat(created.statusCode()).isEqualTo(201);
        String path=created.headers().firstValue("Location").orElseThrow(),before=created.headers().firstValue("ETag").orElseThrow();
        var read=http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(read.headers().firstValue("ETag")).contains(before);assertThat(read.headers().firstValue("Cache-Control")).contains("no-store");
        var updated=http.send(write(path).header("If-Match",before).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(body.replace("Driver","Edited"))).build(),HttpResponse.BodyHandlers.ofString());assertThat(updated.statusCode()).isEqualTo(200);
        var stale=http.send(write(path).header("If-Match",before).DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(stale.statusCode()).isEqualTo(412);
        var deleted=http.send(write(path).header("If-Match",updated.headers().firstValue("ETag").orElseThrow()).DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(deleted.statusCode()).isEqualTo(204);assertThat(operators.count()).isZero();
    }
}
