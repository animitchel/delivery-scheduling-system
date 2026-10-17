package com.mmu.schedulingapp.demo;

import com.mmu.schedulingapp.account.AccountService;
import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.utility.*;
import tools.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Runnable portfolio journey; simulation/fixtures are deliberately excluded from the application JAR. */
@ActiveProfiles({"java25-test","local","portfolio-demo"})
@Import(BackendPortfolioDemoTest.SimulatedTravel.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false","spring.flyway.enabled=false",
        "spring.datasource.username=sa","spring.datasource.password=","server.address=127.0.0.1"})
class BackendPortfolioDemoTest {
    static final String MODEL="SIMULATED DEMO: 10 minutes and 2.5 km per selected leg; not live Maps";
    static final LocalDate DAY=LocalDate.of(2026,1,2);
    @TestConfiguration(proxyBeanMethods=false) @Profile("portfolio-demo")
    static class SimulatedTravel { @Bean @Primary DemoMapsProvider simulatedMaps() { return new DemoMapsProvider(); } }
    static class DemoMapsProvider extends MapsProvider {
        final AtomicInteger calls=new AtomicInteger();
        DemoMapsProvider() { super(null); }
        @Override public DistanceResult distance(double a,double b,double c,double d,PlanningGuard.Budget budget) {
            budget.countProviderCall(); calls.incrementAndGet(); return new DistanceResult(2.5,"SIMULATED DEMO: 10 minutes",600);
        }
        @Override public Optional<GeocodingService.Coordinate> coordinates(String address) {
            throw new IllegalStateException("Demo uses seeded coordinates; live geocoding is unavailable.");
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String mysql=System.getProperty("account.mysql.url");
        if(mysql==null) {
            properties.add("spring.datasource.url",() -> "jdbc:h2:mem:backend-portfolio-demo;MODE=MySQL;DB_CLOSE_DELAY=-1");
            properties.add("spring.datasource.driver-class-name",() -> "org.h2.Driver");
            properties.add("spring.jpa.database-platform",() -> "org.hibernate.dialect.H2Dialect");
        } else {
            if(!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")) throw new IllegalArgumentException("Demo requires the disposable loopback database.");
            // CREATE without IF NOT EXISTS refuses to adopt or reset an existing demo schema.
            try(var db=DriverManager.getConnection(mysql,"root","disposable-test-password");var sql=db.createStatement()) {
                sql.execute("CREATE DATABASE backend_portfolio_demo");
            } catch(Exception failure) { throw new IllegalStateException("Demo database must be new and disposable.",failure); }
            properties.add("spring.datasource.url",() -> mysql.replace("account_foundation_test","backend_portfolio_demo"));
            properties.add("spring.datasource.username",() -> "root"); properties.add("spring.datasource.password",() -> "disposable-test-password");
            properties.add("spring.datasource.driver-class-name",() -> "com.mysql.cj.jdbc.Driver");
            properties.add("spring.jpa.database-platform",() -> "org.hibernate.dialect.MySQLDialect");
        }
    }
    @LocalServerPort int port;
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;
    @Autowired WarehouseRepository warehouses;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MapsProvider provider;
    final Path reportPath=Path.of(".ci-artifacts/portfolio-demo/result.json");
    final Map<String,Object> report=new LinkedHashMap<>();
    final List<Map<String,Object>> steps=new ArrayList<>();
    void save() throws Exception { Files.createDirectories(reportPath.getParent()); Files.writeString(reportPath,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
    void step(String name,Map<String,Object> details) throws Exception {
        var entry=new LinkedHashMap<String,Object>(details); entry.put("name",name); entry.put("passed",true); steps.add(entry); report.put("steps",List.copyOf(steps)); save();
    }
    class Session {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String header,token;
        HttpResponse<String> send(String method,String path,String body,String type,int expected) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
            if(!method.equals("GET")) request.header(header,token).header("Content-Type",type);
            var response=http.send(request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s",method,path).isEqualTo(expected); return response;
        }
        void csrf() throws Exception { var value=json.readTree(send("GET","/api/auth/csrf",null,null,200).body()); header=value.get("headerName").asText(); token=value.get("token").asText(); }
        Session(String username,String password) throws Exception {
            csrf(); send("POST","/api/auth/login","username="+username+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8),"application/x-www-form-urlencoded",200); csrf();
        }
        JsonNode get(String path) throws Exception { return json.readTree(send("GET",path,null,null,200).body()); }
        JsonNode post(String path,Object body,int status) throws Exception { return json.readTree(send("POST",path,json.writeValueAsString(body),"application/json",status).body()); }
    }
    int warehouse(String name,String lat,String lon) {
        var w=new Warehouse(); w.setName(name); w.setAddress("Fictional demo depot"); w.setLatitude(new BigDecimal(lat)); w.setLongitude(new BigDecimal(lon)); return warehouses.saveAndFlush(w).getWarehouseId();
    }
    int operator(String name,int warehouse) {
        var o=new Operator(); o.setName(name); o.setEmail(name+"@example.invalid"); o.setPhoneNumber("00000000000"); o.setVehicleType("Demo van"); o.setMaxCapacity(1.0);
        o.setWarehouse(warehouses.findById(warehouse).orElseThrow()); return operators.saveAndFlush(o).getOperatorId();
    }
    int recipient(String name,String lat,String lon) {
        var p=new Recipient(); p.setFirstName(name); p.setLastName("Fictional demo"); p.setAddress("Fictional demo address"); p.setContactNumber("00000000000"); p.setLatitude(new BigDecimal(lat)); p.setLongitude(new BigDecimal(lon)); return recipients.saveAndFlush(p).getRecipientId();
    }
    int prepare(Session admin,int recipient,String end) throws Exception {
        return admin.post("/api/admin/deliveries",Map.of("recipientId",recipient,"scheduledDate",DAY.toString(),"startTimeWindow","09:00:00","endTimeWindow",end,"priorityLevel","MEDIUM"),201).get("deliveryId").asInt();
    }
    @Test @Timeout(90) void reproduciblePortfolioJourney() throws Exception {
        report.put("complete",false); report.put("simulation",MODEL); report.put("database",System.getProperty("account.mysql.url")==null ? "H2" : "MySQL"); save();
        assertThat(provider).isInstanceOf(DemoMapsProvider.class); var simulated=(DemoMapsProvider)provider;
        assertThat(routes.count()).isZero(); assertThat(deliveries.count()).isZero();
        var ids=new int[6];
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            int west=warehouse("Demo West","53.4808","-2.2426"),east=warehouse("Demo East","53.8008","-1.5491");
            ids[0]=operator("demo-west",west); ids[1]=operator("demo-east",east); ids[2]=operator("demo-backup",west);
            ids[3]=recipient("Demo East recipient","53.8010","-1.5500"); ids[4]=recipient("Demo West A","53.4810","-2.2430"); ids[5]=recipient("Demo West B","53.4820","-2.2440");
        });
        String password=UUID.randomUUID()+" demo passphrase"; // Ephemeral; never printed or written to evidence.
        accounts.createAdministrator("demo.admin",password);
        accounts.createDriver("demo.west",password,ids[0]); accounts.createDriver("demo.east",password,ids[1]); accounts.createDriver("demo.backup",password,ids[2]);
        var admin=new Session("demo.admin",password); var east=new Session("demo.east",password);
        step("Seed fictional depots/drivers/recipients and authenticate",Map.of("warehouses",2,"drivers",3,"recipients",3));
        int first=prepare(admin,ids[3],"09:30:00");
        var generated=admin.post("/api/admin/routes/generate",Map.of("scheduledDate",DAY.toString()),201);
        assertThat(generated.get("routes").size()).isEqualTo(1); var route=generated.get("routes").get(0);
        assertThat(route.get("operatorId").asInt()).isEqualTo(ids[1]); int firstRoute=route.get("routeId").asInt();
        var saved=admin.get("/api/admin/routes/"+firstRoute);
        assertThat(saved.get("deliveries").get(0).get("fromPrevDurationText").asText()).startsWith("SIMULATED DEMO");
        int calls=simulated.calls.get();
        var early=admin.post("/api/admin/routes/"+firstRoute+"/assessment",Map.of("departure",DAY+"T08:00:00","serviceDurationSeconds",600),200);
        var late=admin.post("/api/admin/routes/"+firstRoute+"/assessment",Map.of("departure",DAY+"T10:00:00","serviceDurationSeconds",600),200);
        assertThat(early.get("stops").get(0).get("waitingSeconds").asLong()).isEqualTo(3000);
        assertThat(late.get("stops").get(0).get("latenessSeconds").asLong()).isEqualTo(2400);
        assertThat(early.get("hypothetical").asBoolean()).isTrue(); assertThat(simulated.calls.get()).isEqualTo(calls);
        assertThat(admin.get("/api/admin/routes/"+firstRoute)).isEqualTo(saved);
        step("Select nearby higher-ID driver and compare explicit departure assumptions",Map.of("routeId",firstRoute,"selectedOperatorId",ids[1],"waitingAt0800Seconds",3000,"latenessAt1000Seconds",2400,"extraAssessmentProviderCalls",0));
        east.send("PATCH","/api/driver/deliveries/"+first+"/status","{\"deliveryStatus\":\"COMPLETED\"}","application/json",200);
        assertThat(admin.get("/api/admin/routes/"+firstRoute).get("routeStatus").asText()).isEqualTo("COMPLETED");
        assertThat(admin.get("/api/admin/operators/"+ids[1]).get("availabilityStatus").asText()).isEqualTo("AVAILABLE");
        step("Complete a route as its authenticated driver and release the driver",Map.of("completedRouteId",firstRoute));
        for(int recipient:new int[]{ids[4],ids[5],ids[4],ids[3],ids[3]}) prepare(admin,recipient,"17:00:00");
        var batch=admin.post("/api/admin/routes/generate",Map.of("scheduledDate",DAY.toString()),201).get("routes");
        assertThat(batch.size()).isEqualTo(3); var counts=new ArrayList<Integer>(); Set<Integer> coverage=new HashSet<>();
        var sessions=Map.of(ids[0],new Session("demo.west",password),ids[1],east,ids[2],new Session("demo.backup",password));
        for(var header:batch) {
            int routeId=header.get("routeId").asInt(),operator=header.get("operatorId").asInt(); counts.add(header.get("stopCount").asInt());
            var detail=sessions.get(operator).get("/api/driver/routes/"+routeId);
            for(var stop:detail.get("deliveries")) {
                int delivery=stop.get("deliveryId").asInt(); assertThat(coverage.add(delivery)).isTrue();
                sessions.get(operator).send("PATCH","/api/driver/deliveries/"+delivery+"/status","{\"deliveryStatus\":\"COMPLETED\"}","application/json",200);
            }
            assertThat(admin.get("/api/admin/routes/"+routeId).get("routeStatus").asText()).isEqualTo("COMPLETED");
            assertThat(admin.get("/api/admin/operators/"+operator).get("availabilityStatus").asText()).isEqualTo("AVAILABLE");
        }
        assertThat(coverage).hasSize(5); assertThat(counts).containsExactlyInAnyOrder(2,2,1);
        assertThat(admin.get("/api/admin/routes").get("totalElements").asLong()).isEqualTo(4);
        assertThat(simulated.calls.get()).isLessThanOrEqualTo(6);
        step("Balance five deliveries, complete owned work and retain route history",Map.of("stopCounts",counts,"completedDeliveries",6,"preservedRoutes",4,"simulatedProviderCalls",simulated.calls.get()));
        report.put("complete",true); save();
    }
}
