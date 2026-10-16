package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.utility.MapsProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:window-assessment;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
class RouteWindowAssessmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired RouteRepository routes;
    @Autowired DeliveryRepository deliveries;
    @Autowired OperatorRepository operators;
    @Autowired RecipientRepository recipients;
    @Autowired WarehouseRepository warehouses;
    @Autowired AccountRepository accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MapsProvider maps;
    AccountPrincipal admin,driver;
    int routeId,firstId,secondId;
    static final String BODY="{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":600}";
    void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()); }
    String path() { return "/api/admin/routes/"+routeId+"/assessment"; }
    @BeforeEach void setup() {
        tx(() -> {
            routes.deleteAll(); routes.flush(); accounts.deleteAll(); accounts.flush(); deliveries.deleteAll(); deliveries.flush();
            operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); warehouses.deleteAll(); warehouses.flush();
            var w=new Warehouse(); w.setName("private depot"); w.setAddress("private address"); w.setLatitude(BigDecimal.ZERO); w.setLongitude(BigDecimal.ZERO); warehouses.saveAndFlush(w);
            var o=new Operator(); o.setName("private operator"); o.setEmail("window@test.local"); o.setPhoneNumber("private phone"); o.setWarehouse(w); o.setAvailabilityStatus(Operator.AvailabilityStatus.ON_DELIVERY); operators.saveAndFlush(o);
            var p=new Recipient(); p.setFirstName("private recipient"); p.setLastName("private surname"); p.setAddress("private address"); p.setLatitude(BigDecimal.ONE); p.setLongitude(BigDecimal.ONE); recipients.saveAndFlush(p);
            var members=new ArrayList<Delivery>();
            for(int i=0;i<4;i++) {
                var d=new Delivery(); d.setRecipientId(p); d.setOperatorId(o); d.setScheduledDate(LocalDate.of(2026,1,2));
                d.setStartTimeWindow(LocalTime.of(9,0)); d.setEndTimeWindow(LocalTime.of(17,0)); d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);
                d.setDeliveryStatus(Delivery.DeliveryStatus.IN_PROGRESS); d.setFromPrevDurationSeconds(0); deliveries.saveAndFlush(d); members.add(d);
            }
            // Route order differs from primary-key order.
            Collections.swap(members,0,1); firstId=members.get(0).getDeliveryId(); secondId=members.get(1).getDeliveryId();
            members.get(0).setFromPrevDurationSeconds(900); members.get(0).setEndTimeWindow(LocalTime.of(10,0));
            members.get(1).setFromPrevDurationSeconds(600); members.get(1).setEndTimeWindow(LocalTime.of(9,15));
            members.get(2).setFromPrevDurationSeconds(null);
            var r=new Route(); r.setOperator(o); r.setScheduledDate(LocalDate.of(2026,1,3)); r.setRouteStatus(Route.RouteStatus.IN_PROGRESS); r.setDeliveries(members); routeId=routes.saveAndFlush(r).getRouteId();
            admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("window.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
            driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("window.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,o,false)));
        }); reset(maps);
    }
    @AfterEach void noProviderCalls() { verifyNoInteractions(maps); }
    List<List<Map<String,Object>>> savedState() {
        return List.of(jdbc.queryForList("select * from routes order by route_id"),jdbc.queryForList("select * from deliveries order by delivery_id"),
                jdbc.queryForList("select * from operators order by operator_id"),jdbc.queryForList("select * from route_deliveries order by route_id,delivery_order"));
    }
    @Test void readsSavedOrderAndReturnsHypotheticalTimelineWithoutAnyStateChange() throws Exception {
        var before=savedState();
        var response=mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.hypothetical").value(true)).andExpect(jsonPath("$.estimateSource").value("SAVED_TRAVEL_ESTIMATES"))
                .andExpect(jsonPath("$.windowPolicy").value("SERVICE_START_INCLUSIVE"))
                .andExpect(jsonPath("$.departure").value("2026-01-02T08:00:00"))
                .andExpect(jsonPath("$.serviceDurationSeconds").value(600)).andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.stops[0].deliveryId").value(firstId)).andExpect(jsonPath("$.stops[0].position").value(1))
                .andExpect(jsonPath("$.stops[0].arrival").value("2026-01-02T08:15:00"))
                .andExpect(jsonPath("$.stops[0].waitingSeconds").value(2700))
                .andExpect(jsonPath("$.stops[1].deliveryId").value(secondId)).andExpect(jsonPath("$.stops[1].status").value("LATE"))
                .andExpect(jsonPath("$.stops[1].latenessSeconds").value(300))
                .andExpect(jsonPath("$.stops[2].status").value("UNKNOWN")).andExpect(jsonPath("$.stops[3].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.stops[3].arrival").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stops[3].latenessSeconds").value(org.hamcrest.Matchers.nullValue())).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("private", "window@test.local");
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY.replace("T08:00:00","T10:00:00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stops[0].status").value("LATE"));
        assertThat(savedState()).isEqualTo(before);
    }
    @Test void requiresAdministratorSessionAndCsrf() throws Exception {
        var before=savedState();
        mvc.perform(post(path()).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(user(driver)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post(path()).with(user(admin)).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        assertThat(savedState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"departure\":\"private-input\",\"serviceDurationSeconds\":0}",
            "{\"departure\":\"2026-01-02T08:00:00Z\",\"serviceDurationSeconds\":0}",
            "{\"departure\":\"2026-02-30T08:00:00\",\"serviceDurationSeconds\":0}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":-1}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":1.5}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":\"0\"}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":null}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"departure\":\"2026-01-02T09:00:00\",\"serviceDurationSeconds\":0}",
            "{\"departure\":\"2026-01-02T08:00:00\",\"serviceDurationSeconds\":0,\"unknown\":\"private-input\"}"})
    void rejectsInvalidAssumptionsWithoutExposingInputs(String body) throws Exception {
        var before=savedState();
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-input"))));
        assertThat(savedState()).isEqualTo(before);
    }
    @Test void rejectsBadIdentifiersAndReturnsNotFound() throws Exception {
        for(var id:List.of("0","-1","invalid")) mvc.perform(post("/api/admin/routes/"+id+"/assessment").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/routes/2147483647/assessment").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
    }
    @ParameterizedTest @ValueSource(strings={"PLANNED","IN_PROGRESS","COMPLETED"})
    void allRouteStatusesRemainHypothetical(String status) throws Exception {
        tx(() -> { var r=routes.findById(routeId).orElseThrow(); r.setRouteStatus(Route.RouteStatus.valueOf(status)); routes.saveAndFlush(r); });
        var before=savedState();
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.routeStatus").value(status)).andExpect(jsonPath("$.hypothetical").value(true));
        assertThat(savedState()).isEqualTo(before);
    }
    @Test void rejectsCorruptSavedWindowsAndOrderWithoutWrites() throws Exception {
        jdbc.update("update deliveries set start_time_window=null where delivery_id=?",firstId);
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict());
        jdbc.update("update deliveries set start_time_window='09:00:00' where delivery_id=?",firstId);
        jdbc.update("update route_deliveries set delivery_order=20 where route_id=? and delivery_order=0",routeId);
        var before=savedState();
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isConflict());
        assertThat(savedState()).isEqualTo(before);
    }
    @Test void refusesOversizedSavedRoutesBeforeProducingPartialResults() throws Exception {
        tx(() -> {
            var route=routes.findById(routeId).orElseThrow(); var recipient=recipients.findAll().get(0); var operator=operators.findAll().get(0);
            for(int i=route.getDeliveries().size();i<501;i++) {
                var d=new Delivery(); d.setRecipientId(recipient); d.setOperatorId(operator); d.setScheduledDate(LocalDate.of(2026,1,2));
                d.setStartTimeWindow(LocalTime.of(9,0)); d.setEndTimeWindow(LocalTime.of(17,0)); d.setPriorityLevel(Delivery.PriorityLevel.MEDIUM);
                deliveries.save(d); route.getDeliveries().add(d);
            }
            routes.saveAndFlush(route);
        });
        var before=savedState();
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(savedState()).isEqualTo(before);
    }
    @Test void rejectsOverflowingAssumptionsAndTrailingJson() throws Exception {
        for(var body:List.of(BODY.replace(":600",":9223372036854775807"),BODY+" {}",BODY.replace(":600",":9223372036854775808"),
                BODY.replace("08:00:00","08:00:00.1"))) {
            mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test void revokedSessionAndMandatoryPasswordSetupCannotAssessRoutes() throws Exception {
        var account=accounts.findById(admin.getAccountId()).orElseThrow();
        account.setTemporaryPassword(account.getPasswordHash(),Instant.now().plusSeconds(300)); accounts.saveAndFlush(account);
        mvc.perform(post(path()).with(user(new AccountPrincipal(account))).with(csrf()).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        account.changeEnabled(false); accounts.saveAndFlush(account);
        mvc.perform(post(path()).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
    }

}
