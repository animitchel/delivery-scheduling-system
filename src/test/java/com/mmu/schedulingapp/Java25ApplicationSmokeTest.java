package com.mmu.schedulingapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("java25-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:java25-smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "server.address=127.0.0.1"
})
class Java25ApplicationSmokeTest {
    @Autowired
    private TestRestTemplate http;

    @Test
    void startsOnJava25AndServesAdminPage() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
        var response = http.getForEntity("/html/admin.html", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Scheduling App", "recipientForm", "deliveryForm");
    }

    @ParameterizedTest
    @ValueSource(strings = {"operators", "recipients", "warehouses", "deliveries", "routes"})
    void servesEmptyCollectionsThroughTheRunningApplication(String resource) {
        var response = http.getForEntity("/api/v1/scheduling-app/" + resource, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }
}
