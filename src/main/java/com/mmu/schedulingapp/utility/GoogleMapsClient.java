package com.mmu.schedulingapp.utility;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared credential and transport boundary for the server-side Maps utilities. */
public final class GoogleMapsClient {
    private GoogleMapsClient() {
    }

    public enum Api {
        GEOCODING("geocode"), DISTANCE_MATRIX("distancematrix");

        private final String path;

        Api(String path) {
            this.path = path;
        }
    }

    @FunctionalInterface
    public interface Request {
        String get(Api api, Map<String, String> parameters) throws IOException;
    }

    @FunctionalInterface
    interface Transport {
        String get(URI uri) throws IOException;
    }

    static String get(Api api, Map<String, String> parameters) throws IOException {
        try (var transport = new MapsHttpTransport(new MapsLimits())) {
            return get(api, parameters, System.getenv("GOOGLE_MAPS_API_KEY"), uri -> transport.get(uri, Long.MAX_VALUE));
        }
    }

    static String get(Api api, Map<String, String> parameters, String apiKey, Transport transport)
            throws IOException {
        String key = requireApiKey(apiKey);
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create("https://maps.googleapis.com/maps/api/" + api.path + "/json?"
                + query + "&key=" + encode(key));
        try {
            return transport.get(uri);
        } catch (MapsFailure failure) {
            throw failure;
        } catch (IOException exception) {
            // Transport errors can contain the credential-bearing URL or a response body.
            // Neither the original message nor its cause may cross this boundary.
            throw new IOException("Google Maps request failed.");
        }
    }

    static String requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Google Maps is not configured. Set GOOGLE_MAPS_API_KEY.");
        }
        return apiKey.strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
