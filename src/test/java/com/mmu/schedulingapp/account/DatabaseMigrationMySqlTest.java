package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.SchedulingAppApplication;
import com.mmu.schedulingapp.database.SchemaAdoption;
import java.sql.*;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="account.mysql.url", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_foundation_test")
class DatabaseMigrationMySqlTest {
    String url;
    @BeforeEach void database() throws Exception {
        String name = "migration_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var db = connection(System.getProperty("account.mysql.url")); var statement = db.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        url = System.getProperty("account.mysql.url").replace("account_foundation_test", name);
    }
    @AfterEach void drop() throws Exception {
        if (url != null) try (var db = connection(url); var statement = db.createStatement()) {
            statement.execute("DROP DATABASE " + db.getCatalog());
        }
    }
    static Connection connection(String url) throws SQLException { return DriverManager.getConnection(url,"root","disposable-test-password"); }
    Flyway flyway() { return Flyway.configure().dataSource(url,"root","disposable-test-password").cleanDisabled(true).baselineOnMigrate(false).load(); }
    org.springframework.context.ConfigurableApplicationContext application(String... overrides) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(
                "--spring.datasource.url="+url,"--spring.datasource.username=root","--spring.datasource.password=disposable-test-password",
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver","--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                "--spring.main.banner-mode=off","--logging.level.root=OFF"));
        arguments.addAll(java.util.List.of(overrides));
        return new SpringApplicationBuilder(SchedulingAppApplication.class).web(WebApplicationType.NONE)
                .profiles("java25-test","schema-managed").run(arguments.toArray(String[]::new));
    }
    void sql(String query) throws Exception { try (var db=connection(url); var s=db.createStatement()) { s.execute(query); } }
    int count(String table) throws Exception { try (var db=connection(url); var s=db.createStatement(); var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)) { rows.next(); return rows.getInt(1); } }

    @Test void freshInstallValidatesWithHibernateAndRestartPreservesData() throws Exception {
        try (var app=application()) { assertThat(app.getBean(AccountRepository.class).count()).isZero(); }
        sql("INSERT INTO warehouses(name,address,latitude,longitude) VALUES ('Preserved warehouse','Test address',53.1,-2.1)");
        assertThat(count("flyway_schema_history")).isEqualTo(1);
        try (var app=application()) { assertThat(app.isActive()).isTrue(); }
        assertThat(count("warehouses")).isEqualTo(1);
        assertThat(count("flyway_schema_history")).isEqualTo(1);
    }

    @Test void nonEmptyUnversionedDatabaseIsRefusedWithoutChanges() throws Exception {
        sql("CREATE TABLE unrelated(id INT PRIMARY KEY)"); sql("INSERT INTO unrelated VALUES(42)");
        assertThatThrownBy(() -> application()).hasStackTraceContaining("non-empty schema");
        assertThat(count("unrelated")).isEqualTo(1);
        try (var db=connection(url)) { assertThat(SchemaAdoption.inspect(db).get("tables")).hasSize(1); }
    }

    @Test void reviewedLegacySchemaCanBeExplicitlyAdoptedWithoutLosingWork() throws Exception {
        // Independent legacy path: Hibernate base plus the previously reviewed account SQL.
        try (var app=application("--spring.flyway.enabled=false","--spring.jpa.hibernate.ddl-auto=create")) { assertThat(app.isActive()).isTrue(); }
        // Hibernate 7 adds an order-column check absent from the reviewed Hibernate 6 schema.
        // Remove only that generated addition when reconstructing the historical fixture.
        try (var db=connection(url); var statement=db.createStatement();
             var checks=statement.executeQuery("SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='route_deliveries' AND tc.CONSTRAINT_TYPE='CHECK'")) {
            var names=new java.util.ArrayList<String>();
            while(checks.next()) {
                assertThat(checks.getString(2)).isEqualTo("(`delivery_order` >= 0)");
                names.add(checks.getString(1));
            }
            assertThat(names).hasSize(1);
            for(var name:names) sql("ALTER TABLE route_deliveries DROP CHECK `"+name.replace("`","``")+"`");
        }
        // Hibernate 6 generated microsecond TIME columns; Hibernate 7 defaults LocalTime to TIME(0).
        sql("ALTER TABLE deliveries MODIFY start_time_window TIME(6), MODIFY end_time_window TIME(6)");
        sql("DROP TABLE user_accounts");
        sql(Files.readString(Path.of("db/changes/001-create-user-accounts.sql")));
        sql("DROP TABLE account_audit_events");
        for (String change:Files.readString(Path.of("db/changes/002-account-management.sql")).split(";")) if(!change.isBlank()) sql(change);
        sql("INSERT INTO warehouses(warehouse_id,name,address,latitude,longitude) VALUES(1,'Legacy warehouse','Test',53.1,-2.1)");
        sql("INSERT INTO operators(operator_id,name,phone_number,email,warehouse_id,availability_status) VALUES(1,'Legacy driver','123','driver@example.test',1,'ON_DELIVERY')");
        sql("INSERT INTO recipients(recipient_id,first_name,last_name,address,latitude,longitude,priority_level) VALUES(1,'Legacy','Recipient','Test',53.2,-2.2,'MEDIUM')");
        sql("INSERT INTO deliveries(delivery_id,recipient_id,operator_id,scheduled_date,priority_level,delivery_status) VALUES(1,1,1,'2026-10-14','MEDIUM','IN_PROGRESS')");
        sql("INSERT INTO routes(route_id,operator_id,scheduled_date,route_status) VALUES(1,1,'2026-10-14','IN_PROGRESS')");
        sql("INSERT INTO route_deliveries(route_id,delivery_order,delivery_id) VALUES(1,0,1)");
        sql("INSERT INTO user_accounts(username,password_hash,role,operator_id) VALUES('legacy.driver','{bcrypt}$2a$12$"+"a".repeat(53)+"','DRIVER',1)");
        sql("INSERT INTO account_audit_events(actor_account_id,target_account_id,action,occurred_at) VALUES(1,1,'CREATED',CURRENT_TIMESTAMP(6))");
        var original = dataSnapshot();
        assertThat(runTool("check")).contains("No writes performed");
        assertThatThrownBy(() -> flyway().migrate()).hasMessageContaining("non-empty schema");
        assertThat(runTool("baseline-version-1")).contains("Recorded baseline version 1");
        try (var app=application()) { assertThat(app.getBean(AccountRepository.class).findByUsername("legacy.driver")).isPresent(); }
        for (String table:java.util.List.of("warehouses","operators","recipients","deliveries","routes","route_deliveries","user_accounts","account_audit_events")) assertThat(count(table)).as(table).isEqualTo(1);
        assertThat(count("flyway_schema_history")).isEqualTo(1);
        assertThat(dataSnapshot()).isEqualTo(original);
    }

    java.util.Map<String, java.util.List<String>> dataSnapshot() throws Exception {
        var snapshot = new java.util.TreeMap<String, java.util.List<String>>();
        for (String table:java.util.List.of("warehouses","operators","recipients","deliveries","routes","route_deliveries","user_accounts","account_audit_events")) {
            var values = new java.util.ArrayList<String>();
            try (var db=connection(url); var statement=db.createStatement(); var rows=statement.executeQuery("SELECT * FROM "+table)) {
                while(rows.next()) {
                    var fields = new java.util.ArrayList<String>();
                    for(int i=1;i<=rows.getMetaData().getColumnCount();i++) fields.add(String.valueOf(rows.getObject(i)));
                    values.add(String.join("|",fields));
                }
            }
            java.util.Collections.sort(values); snapshot.put(table, values);
        }
        return snapshot;
    }

    String runTool(String command) throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),SchemaAdoption.class.getName(),command).redirectErrorStream(true);
        builder.environment().put("SCHEMA_DATABASE_URL",url);
        builder.environment().put("SCHEMA_DATABASE_USER","root");
        builder.environment().put("SCHEMA_DATABASE_PASSWORD","disposable-test-password");
        var output = Files.createTempFile("schema-tool-", ".log");
        try {
            var process=builder.redirectOutput(output.toFile()).start();
            try {
                assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(Files.readString(output)).isZero();
                return Files.readString(output);
            } finally { if(process.isAlive()) { process.destroyForcibly(); process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS); } }
        } finally { Files.deleteIfExists(output); }
    }

    @Test void incompatibleSchemaFailsReadOnlyPreflightAndHibernateValidation() throws Exception {
        flyway().migrate(); sql("ALTER TABLE deliveries DROP COLUMN from_prev_duration_seconds");
        sql("DROP TABLE flyway_schema_history");
        try (var db=connection(url)) { assertThatThrownBy(() -> SchemaAdoption.verify(db)).hasMessageContaining("columns"); }
        assertThatThrownBy(() -> application("--spring.flyway.enabled=false")).hasStackTraceContaining("from_prev_duration_seconds");
        try (var db=connection(url)) { assertThat(SchemaAdoption.inspect(db).get("tables")).hasSize(8); }
    }

    @Test void missingAccountConstraintFailsPreflightEvenWhenHibernateWouldAcceptColumns() throws Exception {
        flyway().migrate(); sql("DROP TABLE flyway_schema_history"); sql("ALTER TABLE user_accounts DROP CHECK ck_account_role_link");
        try (var db=connection(url)) { assertThatThrownBy(() -> SchemaAdoption.verify(db)).hasMessageContaining("checks"); }
    }

    @Test void legacyLatin1ClientDDLCanBeCheckedReadOnly() throws Exception {
        try (var db=connection(url); var statement=db.createStatement()) {
            statement.execute("SET NAMES latin1");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(db,
                    new org.springframework.core.io.ClassPathResource("db/migration/V1__initial_schema.sql"));
        }
        try (var db=connection(url)) { SchemaAdoption.verify(db); }
    }

    @Test void missingForeignKeyFailsAdoptionPreflight() throws Exception {
        flyway().migrate(); sql("DROP TABLE flyway_schema_history"); sql("ALTER TABLE deliveries DROP FOREIGN KEY fk_delivery_recipient");
        try (var db=connection(url)) { assertThatThrownBy(() -> SchemaAdoption.verify(db)).hasMessageContaining("foreignKeys"); }
    }

    @Test void crossDatabaseForeignKeyIsRejectedEvenWhenDatabaseNamesDifferOnlyInCase() throws Exception {
        flyway().migrate(); sql("DROP TABLE flyway_schema_history");
        String external;
        try (var db=connection(url)) { external=db.getCatalog().toUpperCase(java.util.Locale.ROOT); }
        sql("CREATE DATABASE " + external);
        try {
            sql("CREATE TABLE " + external + ".recipients(recipient_id INT PRIMARY KEY)");
            sql("ALTER TABLE deliveries DROP FOREIGN KEY fk_delivery_recipient");
            sql("ALTER TABLE deliveries ADD CONSTRAINT fk_delivery_recipient FOREIGN KEY(recipient_id) REFERENCES " + external + ".recipients(recipient_id)");
            try (var db=connection(url)) { assertThatThrownBy(() -> SchemaAdoption.verify(db)).hasMessageContaining("foreignKeys"); }
        } finally {
            sql("ALTER TABLE deliveries DROP FOREIGN KEY fk_delivery_recipient");
            sql("DROP DATABASE " + external);
        }
    }

    @Test void managedStartupRejectsUnsafeOverridesBeforeCreatingTables() throws Exception {
        for (String override:java.util.List.of("--spring.jpa.hibernate.ddl-auto=update","--spring.flyway.baseline-on-migrate=true","--spring.flyway.clean-disabled=false","--spring.flyway.validate-on-migrate=false")) {
            assertThatThrownBy(() -> application(override)).hasStackTraceContaining("IllegalStateException");
            try (var db=connection(url)) { assertThat(SchemaAdoption.inspect(db).get("tables")).isEmpty(); }
        }
    }

    @Test void changedMigrationChecksumIsRejected() throws Exception {
        flyway().migrate(); sql("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
        assertThatThrownBy(() -> flyway().migrate()).hasMessageContaining("checksum mismatch");
    }

    @Test void freshSchemaMatchesReadOnlyAdoptionContract() throws Exception {
        flyway().migrate(); sql("DROP TABLE flyway_schema_history");
        try (var db=connection(url)) {
            SchemaAdoption.verify(db);
        }
    }
}
