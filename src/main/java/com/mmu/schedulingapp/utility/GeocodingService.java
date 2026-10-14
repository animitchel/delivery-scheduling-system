package com.mmu.schedulingapp.utility;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

public class GeocodingService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private GeocodingService() {
    }

    public static Optional<Coordinate> getCoordinates(String address) {
        return getCoordinates(address, GoogleMapsClient::get);
    }

    static Optional<Coordinate> getCoordinates(String address, GoogleMapsClient.Request request) {
        try {
            String response = request.get(GoogleMapsClient.Api.GEOCODING, Map.of("address", address));
            JsonNode root = JSON.readTree(response);
            if (!"OK".equals(root.path("status").asText())) {
                log.warn("Geocoding returned no usable result.");
                return Optional.empty();
            }
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("Geocoding returned no coordinates.");
                return Optional.empty();
            }
            JsonNode location = results.get(0).path("geometry").path("location");
            JsonNode latitude=location.path("lat"),longitude=location.path("lng");
            if(!latitude.isNumber() || !longitude.isNumber() || !Double.isFinite(latitude.asDouble()) || !Double.isFinite(longitude.asDouble())
                    || latitude.asDouble() < -90 || latitude.asDouble() > 90 || longitude.asDouble() < -180 || longitude.asDouble() > 180) {
                log.warn("Geocoding returned invalid coordinates."); return Optional.empty();
            }
            return Optional.of(new Coordinate(latitude.asDouble(),longitude.asDouble()));
        } catch (Exception exception) {
            // Do not log provider text, addresses, exception messages or stack traces.
            log.warn("Geocoding failed; check provider availability and GOOGLE_MAPS_API_KEY configuration.");
            return Optional.empty();
        }
    }

    public static class Coordinate {
        private final double latitude;
        private final double longitude;

        public Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        @Override
        public String toString() {
            return "Latitude: " + latitude + ", Longitude: " + longitude;
        }
    }
}
