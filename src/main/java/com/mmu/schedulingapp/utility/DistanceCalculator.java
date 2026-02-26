package com.mmu.schedulingapp.utility;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;



public class DistanceCalculator {

    private static final String API_KEY = "AIzaSyA1PamDFTkEQdWZIYglm8moI0UsqVvq3GE";

    public static DistanceResult getGoogleMapsDistance(double originLat, double originLng,
                                                       double destLat, double destLng) throws Exception {
        String origin = originLat + "," + originLng;
        String destination = destLat + "," + destLng;

        String urlStr = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                "?origins=" + URLEncoder.encode(origin, StandardCharsets.UTF_8) +
                "&destinations=" + URLEncoder.encode(destination, StandardCharsets.UTF_8) +
                "&units=metric&key=" + API_KEY;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            JsonObject jsonResponse = JsonParser.parseReader(reader).getAsJsonObject();

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed to call API. HTTP code: " + statusCode);
            }

            JsonObject element = jsonResponse.getAsJsonArray("rows")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("elements")
                    .get(0).getAsJsonObject();

            // Distance
            JsonObject distanceObject = element.getAsJsonObject("distance");
            int meters = distanceObject.get("value").getAsInt();
            double distanceKm = meters / 1000.0;

            // Duration
            JsonObject durationObject = element.getAsJsonObject("duration");
            String durationText = durationObject.get("text").getAsString();
            int durationSeconds = durationObject.get("value").getAsInt();

            return new DistanceResult(distanceKm, durationText, durationSeconds);
        }
    }
}
