package com.mmu.schedulingapp.service.autorouting;


import com.mmu.schedulingapp.model.Delivery;
import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.utility.DistanceCalculator;
import com.mmu.schedulingapp.utility.DistanceResult;

import java.util.*;



public class RouteBuilder {

    private final Operator operator;
    @FunctionalInterface public interface DistanceLookup {
        DistanceResult get(double lat1, double lon1, double lat2, double lon2) throws Exception;
    }
    private final DistanceLookup lookup;
    private final Runnable checkDeadline;
    // cache whole DistanceResult so we reuse duration too
    private final Map<String, DistanceResult> legCache = new HashMap<>();

    public RouteBuilder(Operator operator) { this(operator, DistanceCalculator::getGoogleMapsDistance, () -> {}); }
    public RouteBuilder(Operator operator, DistanceLookup lookup, Runnable checkDeadline) {
        this.operator = operator; this.lookup = lookup; this.checkDeadline = checkDeadline;
    }

    public List<Delivery> buildOptimizedRoute(List<Delivery> deliveries) {
        List<Delivery> route = new ArrayList<>();
        List<Delivery> remaining = new ArrayList<>(deliveries);

        double currentLat = operator.getWarehouse().getLatitude().doubleValue();
        double currentLong = operator.getWarehouse().getLongitude().doubleValue();

        while (!remaining.isEmpty()) {
            System.out.println("Remaining deliveries: " + remaining.size());
            Delivery best = null;
            double bestScore = Double.MAX_VALUE;

            // choose next stop using cached distances
            for (Delivery delivery : remaining) {
                double endLat = delivery.getRecipientId().getLatitude().doubleValue();
                double endLong = delivery.getRecipientId().getLongitude().doubleValue();

                double distanceKm = getLeg(currentLat, currentLong, endLat, endLong).getDistanceKm();
                DeliveryScore score = getDeliveryScore(delivery, distanceKm);
                double totalScore = score.getTotalPriorityScore();

                if (totalScore < bestScore) {
                    bestScore = totalScore;
                    best = delivery;
                }
            }

            if (best != null) {
                List<Delivery> atSameLocation = new ArrayList<>();

                // group same-location drops
                Iterator<Delivery> iterator = remaining.iterator();
                while (iterator.hasNext()) {
                    Delivery candidate = iterator.next();
                    double clat = candidate.getRecipientId().getLatitude().doubleValue();
                    double clng = candidate.getRecipientId().getLongitude().doubleValue();
                    double dist = getLeg(currentLat, currentLong, clat, clng).getDistanceKm();
                    if (dist < 0.001) {
                        atSameLocation.add(candidate);
                        iterator.remove();
                    }
                }

                if (atSameLocation.isEmpty()) {
                    atSameLocation.add(best);
                    remaining.remove(best);
                }

                final double latRef = currentLat;
                final double lonRef = currentLong;
                atSameLocation.sort(Comparator.comparing(d -> {
                    double endLat = d.getRecipientId().getLatitude().doubleValue();
                    double endLong = d.getRecipientId().getLongitude().doubleValue();
                    double dist = getLeg(latRef, lonRef, endLat, endLong).getDistanceKm();
                    return getDeliveryScore(d, dist).getTimeUrgency();
                }));

                // annotate each leg once with distance+duration from previous point
                for (int i = 0; i < atSameLocation.size(); i++) {
                    Delivery stop = atSameLocation.get(i);
                    double endLat = stop.getRecipientId().getLatitude().doubleValue();
                    double endLong = stop.getRecipientId().getLongitude().doubleValue();

                    DistanceResult leg = getLeg(currentLat, currentLong, endLat, endLong);

                    System.out.println("Set hop for delivery " + stop.getDeliveryId()
                        + " -> " + leg.getDistanceKm() + " km, " + leg.getDurationText());

                    stop.setFromPrevDistanceKm(leg.getDistanceKm());
                    stop.setFromPrevDurationText(leg.getDurationText());
                    stop.setFromPrevDurationSeconds(leg.getDurationSeconds());

                    route.add(stop);
                    currentLat = endLat;
                    currentLong = endLong;
                }

                System.out.println("Bundled deliveries: " +
                        atSameLocation.stream().map(d -> d.getDeliveryId().toString()).toList());
            } else {
                break;
            }
        }

        return route;
    }

    private DeliveryScore getDeliveryScore(Delivery delivery, double distanceKm) {
        delivery.setOperatorId(operator);
        return DeliveryScore.builder()
                .delivery(delivery)
                .recipientPriorityLevelScore(mapPriority(delivery.getRecipientId().getPriorityLevel().name()))
                .deliveryPriorityLevelScore(mapPriority(delivery.getPriorityLevel().name()))
                .deliveryEndTimeWindow(delivery.getEndTimeWindow())
                .distanceKm(distanceKm)
                .build();
    }

    private int mapPriority(String level) {
        return switch (level.toUpperCase()) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 0;
        };
    }

    /** Get leg info with caching (Distance + Duration). */
    private DistanceResult getLeg(double lat1, double lon1, double lat2, double lon2) {
        checkDeadline.run();
        String key = lat1 + "," + lon1 + "->" + lat2 + "," + lon2;
        DistanceResult cached = legCache.get(key);
        if (cached != null) return cached;

        try {
            DistanceResult result = lookup.get(lat1, lon1, lat2, lon2);
            legCache.put(key, result);
            return result;
        } catch (org.springframework.web.server.ResponseStatusException limit) {
            throw limit;
        } catch (Exception e) {
            checkDeadline.run();
            // Label straight-line distance explicitly; an unknown travel time is never zero.
            double km = haversine(lat1, lon1, lat2, lon2);
            DistanceResult fallback = new DistanceResult(km, "Estimated distance; time unavailable", null);
            legCache.put(key, fallback);
            return fallback;
        }
    }

    /** Fast local distance calculation (km). */
    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }
}
