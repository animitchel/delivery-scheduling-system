package com.mmu.schedulingapp.utility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleMapsClientTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingKeyFailsBeforeTransport(String key) {
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> GoogleMapsClient.get(GoogleMapsClient.Api.GEOCODING,
                Map.of("address", "private-address"), key, uri -> {
                    called.set(true);
                    return "{}";
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Google Maps is not configured. Set GOOGLE_MAPS_API_KEY.");
        assertThat(called).isFalse();
    }

    @ParameterizedTest
    @EnumSource(GoogleMapsClient.Api.class)
    void configuredKeyAndParametersAreEncodedOnceForFixedHttpsEndpoints(GoogleMapsClient.Api api) throws Exception {
        String key = "dummy-key+&%=";
        String address = "Private address + & % / café";
        String response = GoogleMapsClient.get(api, Map.of("address", address), key, uri -> {
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("maps.googleapis.com");
            assertThat(uri.getPath()).isEqualTo(api == GoogleMapsClient.Api.GEOCODING
                    ? "/maps/api/geocode/json" : "/maps/api/distancematrix/json");
            Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
                    .map(part -> part.split("=", 2))
                    .collect(Collectors.toMap(part -> decode(part[0]), part -> decode(part[1])));
            assertThat(query).containsExactlyInAnyOrderEntriesOf(Map.of("key", key, "address", address));
            return "mock-response";
        });
        assertThat(response).isEqualTo("mock-response");
    }

    @Test
    void transportFailureCannotExposeItsUrlBodyOrCause() {
        assertThatThrownBy(() -> GoogleMapsClient.get(GoogleMapsClient.Api.GEOCODING,
                Map.of("address", "private-address"), "dummy-secret-key", uri -> {
                    throw new IOException("private-provider-body: " + uri,
                            new IOException("dummy-secret-key private-address"));
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("Google Maps request failed.")
                .hasNoCause();
    }

    @Test
    void configurationTrimsAccidentalOuterWhitespace() {
        assertThat(GoogleMapsClient.requireApiKey("  dummy-secret-key  ")).isEqualTo("dummy-secret-key");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
