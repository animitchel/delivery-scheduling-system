package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.utility.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recipient-management;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
class RecipientManagementIntegrationTest {
    @Autowired RecipientRepository recipients;
    @Autowired AccountRepository accounts;
    @Autowired OperatorRepository operators;
    @Autowired RecipientService service;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory factory;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoBean MapsProvider provider;
    AccountPrincipal admin, driver;
    Integer id;
    static final String BODY="{\"firstName\":\"New\",\"lastName\":\"Recipient\",\"address\":\"Original address\",\"contactNumber\":\"123\",\"priorityLevel\":\"HIGH\"}";
    @BeforeEach void fixtures() {
        accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); recipients.deleteAll(); recipients.flush();
        admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("recipient.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
        var operator=new Operator(); operator.setName("Test"); operator.setPhoneNumber("123"); operator.setEmail("recipient@example.test"); operators.saveAndFlush(operator);
        driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("recipient.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false)));
        id=seed("Original","Recipient","Original address").getRecipientId(); reset(provider);
        when(provider.coordinates(anyString())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return Optional.of(new GeocodingService.Coordinate(53.12345678,-2.5)); });
    }
    Recipient seed(String first,String last,String address) {
        var recipient=new Recipient(); recipient.setFirstName(first); recipient.setLastName(last); recipient.setAddress(address); recipient.setLatitude(new BigDecimal("10.000000")); recipient.setLongitude(new BigDecimal("20.000000")); recipient.setPriorityLevel(Recipient.PriorityLevel.MEDIUM); recipient.setCreatedAt(LocalDateTime.of(2026,10,12,12,0)); return recipients.saveAndFlush(recipient);
    }
    String etag() { return service.versionedRecipient(id).orElseThrow().etag(); }
    RecipientWriteRequest request(String name,String address) { var request=new RecipientWriteRequest(); request.setFirstName(name); request.setLastName("Recipient"); request.setAddress(address); request.setContactNumber("123"); request.setPriorityLevel(RecipientRequestDTO.PriorityLevel.HIGH); return request; }
    @Test void createGeocodesOutsideTransactionAndIssuedVersionMatchesPersistedRead() throws Exception {
        var created=mvc.perform(post("/api/admin/recipients").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.latitude").value(53.123457)).andReturn();
        var location=created.getResponse().getHeader("Location");
        mvc.perform(get(location).with(user(admin))).andExpect(status().isOk()).andExpect(header().string("ETag",created.getResponse().getHeader("ETag")));
        assertThat(recipients.count()).isEqualTo(2); verify(provider).coordinates("Original address");
    }
    @Test void unchangedAddressPreservesCoordinatesWithoutLookupAndRejectsStaleClientEdit() throws Exception {
        String before=etag();
        var updated=mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(10)).andExpect(jsonPath("$.longitude").value(20)).andReturn();
        assertThat(updated.getResponse().getHeader("ETag")).isNotEqualTo(before); verifyNoInteractions(provider);
        mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY)).andExpect(status().isPreconditionFailed());
        assertThat(recipients.findById(id).orElseThrow().getCreatedAt()).isEqualTo(LocalDateTime.of(2026,10,12,12,0));
    }
    @Test void legacyOuterAddressWhitespaceDoesNotTriggerUnnecessaryLookup() {
        var old=recipients.findById(id).orElseThrow(); old.setAddress("  Original address  "); recipients.saveAndFlush(old);
        var updated=service.replaceRecipient(id,request("Name edit","Original address"),etag()).orElseThrow();
        assertThat(updated.address()).isEqualTo("Original address"); assertThat(updated.latitude()).isEqualByComparingTo("10"); verifyNoInteractions(provider);
    }
    @Test void changedAddressGetsCoordinatesAndKeepsIdentity() throws Exception {
        mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).header("If-Match",etag()).contentType("application/json").content(BODY.replace("Original address","Changed address"))).andExpect(status().isOk()).andExpect(jsonPath("$.recipientId").value(id)).andExpect(jsonPath("$.address").value("Changed address")).andExpect(jsonPath("$.latitude").value(53.123457));
        verify(provider).coordinates("Changed address");
    }
    @ParameterizedTest @ValueSource(strings={"empty","exception","invalid"})
    void providerFailureDoesNotChangeExistingRecord(String kind) throws Exception {
        if(kind.equals("empty")) when(provider.coordinates(anyString())).thenReturn(Optional.empty());
        else if(kind.equals("exception")) when(provider.coordinates(anyString())).thenThrow(new IllegalStateException("private-provider-url-and-address"));
        else when(provider.coordinates(anyString())).thenReturn(Optional.of(new GeocodingService.Coordinate(Double.NaN,200)));
        String before=etag();
        mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY.replace("Original address","Changed address"))).andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-provider"))));
        assertThat(etag()).isEqualTo(before);
        mvc.perform(post("/api/admin/recipients").with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isServiceUnavailable()); assertThat(recipients.count()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"\"recipientId\":99,","\"latitude\":0,","\"longitude\":0,","\"createdAt\":\"2026-10-13\",","\"firstName\":\"Duplicate\","})
    void strictPayloadRejectsUnknownAndDuplicateFieldsBeforeLookup(String extra) throws Exception {
        mvc.perform(post("/api/admin/recipients").with(user(admin)).with(csrf()).contentType("application/json").content("{"+extra+BODY.substring(1))).andExpect(status().isBadRequest()); verifyNoInteractions(provider); assertThat(recipients.count()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"blankName","longName","blankAddress","longAddress","longContact","missingPriority","invalidPriority"})
    void invalidWritableFieldsAreRejectedBeforeLookup(String kind) throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(BODY);
        switch(kind) {
            case "blankName" -> body.put("firstName"," ");
            case "longName" -> body.put("lastName","x".repeat(51));
            case "blankAddress" -> body.put("address"," ");
            case "longAddress" -> body.put("address","x".repeat(1001));
            case "longContact" -> body.put("contactNumber","1".repeat(21));
            case "missingPriority" -> body.remove("priorityLevel");
            case "invalidPriority" -> body.put("priorityLevel","UNKNOWN");
        }
        mvc.perform(post("/api/admin/recipients").with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest()); verifyNoInteractions(provider);
    }
    @ParameterizedTest @ValueSource(strings={"firstName","lastName","address","contactNumber","priorityLevel"})
    void strictWriteFieldsRejectJsonCoercion(String field) throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(BODY); body.put(field,0);
        mvc.perform(post("/api/admin/recipients").with(user(admin)).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest()); verifyNoInteractions(provider);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"-1,20","10001,20","0,0","0,51"})
    void invalidDirectoryBoundsAreRejected(int page,int size) throws Exception {
        mvc.perform(get("/api/admin/recipients").with(user(admin)).param("page",String.valueOf(page)).param("size",String.valueOf(size))).andExpect(status().isBadRequest());
    }
    @Test void conditionalUpdateRequiresOneStrongVersion() throws Exception {
        mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isPreconditionRequired());
        for(String invalid:List.of("*","W/"+etag(),etag()+","+etag())) mvc.perform(put("/api/admin/recipients/"+id).with(user(admin)).with(csrf()).header("If-Match",invalid).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(provider);
    }
    @Test void readsAndWritesEnforceAdministratorAndCsrf() throws Exception {
        mvc.perform(get("/api/admin/recipients")).andExpect(status().isUnauthorized()); mvc.perform(get("/api/admin/recipients").with(user(driver))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/recipients").with(user(admin)).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/recipients").with(user(driver)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isForbidden()); verifyNoInteractions(provider);
    }
    @Test void directoryIsBoundedScalarAndSearchWildcardsAreLiteral() throws Exception {
        for(int i=0;i<12;i++) seed("Same","Name","Address "+i); var special=seed("Percent_%!","Name","Literal");
        var stats=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        try {
            var result=service.directory(0,5,""); assertThat(result.recipients()).hasSize(5); assertThat(result.totalElements()).isEqualTo(14); assertThat(stats.getEntityLoadCount()).isZero(); assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2);
            var second=service.directory(1,5,""); assertThat(second.recipients().stream().map(RecipientDirectory.Item::recipientId)).doesNotContainAnyElementsOf(result.recipients().stream().map(RecipientDirectory.Item::recipientId).toList());
            assertThat(service.directory(0,20,"_%!").recipients()).extracting(RecipientDirectory.Item::recipientId).containsExactly(special.getRecipientId());
            assertThat(service.directory(0,20,"oRiGiNaL").recipients()).extracting(RecipientDirectory.Item::recipientId).containsExactly(id);
        } finally { stats.setStatisticsEnabled(false); }
        mvc.perform(get("/api/admin/recipients").with(user(admin)).param("size","51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/recipients").with(user(admin)).param("search","x".repeat(101))).andExpect(status().isBadRequest());
        assertThat(service.directory(100,20,"").recipients()).isEmpty();
    }
    @Test void legacyUpdatePreservesCoordinatesAndLegacyCreateIgnoresIdentity() {
        var old=RecipientRequestDTO.builder().recipientId(id).firstName("Legacy").lastName("Recipient").address("Original address").latitude(BigDecimal.ZERO).longitude(BigDecimal.ZERO).priorityLevel(RecipientRequestDTO.PriorityLevel.HIGH).build();
        assertThat(service.updateRecipient(id,old).orElseThrow().getLatitude()).isEqualByComparingTo("10"); verifyNoInteractions(provider);
        var created=service.createRecipient(old); assertThat(created.getRecipientId()).isNotEqualTo(id); assertThat(recipients.count()).isEqualTo(2);
    }
    @Test void concurrentEditDuringLookupCannotBeOverwritten() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(provider.coordinates("Changed address")).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return Optional.of(new GeocodingService.Coordinate(53,-2));});
        String version=etag(); var pending=CompletableFuture.supplyAsync(()->service.replaceRecipient(id,request("Slow edit","Changed address"),version));
        try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); service.replaceRecipient(id,request("Winning edit","Original address"),version); } finally { release.countDown(); }
        assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error.getCause()).getStatusCode().value()).isEqualTo(409));
        var current=recipients.findById(id).orElseThrow(); assertThat(current.getFirstName()).isEqualTo("Winning edit"); assertThat(current.getAddress()).isEqualTo("Original address"); assertThat(current.getLatitude()).isEqualByComparingTo("10");
    }
    @Test void deletionDuringLookupDoesNotResurrectRecipient() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(provider.coordinates("Changed address")).thenAnswer(call->{started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return Optional.of(new GeocodingService.Coordinate(53,-2));});
        String version=etag(); var pending=CompletableFuture.supplyAsync(()->service.replaceRecipient(id,request("Slow edit","Changed address"),version));
        try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); recipients.deleteById(id); recipients.flush(); } finally { release.countDown(); }
        assertThat(pending.get(5,TimeUnit.SECONDS)).isEmpty(); assertThat(recipients.existsById(id)).isFalse();
    }
}
