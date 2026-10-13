package com.mmu.schedulingapp.utility;

import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class MapsProvider {
    private final MapsHttpTransport transport;
    public MapsProvider(MapsHttpTransport transport) { this.transport = transport; }
    public DistanceResult distance(double lat1, double lon1, double lat2, double lon2, PlanningGuard.Budget budget) throws Exception {
        // Count misses before attempting transport, including failed requests; cache hits do not call here.
        budget.countProviderCall();
        return DistanceCalculator.getGoogleMapsDistance(lat1, lon1, lat2, lon2, (api, parameters) ->
                GoogleMapsClient.get(api, parameters, System.getenv("GOOGLE_MAPS_API_KEY"), uri -> transport.get(uri, budget.remainingNanos())));
    }
    public Optional<GeocodingService.Coordinate> coordinates(String address) {
        return GeocodingService.getCoordinates(address, (api, parameters) ->
                GoogleMapsClient.get(api, parameters, System.getenv("GOOGLE_MAPS_API_KEY"), uri -> transport.get(uri, Long.MAX_VALUE)));
    }
}
