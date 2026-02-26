package com.mmu.schedulingapp.utility;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class GeocodingService {

    private static final String GEOCODING_URL = "https://maps.googleapis.com/maps/api/geocode/json";
    private static final String API_KEY = "REMOVED_REVOKED_GOOGLE_MAPS_KEY"; //

    private GeocodingService() {
        // This prevents instantiation
    }



public static Optional<Coordinate> getCoordinates(String address) {
    try {
        RestTemplate restTemplate = new RestTemplate();

        // Manually encode the address
        String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);

        // Build the URL manually
        String url = GEOCODING_URL + "?address=" + encodedAddress + "&key=" + API_KEY;
        System.out.println("Geocoding request URL: " + url);

        String response = restTemplate.getForObject(url, String.class);
        System.out.println("Geocoding response JSON: " + response);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(response);

        String status = root.path("status").asText();
        if (!"OK".equals(status)) {
            System.err.println("Geocoding API error: " + status + " for address: " + address);
            return Optional.empty();
        }

        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            System.err.println("No results found for address: " + address);
            return Optional.empty();
        }

        JsonNode location = results.get(0).path("geometry").path("location");
        double lat = location.path("lat").asDouble();
        double lng = location.path("lng").asDouble();

        return Optional.of(new Coordinate(lat, lng));

    } catch (Exception e) {
        System.err.println("Exception while fetching coordinates for: " + address);
        e.printStackTrace();
        return Optional.empty();
    }
}

    // Inner class for holding coordinates
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
