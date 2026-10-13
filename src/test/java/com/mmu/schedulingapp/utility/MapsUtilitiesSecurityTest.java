package com.mmu.schedulingapp.utility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class MapsUtilitiesSecurityTest {
    private static final String ADDRESS = "private-address-sentinel";
    private static final String KEY = "dummy-secret-key";
    private static final String BODY = "private-provider-body";

    @Test
    void geocodingSuccessPreservesCoordinatesWithoutLoggingData(CapturedOutput output) {
        var result = GeocodingService.getCoordinates(ADDRESS, (api, parameters) -> {
            assertThat(api).isEqualTo(GoogleMapsClient.Api.GEOCODING);
            assertThat(parameters).containsEntry("address", ADDRESS);
            return """
                    {"status":"OK","formatted_address":"private-address-sentinel",
                     "results":[{"geometry":{"location":{"lat":53.480801,"lng":-2.242601}}}],
                     "private":"private-provider-body dummy-secret-key"}
                    """;
        });
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getLatitude()).isEqualTo(53.480801);
        assertThat(result.orElseThrow().getLongitude()).isEqualTo(-2.242601);
        assertPrivateDataAbsent(output);
        assertThat(output.getAll()).doesNotContain("53.480801", "-2.242601");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZERO_RESULTS", "REQUEST_DENIED", "OVER_QUERY_LIMIT", "private-provider-body"})
    void nonOkResponsesDoNotLogUntrustedStatusOrBody(String status, CapturedOutput output) {
        var result = GeocodingService.getCoordinates(ADDRESS,
                (api, parameters) -> "{\"status\":\"" + status + "\",\"private\":\"" + KEY + "\"}");
        assertThat(result).isEmpty();
        assertThat(output.getAll()).contains("Geocoding returned no usable result.");
        assertPrivateDataAbsent(output);
        assertThat(output.getAll()).doesNotContain(status);
    }

    @Test
    void emptyResultsKeepExistingEmptyOutcome(CapturedOutput output) {
        assertThat(GeocodingService.getCoordinates(ADDRESS,
                (api, parameters) -> "{\"status\":\"OK\",\"results\":[]}")).isEmpty();
        assertThat(output.getAll()).contains("Geocoding returned no coordinates.");
        assertPrivateDataAbsent(output);
    }

    @Test
    void malformedJsonDoesNotLogParserInput(CapturedOutput output) {
        assertThat(GeocodingService.getCoordinates(ADDRESS,
                (api, parameters) -> "malformed " + BODY + " " + KEY)).isEmpty();
        assertPrivateDataAbsent(output);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void urlBearingExceptionAndCauseAreNotLogged(boolean unchecked, CapturedOutput output) {
        assertThat(GeocodingService.getCoordinates(ADDRESS, (api, parameters) -> {
            if (unchecked) {
                throw new IllegalStateException(KEY + " " + ADDRESS, new IllegalStateException(BODY));
            }
            throw new IOException("https://maps.googleapis.com/?key=" + KEY + "&address=" + ADDRESS,
                    new IllegalStateException(BODY));
        })).isEmpty();
        assertThat(output.getAll()).contains("Geocoding failed");
        assertPrivateDataAbsent(output);
        assertThat(output.getAll()).doesNotContain("maps.googleapis.com", "IOException", "IllegalStateException");
    }

    @Test
    void missingConfigurationDoesNotInvokeTransport(CapturedOutput output) {
        assertThat(GeocodingService.getCoordinates(ADDRESS, (api, parameters) ->
                GoogleMapsClient.get(api, parameters, " ", uri -> {
                    throw new AssertionError("Network must not be invoked");
                }))).isEmpty();
        assertThat(output.getAll()).contains("GOOGLE_MAPS_API_KEY configuration");
        assertPrivateDataAbsent(output);
    }

    @Test
    void distanceSuccessPreservesDistanceAndDuration(CapturedOutput output) throws Exception {
        var result = DistanceCalculator.getGoogleMapsDistance(53.1, -2.1, 53.2, -2.2, (api, parameters) -> {
            assertThat(api).isEqualTo(GoogleMapsClient.Api.DISTANCE_MATRIX);
            assertThat(parameters).containsEntry("origins", "53.1,-2.1")
                    .containsEntry("destinations", "53.2,-2.2").containsEntry("units", "metric");
            return """
                    {"status":"OK","rows":[{"elements":[{"status":"OK","distance":{"value":1250},
                     "duration":{"text":"4 mins","value":240}}]}],"private":"private-provider-body"}
                    """;
        });
        assertThat(result.getDistanceKm()).isEqualTo(1.25);
        assertThat(result.getDurationText()).isEqualTo("4 mins");
        assertThat(result.getDurationSeconds()).isEqualTo(240);
        assertPrivateDataAbsent(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-provider-body", "{}", "{\"rows\":[]}"})
    void distanceParseFailureIsSanitized(String response, CapturedOutput output) {
        assertThatThrownBy(() -> DistanceCalculator.getGoogleMapsDistance(53.1, -2.1, 53.2, -2.2,
                (api, parameters) -> response))
                .isInstanceOf(IOException.class).hasMessage("Google Maps distance request failed.").hasNoCause();
        assertPrivateDataAbsent(output);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distanceTransportFailureIsSanitized(boolean unchecked, CapturedOutput output) {
        assertThatThrownBy(() -> DistanceCalculator.getGoogleMapsDistance(53.1, -2.1, 53.2, -2.2,
                (api, parameters) -> {
                    if (unchecked) {
                        throw new IllegalStateException(KEY + " " + ADDRESS, new IllegalStateException(BODY));
                    }
                    throw new IOException(KEY + " " + ADDRESS, new IOException(BODY));
                }))
                .isInstanceOf(IOException.class).hasMessage("Google Maps distance request failed.").hasNoCause();
        assertPrivateDataAbsent(output);
    }

    private static void assertPrivateDataAbsent(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(KEY, ADDRESS, BODY);
    }
}
