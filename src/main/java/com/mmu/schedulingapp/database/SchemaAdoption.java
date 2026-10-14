package com.mmu.schedulingapp.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Offline operator tool: check is read-only; baseline requires explicit confirmation. */
public final class SchemaAdoption {
    private SchemaAdoption() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("check", "baseline-version-1").contains(args[0])) {
            throw new IllegalArgumentException("Use check or baseline-version-1. Read DATABASE_MIGRATIONS.md first.");
        }
        String url = required("SCHEMA_DATABASE_URL");
        if (!url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("A MySQL JDBC URL is required.");
        String user = required("SCHEMA_DATABASE_USER"), password = required("SCHEMA_DATABASE_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            verify(connection);
        }
        if (args[0].equals("baseline-version-1")) {
            // This records adoption only; V1 is NOT executed against existing tables.
            Flyway.configure().dataSource(url, user, password).baselineVersion("1")
                    .baselineDescription("Explicitly checked existing application schema")
                    .baselineOnMigrate(false).cleanDisabled(true).load().baseline();
            System.out.println("Recorded baseline version 1. No application DDL or data was changed.");
        } else {
            System.out.println("Schema matches the version-1 contract. No writes performed.");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    public static void verify(Connection connection) throws Exception {
        Map<String, List<String>> expected;
        try (var input = SchemaAdoption.class.getResourceAsStream("/db/schema-v1-contract.json")) {
            if (input == null) throw new IllegalStateException("Missing version-1 schema contract.");
            expected = new ObjectMapper().readValue(input, new TypeReference<>() { });
        }
        Map<String, List<String>> actual = inspect(connection);
        for (String category : expected.keySet()) {
            if (!expected.get(category).equals(actual.get(category))) {
                throw new IllegalStateException("Schema incompatible with version 1: " + category
                        + ". Expected: " + expected.get(category) + "; actual: " + actual.get(category)
                        + ". Do not baseline or enable automatic repair.");
            }
        }
    }

    /** Only information_schema reads. Constraint names and optional non-unique indexes are ignored. */
    public static Map<String, List<String>> inspect(Connection connection) throws SQLException {
        String database = connection.getCatalog();
        if (database == null || database.isBlank()) throw new IllegalArgumentException("Select a database in the JDBC URL.");
        Map<String, List<String>> result = new TreeMap<>();
        result.put("tables", query(connection, database,
                "SELECT TABLE_NAME, TABLE_TYPE, COALESCE(ENGINE,'') FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME"));
        result.put("columns", query(connection, database,
                "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME,COLUMN_NAME"));
        result.put("uniqueKeys", query(connection, database,
                "SELECT TABLE_NAME,GROUP_CONCAT(CONCAT(COLUMN_NAME,IF(SUB_PART IS NULL,'',CONCAT(':prefix:',SUB_PART))) ORDER BY SEQ_IN_INDEX),IF(INDEX_NAME='PRIMARY','PRIMARY','UNIQUE') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND NON_UNIQUE=0 GROUP BY TABLE_NAME,INDEX_NAME ORDER BY TABLE_NAME,2,3"));
        result.put("foreignKeys", query(connection, database,
                "SELECT k.TABLE_NAME,GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION),k.REFERENCED_TABLE_NAME,GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION),r.DELETE_RULE,r.UPDATE_RULE,IF(CAST(k.REFERENCED_TABLE_SCHEMA AS BINARY)=CAST(k.TABLE_SCHEMA AS BINARY),'LOCAL','EXTERNAL') FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA=? GROUP BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.REFERENCED_TABLE_NAME,r.DELETE_RULE,r.UPDATE_RULE,k.REFERENCED_TABLE_SCHEMA,k.TABLE_SCHEMA ORDER BY 1,2,3"));
        result.put("checks", query(connection, database,
                "SELECT t.TABLE_NAME,c.CHECK_CLAUSE,t.ENFORCED FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=? ORDER BY t.TABLE_NAME,c.CHECK_CLAUSE"));
        result.put("roleCollation", query(connection, database,
                "SELECT CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='user_accounts' AND COLUMN_NAME='role'"));
        return result;
    }

    private static List<String> query(Connection connection, String database, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            try (var values = statement.executeQuery()) {
                int count = values.getMetaData().getColumnCount();
                while (values.next()) {
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        String value = values.getString(i);
                        // ADMIN/DRIVER are ASCII literals; Flyway and legacy JDBC connections
                        // can display different ASCII-compatible charset introducers without changing these checks.
                        if (sql.contains("CHECK_CLAUSE") && i == 2) {
                            for (String role : List.of("ADMIN", "DRIVER")) {
                                for (String charset : List.of("utf8mb4", "utf8mb3", "latin1")) {
                                    value = value.replace("_" + charset + "\\'" + role + "\\'", "_ascii\\'" + role + "\\'");
                                }
                            }
                        }
                        // MySQL treats NO ACTION and RESTRICT identically; names vary across schema origins.
                        columns.add("NO ACTION".equals(value) ? "RESTRICT" : value);
                    }
                    rows.add(String.join("|", columns));
                }
            }
        }
        Collections.sort(rows);
        return rows;
    }
}
