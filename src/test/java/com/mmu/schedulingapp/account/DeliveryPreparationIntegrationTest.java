package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.dto.delivery.*;
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
import jakarta.persistence.EntityManagerFactory;
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
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:delivery-preparation;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false","spring.jpa.properties.hibernate.generate_statistics=true","logging.level.org.hibernate.stat=WARN","logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"})
class DeliveryPreparationIntegrationTest {
    @Autowired DeliveryRepository deliveries;
    @Autowired RecipientRepository recipients;
    @Autowired OperatorRepository operators;
    @Autowired RouteRepository routes;
    @Autowired AccountRepository accounts;
    @Autowired DeliveryPreparationService service;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory factory;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean MapsProvider provider;
    AccountPrincipal admin, driver;
    Integer recipientId, operatorId;
    static final String BASE="/api/admin/deliveries";
    @BeforeEach void setup() {
        routes.deleteAll(); routes.flush(); deliveries.deleteAll(); deliveries.flush(); accounts.deleteAll(); accounts.flush();
        operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush(); reset(provider);
        var recipient=new Recipient(); recipient.setFirstName("<img src=x>"); recipient.setLastName("Recipient"); recipient.setAddress("Saved address"); recipient.setLatitude(BigDecimal.ONE); recipient.setLongitude(BigDecimal.ONE); recipientId=recipients.saveAndFlush(recipient).getRecipientId();
        var operator=new Operator(); operator.setName("Driver"); operator.setEmail("delivery@test.local"); operator.setPhoneNumber("123"); operatorId=operators.saveAndFlush(operator).getOperatorId();
        admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("delivery.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
        driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("delivery.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false)));
    }
    Map<String,Object> fields() {
        var fields=new LinkedHashMap<String,Object>(); fields.put("recipientId",recipientId); fields.put("scheduledDate","2020-01-02"); fields.put("startTimeWindow","09:00"); fields.put("endTimeWindow","12:00:00"); fields.put("priorityLevel","HIGH"); fields.put("specialInstructions","  Use side entrance  "); return fields;
    }
    String body() throws Exception { return json.writeValueAsString(fields()); }
    DeliveryCreateRequest request(LocalDate date) { return new DeliveryCreateRequest(recipientId,date,LocalTime.of(9,0),LocalTime.of(12,0),Delivery.PriorityLevel.HIGH,"  Use side entrance  "); }
    @Test void createsOverdueUnassignedPendingDeliveryAndReturnsLocationAndNoStore() throws Exception {
        var result=mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body())).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.deliveryStatus").value("PENDING")).andExpect(jsonPath("$.operatorId").isEmpty()).andExpect(jsonPath("$.scheduledDate").value("2020-01-02")).andExpect(jsonPath("$.specialInstructions").value("Use side entrance")).andReturn();
        mvc.perform(get(result.getResponse().getHeader("Location")).with(user(admin))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.recipientId").value(recipientId));
        assertThat(deliveries.count()).isEqualTo(1); verifyNoInteractions(provider);
        assertThat(deliveries.findPendingIds(LocalDate.now(),org.springframework.data.domain.PageRequest.of(0,50))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"deliveryId","operatorId","deliveryStatus","latitude","unknown"})
    void rejectsServerOwnedAndUnknownFields(String field) throws Exception {
        var values=fields(); values.put(field,1); reject(json.writeValueAsString(values));
    }
    @ParameterizedTest @ValueSource(strings={"recipientId","scheduledDate","startTimeWindow","endTimeWindow","priorityLevel","specialInstructions"})
    void rejectsDuplicateFields(String field) throws Exception {
        String extra=json.writeValueAsString(Map.of(field,fields().get(field)));
        reject(extra.substring(0,extra.length()-1)+","+body().substring(1));
    }
    @ParameterizedTest @ValueSource(strings={"missingRecipient","missingDate","missingStart","missingEnd","missingPriority","zeroRecipient","negativeRecipient","quotedRecipient","fractionalRecipient","overflowRecipient","numericPriority","badPriority","numericDate","impossibleDate","nonIsoDate","ancientDate","numericStart","invalidStart","fractionalTime","reversedWindow","equalWindow","numericInstructions","oversizedInstructions"})
    void validatesAllCreationFieldsWithoutWriting(String kind) throws Exception {
        var values=fields();
        switch(kind) {
            case "missingRecipient" -> values.remove("recipientId"); case "missingDate" -> values.remove("scheduledDate");
            case "missingStart" -> values.remove("startTimeWindow"); case "missingEnd" -> values.remove("endTimeWindow"); case "missingPriority" -> values.remove("priorityLevel");
            case "zeroRecipient" -> values.put("recipientId",0); case "negativeRecipient" -> values.put("recipientId",-1);
            case "quotedRecipient" -> values.put("recipientId",recipientId.toString()); case "fractionalRecipient" -> values.put("recipientId",1.5); case "overflowRecipient" -> values.put("recipientId",2147483648L);
            case "numericPriority" -> values.put("priorityLevel",0); case "badPriority" -> values.put("priorityLevel","URGENT"); case "numericDate" -> values.put("scheduledDate",1);
            case "impossibleDate" -> values.put("scheduledDate","2026-02-30"); case "nonIsoDate" -> values.put("scheduledDate","02/01/2026"); case "ancientDate" -> values.put("scheduledDate","0999-01-01");
            case "numericStart" -> values.put("startTimeWindow",9); case "invalidStart" -> values.put("startTimeWindow","25:00"); case "fractionalTime" -> values.put("startTimeWindow","09:00:00.5");
            case "reversedWindow" -> values.put("startTimeWindow","13:00"); case "equalWindow" -> values.put("endTimeWindow","09:00");
            case "numericInstructions" -> values.put("specialInstructions",123); case "oversizedInstructions" -> values.put("specialInstructions","x".repeat(256));
        }
        reject(json.writeValueAsString(values));
    }
    void reject(String body) throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(deliveries.count()).isZero(); verifyNoInteractions(provider);
    }
    @Test void acceptsMaximumInstructionsAndNormalizesBlankOrMissingInstructions() throws Exception {
        var values=fields(); values.put("specialInstructions","x".repeat(255));
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isCreated()).andExpect(jsonPath("$.specialInstructions").value("x".repeat(255)));
        values.put("specialInstructions","  "); mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isCreated()).andExpect(jsonPath("$.specialInstructions").isEmpty());
        values.remove("specialInstructions"); mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isCreated()).andExpect(jsonPath("$.specialInstructions").isEmpty());
    }
    @Test void missingRecipientAndDeliveryHaveExplicitNotFoundResponses() throws Exception {
        recipients.deleteById(recipientId);
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body())).andExpect(status().isNotFound()); assertThat(deliveries.count()).isZero();
        mvc.perform(get(BASE+"/2147483647").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(get(BASE+"/0").with(user(admin))).andExpect(status().isBadRequest());
    }
    @Test void administratorRoleSessionAndCsrfAreRequired() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).with(user(driver))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(user(driver)).with(csrf()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(user(admin)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        var account=accounts.findById(admin.getAccountId()).orElseThrow(); account.changeEnabled(false); accounts.saveAndFlush(account);
        mvc.perform(get(BASE).with(user(admin))).andExpect(status().isUnauthorized()); assertThat(deliveries.count()).isZero();
    }
    @Test void paginatesScalarRowsInStableOrderAndFiltersByDateStatusAndRecipient() throws Exception {
        var first=service.create(request(LocalDate.of(2020,1,2))); var second=service.create(request(LocalDate.of(2020,1,2))); var third=service.create(request(LocalDate.of(2020,1,3)));
        var saved=deliveries.findById(third.getDeliveryId()).orElseThrow(); saved.setDeliveryStatus(Delivery.DeliveryStatus.COMPLETED); deliveries.saveAndFlush(saved);
        mvc.perform(get(BASE+"?page=1&size=1").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries[0].deliveryId").value(second.getDeliveryId())).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get(BASE+"?scheduledDate=2020-01-02&status=PENDING&recipientId="+recipientId).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.deliveries[0].deliveryId").value(first.getDeliveryId()));
        mvc.perform(get(BASE+"?status=COMPLETED").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries[0].deliveryId").value(third.getDeliveryId()));
        mvc.perform(get(BASE+"?recipientId=2147483647").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries").isEmpty());
        var stats=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.clear();
        var result=service.directory(0,1,null,null,null); assertThat(result.deliveries()).hasSize(1); assertThat(stats.getEntityLoadCount()).isZero(); assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"page=-1","page=10001","size=0","size=51","size=no","recipientId=0","recipientId=-1","scheduledDate=2026-02-30","scheduledDate=20260101","scheduledDate=0999-01-01","status=UNKNOWN"})
    void rejectsUnboundedAndMalformedFilters(String query) throws Exception { mvc.perform(get(BASE+"?"+query).with(user(admin))).andExpect(status().isBadRequest()); }
    @Test void emptyDirectoryAndOutOfRangePageStayBounded() throws Exception {
        mvc.perform(get(BASE+"?page=10000&size=50").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.deliveries").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void legacyCreateAndEditRejectInvalidReferencesWindowsAndOversizedInstructions() throws Exception {
        var values=fields(); values.put("operatorId",Integer.MAX_VALUE);
        mvc.perform(post("/api/v1/scheduling-app/deliveries").with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isBadRequest()); assertThat(deliveries.count()).isZero();
        values.put("operatorId",operatorId);
        var created=mvc.perform(post("/api/v1/scheduling-app/deliveries").with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isCreated()).andExpect(jsonPath("$.operatorId").value(operatorId)).andReturn();
        int id=json.readTree(created.getResponse().getContentAsString()).get("deliveryId").asInt();
        values.put("startTimeWindow","13:00"); mvc.perform(put("/api/v1/scheduling-app/deliveries/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isBadRequest());
        values.put("startTimeWindow","09:00"); values.put("specialInstructions","x".repeat(256)); mvc.perform(put("/api/v1/scheduling-app/deliveries/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isBadRequest());
        assertThat(deliveries.findById(id).orElseThrow().getSpecialInstructions()).isEqualTo("Use side entrance");
        values.put("specialInstructions","Valid");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var route=new Route(); route.setOperator(operators.findById(operatorId).orElseThrow()); route.setScheduledDate(LocalDate.now()); route.setRouteStatus(Route.RouteStatus.PLANNED); route.setDeliveries(new ArrayList<>(List.of(deliveries.findById(id).orElseThrow()))); routes.saveAndFlush(route);
        });
        mvc.perform(put("/api/v1/scheduling-app/deliveries/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isConflict());
        assertThat(deliveries.findById(id).orElseThrow().getSpecialInstructions()).isEqualTo("Use side entrance");
    }
    @Test void validLegacyPendingEditWorksAndInvalidOperatorDoesNotClearAssignment() throws Exception {
        var values=fields(); values.put("operatorId",operatorId);
        var created=mvc.perform(post("/api/v1/scheduling-app/deliveries").with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isCreated()).andReturn();
        int id=json.readTree(created.getResponse().getContentAsString()).get("deliveryId").asInt();
        values.put("specialInstructions","  Revised instructions  "); values.put("endTimeWindow","13:00");
        mvc.perform(put("/api/v1/scheduling-app/deliveries/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isOk()).andExpect(jsonPath("$.specialInstructions").value("Revised instructions")).andExpect(jsonPath("$.operatorId").value(operatorId)).andExpect(jsonPath("$.deliveryStatus").value("PENDING"));
        values.put("operatorId",Integer.MAX_VALUE); values.put("specialInstructions","Must not overwrite");
        mvc.perform(put("/api/v1/scheduling-app/deliveries/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(values))).andExpect(status().isBadRequest());
        var saved=deliveries.findById(id).orElseThrow(); assertThat(saved.getOperatorId().getOperatorId()).isEqualTo(operatorId); assertThat(saved.getSpecialInstructions()).isEqualTo("Revised instructions");
    }

}
