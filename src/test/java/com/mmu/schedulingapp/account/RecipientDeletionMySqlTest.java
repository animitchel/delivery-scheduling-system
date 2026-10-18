package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.SchedulingAppApplication;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.RecipientRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real sessions and HTTP error dispatch against a disposable MySQL foreign key. */
@EnabledIfSystemProperty(named="account.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class RecipientDeletionMySqlTest {
    static ServletWebServerApplicationContext context;
    static String url,base;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PATH="/api/v1/scheduling-app/recipients/";
    HttpClient http;
    String csrfHeader,csrfValue;
    int id;

    @BeforeAll static void application() throws Exception {
        try(var db=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");var sql=db.createStatement()) { sql.execute("CREATE DATABASE recipient_deletion_test"); }
        url=System.getProperty("account.mysql.url").replace("account_foundation_test","recipient_deletion_test");
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(SchedulingAppApplication.class)
                .profiles("java25-test","local").run("--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password",
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                        "--spring.jpa.hibernate.ddl-auto=create-drop","--spring.jpa.open-in-view=false","--server.address=127.0.0.1","--server.port=0",
                        "--spring.main.banner-mode=off","--logging.level.root=WARN");
        base="http://127.0.0.1:"+context.getWebServer().getPort();
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @BeforeEach void fixtures() throws Exception {
        try(var db=connection();var sql=db.createStatement()) { sql.executeUpdate("DELETE FROM route_deliveries");sql.executeUpdate("DELETE FROM routes");sql.executeUpdate("DELETE FROM deliveries");sql.executeUpdate("DELETE FROM recipients"); }
        var recipient=new Recipient();recipient.setFirstName("Private original");recipient.setLastName("Recipient");recipient.setAddress("Private stored address");
        recipient.setLatitude(BigDecimal.ONE);recipient.setLongitude(BigDecimal.TEN);
        id=context.getBean(RecipientRepository.class).saveAndFlush(recipient).getRecipientId();
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String username="delete.admin."+UUID.randomUUID(),password="disposable recipient deletion passphrase";
        context.getBean(AccountService.class).createAdministrator(username,password);
        token();
        var login=http.send(request("/api/auth/login").header(csrfHeader,csrfValue).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);token();
    }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)); }
    void token() throws Exception { var response=http.send(request("/api/auth/csrf").GET().build(),HttpResponse.BodyHandlers.ofString());var json=JSON.readTree(response.body());csrfHeader=json.get("headerName").asText();csrfValue=json.get("token").asText(); }
    HttpRequest deletion(int recipientId) { return request(PATH+recipientId).header(csrfHeader,csrfValue).DELETE().build(); }
    HttpResponse<String> delete(int recipientId) throws Exception { return http.send(deletion(recipientId),HttpResponse.BodyHandlers.ofString()); }
    Connection connection() throws SQLException { return DriverManager.getConnection(url,"root","disposable-test-password"); }
    void delivery(Statement sql,String status) throws SQLException {
        sql.executeUpdate("INSERT INTO deliveries(recipient_id,scheduled_date,start_time_window,end_time_window,priority_level,delivery_status,special_instructions) VALUES ("+id+",'2026-01-02','09:00:00','12:00:00','MEDIUM','"+status+"','Private delivery instructions')");
    }
    String snapshot() throws Exception {
        try(var db=connection();var sql=db.createStatement()) {
            StringBuilder state=new StringBuilder();
            for(String table:java.util.List.of("recipients","deliveries","routes","route_deliveries","operators")) {
                try(var rows=sql.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
                    while(rows.next()) for(int column=1;column<=rows.getMetaData().getColumnCount();column++) state.append(rows.getString(column)).append('|');
                }
            }
            return state.toString();
        }
    }
    void conflict(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("error").asText()).isEqualTo("Recipient is still referenced and cannot be deleted.");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain("Private", "SQL", "constraint", "fk_", "Exception");
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","IN_PROGRESS","COMPLETED","CANCELLED"})
    void referencedRecipientReturnsConflictWithoutChangingData(String status) throws Exception {
        try(var db=connection();var sql=db.createStatement()) {
            delivery(sql,status);
            if(!status.equals("PENDING")) {
                int operator=operator(sql);
                sql.executeUpdate("UPDATE deliveries SET operator_id="+operator+" WHERE recipient_id="+id);
                String routeStatus=status.equals("IN_PROGRESS") ? "IN_PROGRESS" : "COMPLETED";
                sql.executeUpdate("INSERT INTO routes(operator_id,scheduled_date,route_status) VALUES ("+operator+",'2026-01-02','"+routeStatus+"')");
                sql.executeUpdate("INSERT INTO route_deliveries(route_id,delivery_order,delivery_id) SELECT LAST_INSERT_ID(),0,delivery_id FROM deliveries WHERE recipient_id="+id);
            }
        }
        String before=snapshot();conflict(delete(id));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void unreferencedDeletionRetainsLegacyResponseAndMissingReturns404() throws Exception {
        var deleted=delete(id);assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(deleted.body()).get("recipientId").asInt()).isEqualTo(id);
        assertThat(context.getBean(RecipientRepository.class).existsById(id)).isFalse();
        assertThat(delete(id).statusCode()).isEqualTo(404);
    }
    @Test void unauthenticatedAndMissingCsrfRequestsCannotDelete() throws Exception {
        var anonymous=HttpClient.newHttpClient().send(request(PATH+id).DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isIn(401,403);
        assertThat(http.send(request(PATH+id).DELETE().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(context.getBean(RecipientRepository.class).existsById(id)).isTrue();
    }
    int operator(Statement sql) throws SQLException {
        sql.executeUpdate("INSERT INTO operators(name,phone_number,email,availability_status) VALUES ('Private operator','01234567890','"+UUID.randomUUID()+"@example.invalid','AVAILABLE')");
        try(var rows=sql.executeQuery("SELECT LAST_INSERT_ID()")) { rows.next();return rows.getInt(1); }
    }
    @Test void driverWithValidSessionAndCsrfCannotDeleteRecipient() throws Exception {
        int operator;
        try(var db=connection();var sql=db.createStatement()) { operator=operator(sql); }
        String username="delete.driver."+UUID.randomUUID(),password="disposable recipient deletion passphrase";
        context.getBean(AccountService.class).createDriver(username,password,operator);
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();token();
        var login=http.send(request("/api/auth/login").header(csrfHeader,csrfValue).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username="+username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);token();
        assertThat(delete(id).statusCode()).isEqualTo(403);
        assertThat(context.getBean(RecipientRepository.class).existsById(id)).isTrue();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void deletionWaitsForConcurrentReferenceAndHonoursCommitOrRollback(boolean commit) throws Exception {
        try(var db=connection();var sql=db.createStatement()) {
            db.setAutoCommit(false);delivery(sql,"PENDING");
            int blocker;
            try(var row=sql.executeQuery("SELECT CONNECTION_ID()")) { row.next();blocker=row.getInt(1); }
            var pending=http.sendAsync(deletion(id),HttpResponse.BodyHandlers.ofString());
            try { awaitWaiter(blocker);assertThat(pending.isDone()).isFalse(); }
            finally { if(commit) db.commit(); else db.rollback(); }
            var response=pending.get(10,TimeUnit.SECONDS);
            if(commit) {
                conflict(response);
                assertThat(context.getBean(RecipientRepository.class).existsById(id)).isTrue();
                try(var check=connection();var statement=check.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM deliveries WHERE recipient_id="+id)) { rows.next();assertThat(rows.getInt(1)).isEqualTo(1); }
            } else {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(context.getBean(RecipientRepository.class).existsById(id)).isFalse();
            }
        }
    }
    void awaitWaiter(int blocker) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try(var db=connection();var sql=db.createStatement()) {
            do {
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits WHERE BLOCKING_ENGINE_TRANSACTION_ID IN (SELECT trx_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id="+blocker+")")) {
                    rows.next();if(rows.getInt(1)>0) return;
                }
                Thread.sleep(10);
            } while(System.nanoTime()<deadline);
        }
        throw new AssertionError("Deletion did not wait on the concurrent delivery reference.");
    }
}
