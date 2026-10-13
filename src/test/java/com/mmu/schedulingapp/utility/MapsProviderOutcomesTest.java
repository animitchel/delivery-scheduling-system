package com.mmu.schedulingapp.utility;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MapsProviderOutcomesTest {
    @ParameterizedTest @CsvSource({"REQUEST_DENIED,DENIED","OVER_QUERY_LIMIT,QUOTA","OVER_DAILY_LIMIT,QUOTA","ZERO_RESULTS,ZERO_RESULTS","UNKNOWN_STATUS,MALFORMED_RESPONSE"})
    void apiFailuresAreTypedAndSanitized(String status, MapsFailure.Kind kind) {
        assertThatThrownBy(() -> DistanceCalculator.getGoogleMapsDistance(0,0,1,1,(api,parameters) ->
                "{\"status\":\""+status+"\",\"error_message\":\"dummy-secret private-address\"}"))
                .isInstanceOf(MapsFailure.class).hasMessage("Google Maps distance request failed.").hasNoCause()
                .satisfies(e -> assertThat(((MapsFailure)e).kind()).isEqualTo(kind));
    }
    @Test void elementFailureIsNotMistakenForUsableRoadDistance() {
        assertThatThrownBy(() -> DistanceCalculator.getGoogleMapsDistance(0,0,1,1,(api,parameters) ->
                "{\"status\":\"OK\",\"rows\":[{\"elements\":[{\"status\":\"ZERO_RESULTS\"}]}]}"))
                .isInstanceOf(MapsFailure.class).satisfies(e -> assertThat(((MapsFailure)e).kind()).isEqualTo(MapsFailure.Kind.ZERO_RESULTS));
    }
    @Test void negativeDurationIsRejected() {
        assertThatThrownBy(() -> DistanceCalculator.getGoogleMapsDistance(0,0,1,1,(api,parameters) ->
                "{\"status\":\"OK\",\"rows\":[{\"elements\":[{\"status\":\"OK\",\"distance\":{\"value\":1},\"duration\":{\"value\":-1,\"text\":\"private-provider-body\"}}]}]}"))
                .isInstanceOf(MapsFailure.class).hasNoCause().satisfies(e -> assertThat(((MapsFailure)e).kind()).isEqualTo(MapsFailure.Kind.MALFORMED_RESPONSE));
    }
}
