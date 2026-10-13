package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.RouteService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:admin-route-directory;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"})
class AdminRouteDirectoryIntegrationTest {
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired AccountRepository accounts;
    @Autowired AccountAuditRepository audits;
    @Autowired RouteService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory factory;
    @Autowired MockMvc mvc;
    AccountPrincipal admin, driver;
    Integer populated;
    @BeforeEach void fixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); audits.deleteAll(); accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush();
            var operator = new Operator(); operator.setName("Test driver"); operator.setEmail("routes@example.test"); operator.setPhoneNumber("123"); operators.saveAndFlush(operator);
            admin = new AccountPrincipal(accounts.saveAndFlush(new UserAccount("directory.admin", "{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
            driver = new AccountPrincipal(accounts.saveAndFlush(new UserAccount("directory.driver", "{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false)));
            var recipient = new Recipient(); recipient.setFirstName("Test"); recipient.setLastName("Recipient"); recipient.setAddress("Private address"); recipient.setLatitude(BigDecimal.ZERO); recipient.setLongitude(BigDecimal.ZERO); recipients.saveAndFlush(recipient);
            for (int i=0;i<23;i++) {
                var route = new Route(); route.setOperator(operator); route.setScheduledDate(LocalDate.of(2026,10,13)); route.setRouteStatus(Route.RouteStatus.IN_PROGRESS); route.setDeliveries(new ArrayList<>());
                if (i==22) for (var status:List.of(Delivery.DeliveryStatus.IN_PROGRESS,Delivery.DeliveryStatus.COMPLETED,Delivery.DeliveryStatus.CANCELLED)) {
                    var delivery = new Delivery(); delivery.setRecipientId(recipient); delivery.setOperatorId(operator); delivery.setDeliveryStatus(status); delivery.setPriorityLevel(Delivery.PriorityLevel.MEDIUM); deliveries.saveAndFlush(delivery); route.getDeliveries().add(delivery);
                }
                routes.saveAndFlush(route); if(i==22) populated=route.getRouteId();
            }
        });
    }
    @Test void directoryIsBoundedStableAndLoadsNoEntitiesOrOffPageStops() {
        var stats=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        try {
            var result=service.directory(0,10);
            assertThat(result.routes()).hasSize(10); assertThat(result.totalElements()).isEqualTo(23); assertThat(result.totalPages()).isEqualTo(3);
            assertThat(result.routes().getFirst().routeId()).isEqualTo(populated);
            assertThat(result.routes().getFirst().stopCount()).isEqualTo(3); assertThat(result.routes().getFirst().finishedCount()).isEqualTo(2);
            assertThat(stats.getEntityLoadCount()).isZero(); assertThat(stats.getCollectionFetchCount()).isZero(); assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
            var next=service.directory(1,10); assertThat(next.routes().getFirst().routeId()).isLessThan(result.routes().getLast().routeId());
            assertThat(next.routes()).allSatisfy(item -> { assertThat(item.stopCount()).isZero(); assertThat(item.finishedCount()).isZero(); });
            assertThat(service.directory(3,10).routes()).isEmpty();
        } finally { stats.setStatisticsEnabled(false); }
    }
    @Test void adminCanReadDirectoryAndOrderedDetailsWithoutOpenSessionInView() throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/routes/directory").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.routes.length()").value(20)).andExpect(jsonPath("$.routes[0].deliveries").doesNotExist()).andExpect(jsonPath("$.routes[0].finishedCount").value(2));
        mvc.perform(get("/api/v1/scheduling-app/routes/"+populated).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries.length()").value(3)).andExpect(jsonPath("$.deliveries[0].status").value("IN_PROGRESS"));
    }
    @Test void anonymousAndDriversCannotReadAdministratorDirectory() throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/routes/directory")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/scheduling-app/routes/directory").with(user(driver))).andExpect(status().isForbidden());
    }
    @ParameterizedTest @CsvSource({"-1,20","10001,20","0,0","0,51"})
    void invalidBoundsAreRejected(int page,int size) throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/routes/directory").param("page",String.valueOf(page)).param("size",String.valueOf(size)).with(user(admin))).andExpect(status().isBadRequest());
    }
    @Test void emptyDirectoryRetainsPageMetadata() {
        routes.deleteAll(); routes.flush(); var result=service.directory(0,20); assertThat(result.routes()).isEmpty(); assertThat(result.totalElements()).isZero(); assertThat(result.totalPages()).isZero();
    }
}
