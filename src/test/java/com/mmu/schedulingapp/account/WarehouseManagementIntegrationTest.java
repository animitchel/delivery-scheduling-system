package com.mmu.schedulingapp.account;

import com.mmu.schedulingapp.model.*;
import com.mmu.schedulingapp.repository.*;
import com.mmu.schedulingapp.service.*;
import com.mmu.schedulingapp.utility.MapsProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@ActiveProfiles("java25-test")
@AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:warehouse-management;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false","spring.jpa.properties.hibernate.generate_statistics=true"})
class WarehouseManagementIntegrationTest {
    @Autowired WarehouseRepository warehouses;
    @Autowired OperatorRepository operators;
    @Autowired AccountRepository accounts;
    @Autowired WarehouseService service;
    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory factory;
    @MockitoBean MapsProvider maps;
    AccountPrincipal admin, driver;
    Integer id;
    static final String BASE="/api/admin/warehouses";
    static final String BODY="{\"name\":\"New depot\",\"address\":\"New address\",\"latitude\":53.123456,\"longitude\":-2.5}";
    @BeforeEach void fixtures() {
        accounts.deleteAll(); accounts.flush(); operators.deleteAll(); operators.flush(); warehouses.deleteAll(); warehouses.flush();
        admin=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("warehouse.admin","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.ADMIN,null,false)));
        var operator=new Operator(); operator.setName("Driver"); operator.setPhoneNumber("123"); operator.setEmail("warehouse@example.test"); operators.saveAndFlush(operator);
        driver=new AccountPrincipal(accounts.saveAndFlush(new UserAccount("warehouse.driver","{bcrypt}$2a$12$"+"a".repeat(53),AccountRole.DRIVER,operator,false)));
        id=seed("Original","Depot address").getWarehouseId(); reset(maps);
    }
    Warehouse seed(String name,String address) { var warehouse=new Warehouse(); warehouse.setName(name); warehouse.setAddress(address); warehouse.setLatitude(BigDecimal.TEN); warehouse.setLongitude(new BigDecimal("20")); return warehouses.saveAndFlush(warehouse); }
    String etag() { return service.snapshot(id).orElseThrow().etag(); }
    @Test void createReturnsStableIdentityLocationAndPersistedVersionWithoutMaps() throws Exception {
        var result=mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.assignedDrivers").doesNotExist()).andReturn();
        mvc.perform(get(result.getResponse().getHeader("Location")).with(user(admin))).andExpect(status().isOk()).andExpect(header().string("ETag",result.getResponse().getHeader("ETag"))).andExpect(jsonPath("$.latitude").value(53.123456));
        assertThat(warehouses.count()).isEqualTo(2); verifyNoInteractions(maps);
    }
    @Test void conditionalEditsAndDeletesRejectOldFingerprintsAndRequireFreshReview() throws Exception {
        String before=etag();
        mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(id));
        mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY)).andExpect(status().isPreconditionFailed());
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",before)).andExpect(status().isPreconditionFailed());
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag())).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(BASE+"/"+id).with(user(admin))).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"warehouseId\":1}","{\"name\":\"a\",\"name\":\"b\"}","{\"name\":4}","{\"latitude\":\"53\"}","{\"latitude\":true}","{\"latitude\":{}}","{\"assignedDrivers\":[]}","null","[]"})
    void malformedContractsAreRejectedWithoutChanges(String body) throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest()); assertThat(warehouses.count()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"91","-91","180.000001","-180.000001","0.1234567","1e99","null"})
    void invalidLatitudeIsRejected(String latitude) throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(BODY.replace("53.123456",latitude))).andExpect(status().isBadRequest()); assertThat(warehouses.count()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"181","-181","0.1234567","null"})
    void invalidLongitudeIsRejected(String longitude) throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(BODY.replace("-2.5",longitude))).andExpect(status().isBadRequest());
    }
    @Test void coordinateBoundariesWhitespaceAndLiteralMarkupAreAcceptedAsData() throws Exception {
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(BODY.replace("New depot","  <img src=x>  ").replace("New address","  Address  ").replace("53.123456","-90").replace("-2.5","180"))).andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("<img src=x>")).andExpect(jsonPath("$.address").value("Address"));
    }
    @Test void overlongAndBlankRequiredFieldsFailWithSafeErrors() throws Exception {
        for(String body:new String[]{BODY.replace("New depot"," "),BODY.replace("New depot","x".repeat(256)),BODY.replace("New address"," "),BODY.replace("New address","x".repeat(1001))}) {
            mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"?page=-1","?page=10001","?size=0","?size=51","?page=abc"})
    void boundedQueryValidation(String query) throws Exception { mvc.perform(get(BASE+query).with(user(admin))).andExpect(status().isBadRequest()); }
    @Test void overlongSearchIsRejected() throws Exception { mvc.perform(get(BASE).param("search","x".repeat(101)).with(user(admin))).andExpect(status().isBadRequest()); }
    @Test void directorySearchEscapesWildcardsAndRemainsScalarAndBounded() throws Exception {
        seed("Literal_%!","Address"); seed("Other","Address");
        var statistics=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); statistics.clear();
        mvc.perform(get(BASE).param("size","1").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.warehouses.length()").value(1)).andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3));
        assertThat(statistics.getCollectionFetchCount()).isZero(); assertThat(statistics.getEntityStatistics(Warehouse.class.getName()).getLoadCount()).isZero(); assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        mvc.perform(get(BASE).param("search","_%!").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.warehouses[0].name").value("Literal_%!")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE).param("search","ORIGINAL").with(user(admin))).andExpect(jsonPath("$.warehouses[0].warehouseId").value(id));
    }
    @Test void missingAndMalformedPreconditionsCannotWrite() throws Exception {
        mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isPreconditionRequired());
        for(String tag:new String[]{"*","W/"+etag(),etag()+","+etag(),"\"bad\""}) {
            mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",tag)).andExpect(status().isBadRequest());
        }
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf())).andExpect(status().isPreconditionRequired());
        assertThat(warehouses.existsById(id)).isTrue();
    }
    @Test void assignedWarehouseDeletionIsBlockedThroughBothContracts() throws Exception {
        var operator=operators.findAll().getFirst(); operator.setWarehouse(warehouses.findById(id).orElseThrow()); operators.saveAndFlush(operator);
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",etag())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/scheduling-app/warehouses/"+id).with(user(admin)).with(csrf())).andExpect(status().isConflict());
        assertThat(warehouses.existsById(id)).isTrue(); assertThat(operators.findById(operator.getOperatorId()).orElseThrow().getWarehouse().getWarehouseId()).isEqualTo(id);
    }
    @Test void legacyWritesShareValidationAndInvalidateModernFingerprints() throws Exception {
        String before=etag();
        mvc.perform(put("/api/v1/scheduling-app/warehouses/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.assignedDrivers").isArray());
        mvc.perform(put(BASE+"/"+id).with(user(admin)).with(csrf()).header("If-Match",before).contentType("application/json").content(BODY)).andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/scheduling-app/warehouses").with(user(admin)).with(csrf()).contentType("application/json").content(BODY.replace("-2.5","181"))).andExpect(status().isBadRequest());
    }
    @Test void legacyUnreferencedDeletionKeepsItsResponseShape() throws Exception {
        mvc.perform(delete("/api/v1/scheduling-app/warehouses/"+id).with(user(admin)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(id)).andExpect(jsonPath("$.assignedDrivers").isEmpty());
        assertThat(warehouses.existsById(id)).isFalse();
    }
    @Test void scalarDetailsNeverLoadAnAssignedOperatorList() throws Exception {
        var operator=operators.findAll().getFirst(); operator.setWarehouse(warehouses.findById(id).orElseThrow()); operators.saveAndFlush(operator);
        var statistics=factory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); statistics.clear();
        mvc.perform(get(BASE+"/"+id).with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.assignedDrivers").doesNotExist());
        assertThat(statistics.getCollectionFetchCount()).isZero();assertThat(statistics.getEntityStatistics(Operator.class.getName()).getLoadCount()).isZero();
    }
    @Test void roleCsrfDisabledAndPasswordSetupGuardsCoverNewEndpoints() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized()); mvc.perform(get(BASE).with(user(driver))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(user(admin)).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put(BASE+"/"+id).with(user(admin)).header("If-Match",etag()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete(BASE+"/"+id).with(user(admin)).header("If-Match",etag())).andExpect(status().isForbidden());
        mvc.perform(put(BASE+"/"+id).with(user(driver)).with(csrf()).header("If-Match",etag()).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete(BASE+"/"+id).with(user(driver)).with(csrf()).header("If-Match",etag())).andExpect(status().isForbidden());
        var account=accounts.findByUsername(admin.getUsername()).orElseThrow(); account.changeEnabled(false); accounts.saveAndFlush(account);
        mvc.perform(get(BASE).with(user(admin))).andExpect(status().isUnauthorized());
        account.changeEnabled(true); account.setTemporaryPassword(account.getPasswordHash(),java.time.Instant.now().plusSeconds(3600)); accounts.saveAndFlush(account);
        mvc.perform(get(BASE).with(user(new AccountPrincipal(account)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }
    @Test void invalidIDsMissingRecordsAndUnsupportedContentTypesKeepTheirStatus() throws Exception {
        mvc.perform(get(BASE+"/0").with(user(admin))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE+"/2147483647").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(post(BASE).with(user(admin)).with(csrf()).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType());
    }
}
