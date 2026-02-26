package com.mmu.schedulingapp.utility;

import lombok.Getter;

@Getter
public class DistanceResult {
    private final double distanceKm;
    private final String durationText;
    private final int durationSeconds;

    public DistanceResult(double distanceKm, String durationText, int durationSeconds) {
        this.distanceKm = distanceKm;
        this.durationText = durationText;
        this.durationSeconds = durationSeconds;
    }

    @Override
    public String toString() {
        return String.format("%.2f km, %s (%d seconds)", distanceKm, durationText, durationSeconds);
    }
}
