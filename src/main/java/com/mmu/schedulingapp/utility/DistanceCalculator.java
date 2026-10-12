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

    static DistanceResult getGoogleMapsDistance(double originLat, double originLng,
                                                double destLat, double destLng, GoogleMapsClient.Request request)
            throws IOException {
        try {
            String response = request.get(GoogleMapsClient.Api.DISTANCE_MATRIX, Map.of(
                    "origins", originLat + "," + originLng,
                    "destinations", destLat + "," + destLng,
                    "units", "metric"));
            JsonObject element = JsonParser.parseString(response).getAsJsonObject()
                    .getAsJsonArray("rows").get(0).getAsJsonObject()
                    .getAsJsonArray("elements").get(0).getAsJsonObject();
            double distanceKm = element.getAsJsonObject("distance").get("value").getAsInt() / 1000.0;
            JsonObject duration = element.getAsJsonObject("duration");
            return new DistanceResult(distanceKm, duration.get("text").getAsString(),
                    duration.get("value").getAsInt());
        } catch (Exception exception) {
            // Preserve the existing route fallback without exposing transport or parser details.
            throw new IOException("Google Maps distance request failed.");
        }
    }
}
