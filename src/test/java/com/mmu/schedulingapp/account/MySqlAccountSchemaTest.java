package com.mmu.schedulingapp.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Runs only against the disposable database created by scripts/test-account-mysql.sh. */
@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class MySqlAccountSchemaTest {
    static Connection db;
    @BeforeAll static void createSchema() throws Exception {
        db = DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password");
        execute("CREATE TABLE operators (operator_id INT PRIMARY KEY)");
        execute(Files.readString(Path.of("db/changes/001-create-user-accounts.sql")));
    }
    @AfterAll static void close() throws Exception { if(db != null) db.close(); }
    @BeforeEach void reset() throws Exception { execute("DELETE FROM user_accounts"); execute("DELETE FROM operators"); execute("INSERT INTO operators VALUES (1),(2)"); }
    static void execute(String sql) throws SQLException { try(var statement=db.createStatement()) { statement.execute(sql); } }
    static void insert(String username,String role,String operator,String slot) throws SQLException {
        execute("INSERT INTO user_accounts(username,password_hash,role,operator_id,bootstrap_slot) VALUES ('"+username+"','test-hash','"+role+"',"+operator+","+slot+")");
    }
    @Test void acceptsAdministratorAndLinkedDriver() throws Exception { insert("admin.one","ADMIN","NULL","1"); insert("driver.one","DRIVER","1","NULL"); try(var s=db.createStatement(); var rows=s.executeQuery("SELECT COUNT(*) FROM user_accounts WHERE enabled=TRUE")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); } }
    @Test void rejectsDuplicateUsername() throws Exception { insert("admin.one","ADMIN","NULL","NULL"); assertThatThrownBy(() -> insert("admin.one","ADMIN","NULL","NULL")).isInstanceOf(SQLException.class); }
    @Test void rejectsDuplicateDriverLink() throws Exception { insert("driver.one","DRIVER","1","NULL"); assertThatThrownBy(() -> insert("driver.two","DRIVER","1","NULL")).isInstanceOf(SQLException.class); }
    @Test void rejectsMissingOperator() { assertThatThrownBy(() -> insert("driver.one","DRIVER","999","NULL")).isInstanceOf(SQLException.class); }
    @Test void preventsDeletingLinkedOperator() throws Exception { insert("driver.one","DRIVER","1","NULL"); assertThatThrownBy(() -> execute("DELETE FROM operators WHERE operator_id=1")).isInstanceOf(SQLException.class); }
    @Test void rejectsDriverWithoutLink() { assertThatThrownBy(() -> insert("driver.one","DRIVER","NULL","NULL")).isInstanceOf(SQLException.class); }
    @Test void rejectsAdminWithLink() { assertThatThrownBy(() -> insert("admin.one","ADMIN","1","NULL")).isInstanceOf(SQLException.class); }
    @Test void rejectsNonCanonicalRole() { assertThatThrownBy(() -> insert("admin.one","admin","NULL","NULL")).isInstanceOf(SQLException.class); }
    @Test void validatesMigratedSchemaWithRealHibernateAndPersistsAccounts() throws Exception {
        execute("CREATE DATABASE account_foundation_jpa_test");
        String url = System.getProperty("account.mysql.url").replace("account_foundation_test", "account_foundation_jpa_test");
        // Generate the existing application schema, then apply the reviewed account change.
        try (var context = application(url, "create")) {
            assertThat(context.getBean(AccountRepository.class).count()).isZero();
        }
        try (var connection = DriverManager.getConnection(url, "root", "disposable-test-password");
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE user_accounts");
            statement.execute(Files.readString(Path.of("db/changes/001-create-user-accounts.sql")));
            statement.execute("INSERT INTO user_accounts(username,password_hash,role,enabled) VALUES ('legacy.admin','{bcrypt}$2a$12$" + "a".repeat(53) + "','ADMIN',TRUE)");
            statement.execute("DROP TABLE account_audit_events");
            String migration=Files.readString(Path.of("db/changes/002-account-management.sql"));
            for(String change:migration.split(";")) if(!change.isBlank()) statement.execute(change);
        }
        try (var context = application(url, "validate")) {
            var service = context.getBean(AccountService.class);
            var accounts = context.getBean(AccountRepository.class);
            var operators = context.getBean(com.mmu.schedulingapp.repository.OperatorRepository.class);
            var operator = new com.mmu.schedulingapp.model.Operator();
            operator.setName("Test driver"); operator.setPhoneNumber("123"); operator.setEmail("mysql@example.test");
            operators.saveAndFlush(operator);
            var legacy = accounts.findByUsername("legacy.admin").orElseThrow();
            assertThat(legacy.getAuthenticationRevision()).isZero();
            assertThat(legacy.isPasswordChangeRequired()).isFalse();
            assertThat(legacy.getTemporaryPasswordExpiresAt()).isNull();
            assertThat(legacy.isEnabled()).isTrue();
            var admin = service.createAdministrator("mysql.admin", "mysql private test passphrase");
            var driver = service.createDriver("mysql.driver", "mysql driver test passphrase", operator.getOperatorId());
            assertThat(driver.operatorId()).isEqualTo(operator.getOperatorId());
            assertThat(accounts.count()).isEqualTo(3);
            var encoder = context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            assertThat(encoder.matches("mysql private test passphrase", accounts.findById(admin.id()).orElseThrow().getPasswordHash())).isTrue();
            assertThat(service.provisionInitialAdministrator(null, null)).isEmpty();
        }
    }
    static org.springframework.context.ConfigurableApplicationContext application(String url, String ddl) {
        return new org.springframework.boot.builder.SpringApplicationBuilder(com.mmu.schedulingapp.SchedulingAppApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("java25-test").run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=root",
                        "--spring.datasource.password=disposable-test-password",
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                        "--spring.jpa.hibernate.ddl-auto=" + ddl,
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
    @Test void rejectsUnknownRole() { assertThatThrownBy(() -> insert("unknown.one","SUPERUSER","NULL","NULL")).isInstanceOf(SQLException.class); }
    @Test void onlyOneInitialAdministratorSlot() throws Exception { insert("admin.one","ADMIN","NULL","1"); assertThatThrownBy(() -> insert("admin.two","ADMIN","NULL","1")).isInstanceOf(SQLException.class); }
    @Test void rejectsInvalidBootstrapMarker() { assertThatThrownBy(() -> insert("admin.one","ADMIN","NULL","2")).isInstanceOf(SQLException.class); assertThatThrownBy(() -> insert("driver.one","DRIVER","1","1")).isInstanceOf(SQLException.class); }
    @Test void concurrentBootstrapHasExactlyOneWinner() throws Exception {
        try(var second=DriverManager.getConnection(System.getProperty("account.mysql.url"),"root","disposable-test-password")) {
            db.setAutoCommit(false);
            insert("admin.one","ADMIN","NULL","1");
            var started = new java.util.concurrent.CountDownLatch(1);
            var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                started.countDown();
                try(var s=second.createStatement()) { s.execute("INSERT INTO user_accounts(username,password_hash,role,bootstrap_slot) VALUES ('admin.two','test-hash','ADMIN',1)"); return (SQLException) null; }
                catch(SQLException failure) { return failure; }
            });
            try {
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // The first transaction still holds the unique slot, so the second cannot finish.
                assertThatThrownBy(() -> result.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                db.commit();
                SQLException failure = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
                org.junit.jupiter.api.Assertions.assertNotNull(failure);
                assertThat(failure.getErrorCode()).isEqualTo(1062); // MySQL duplicate-key violation
            }
            finally { db.rollback(); db.setAutoCommit(true); }
        }
    }
}
