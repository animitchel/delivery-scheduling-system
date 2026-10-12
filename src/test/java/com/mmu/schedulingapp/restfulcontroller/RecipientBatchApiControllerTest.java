package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.recipient.RecipientResponseDTO;
import com.mmu.schedulingapp.service.RecipientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RecipientBatchApiControllerTest {
    private RecordingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new RecordingService();
        mvc = standaloneSetup(new RecipientApiController(service)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/recipients"})
    void batchReturnsOnlyRequestedRecipientsAndPreservesLegacyPath(String suffix) throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/recipients" + suffix).param("ids", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].recipientId").value(1))
                .andExpect(jsonPath("$[1].recipientId").value(2));
        assertThat(service.requestedIds).containsExactly(1, 2);
        assertThat(service.listCalls).isZero();
    }

    @Test
    void unfilteredCollectionStillUsesCollectionService() throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/recipients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recipientId").value(3));
        assertThat(service.listCalls).isEqualTo(1);
        assertThat(service.requestedIds).isNull();
    }

    @Test
    void unknownIdsReturnEmptyArrayInsteadOfAllRecipients() throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/recipients").param("ids", "999"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(service.requestedIds).containsExactly(999);
        assertThat(service.listCalls).isZero();
    }

    @Test
    void invalidBatchIdFailsBeforeLoadingData() throws Exception {
        mvc.perform(get("/api/v1/scheduling-app/recipients").param("ids", "invalid"))
                .andExpect(status().isBadRequest());
        assertThat(service.requestedIds).isNull();
        assertThat(service.listCalls).isZero();
    }

    private static class RecordingService extends RecipientService {
        private int listCalls;
        private List<Integer> requestedIds;
        RecordingService() { super(null); }
        @Override public List<RecipientResponseDTO> getAllRecipientDTOs() {
            listCalls++;
            return List.of(recipient(3));
        }
        @Override public List<RecipientResponseDTO> findByIds(List<Integer> ids) {
            requestedIds = ids;
            return ids.stream().filter(id -> id == 1 || id == 2)
                    .map(RecordingService::recipient).toList();
        }
        private static RecipientResponseDTO recipient(int id) {
            return RecipientResponseDTO.builder().recipientId(id).firstName("Test").lastName("Recipient").build();
        }
    }
}
