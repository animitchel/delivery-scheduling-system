package com.mmu.schedulingapp.utility;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Map;

public class DistanceCalculator {
    public static DistanceResult getGoogleMapsDistance(double originLat, double originLng,
                                                       double destLat, double destLng) throws Exception {
        return getGoogleMapsDistance(originLat, originLng, destLat, destLng, GoogleMapsClient::get);
    }

    public static DistanceResult getGoogleMapsDistance(double originLat, double originLng,
                                                double destLat, double destLng, GoogleMapsClient.Request request)
            throws IOException {
        try {
            String response = request.get(GoogleMapsClient.Api.DISTANCE_MATRIX, Map.of(
                    "origins", originLat + "," + originLng,
                    "destinations", destLat + "," + destLng,
                    "units", "metric"));
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            requireOk(root);
            JsonObject element = root.getAsJsonArray("rows").get(0).getAsJsonObject()
                    .getAsJsonArray("elements").get(0).getAsJsonObject();
            requireOk(element);
            double distanceKm = element.getAsJsonObject("distance").get("value").getAsInt() / 1000.0;
            JsonObject duration = element.getAsJsonObject("duration");
            int seconds = duration.get("value").getAsInt();
            if (!Double.isFinite(distanceKm) || distanceKm < 0 || seconds < 0) throw new IllegalArgumentException();
            return new DistanceResult(distanceKm, duration.get("text").getAsString(), seconds);
        } catch (org.springframework.web.server.ResponseStatusException limit) {
            throw limit;
        } catch (MapsFailure failure) {
            throw new MapsFailure(failure.kind(), "Google Maps distance request failed.");
        } catch (Exception exception) {
            // Preserve the existing route fallback without exposing transport or parser details.
            throw new MapsFailure(exception instanceof IOException ? MapsFailure.Kind.UNAVAILABLE : MapsFailure.Kind.MALFORMED_RESPONSE,
                    "Google Maps distance request failed.");
        }
    }
    private static void requireOk(JsonObject object) throws MapsFailure {
        String status = object.get("status").getAsString();
        if ("OK".equals(status)) return;
        MapsFailure.Kind kind = switch (status) {
            case "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" -> MapsFailure.Kind.QUOTA;
            case "REQUEST_DENIED" -> MapsFailure.Kind.DENIED;
            case "ZERO_RESULTS", "NOT_FOUND", "MAX_ROUTE_LENGTH_EXCEEDED" -> MapsFailure.Kind.ZERO_RESULTS;
            default -> MapsFailure.Kind.MALFORMED_RESPONSE;
        };
        throw new MapsFailure(kind);
    }
}
