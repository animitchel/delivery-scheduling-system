package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.recipient.RecipientRequestDTO;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.RecipientRepository;
import com.mmu.schedulingapp.utility.GeocodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RecipientServiceSecurityTest {
    @Test
    void successSavesCoordinatesWithoutPrintingLocation(CapturedOutput output) {
        var saved = new AtomicReference<Recipient>();
        var service = new RecipientService(repository(saved), address ->
                Optional.of(new GeocodingService.Coordinate(53.480801, -2.242601)));
        var response = service.createRecipient(request());
        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().getLatitude()).isEqualByComparingTo(new BigDecimal("53.480801"));
        assertThat(response.getLongitude()).isEqualByComparingTo(new BigDecimal("-2.242601"));
        assertThat(output.getAll()).doesNotContain("53.480801", "-2.242601", "private-address-sentinel");
    }

    @Test
    void failureDoesNotSaveOrExposeAddressInException(CapturedOutput output) {
        var saved = new AtomicReference<Recipient>();
        var service = new RecipientService(repository(saved), address -> Optional.empty());
        assertThatThrownBy(() -> service.createRecipient(request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to determine coordinates for the recipient address.");
        assertThat(saved.get()).isNull();
        assertThat(output.getAll()).doesNotContain("private-address-sentinel");
    }

    private static RecipientRequestDTO request() {
        return RecipientRequestDTO.builder().firstName("Test").lastName("Recipient")
                .address("private-address-sentinel").priorityLevel(RecipientRequestDTO.PriorityLevel.MEDIUM).build();
    }

    private static RecipientRepository repository(AtomicReference<Recipient> saved) {
        return (RecipientRepository) Proxy.newProxyInstance(RecipientRepository.class.getClassLoader(),
                new Class<?>[]{RecipientRepository.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("save")) {
                        throw new AssertionError("Unexpected repository operation: " + method.getName());
                    }
                    Recipient recipient = (Recipient) args[0];
                    recipient.setRecipientId(1);
                    saved.set(recipient);
                    return recipient;
                });
    }
}
