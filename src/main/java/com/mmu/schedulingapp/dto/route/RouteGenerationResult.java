package com.mmu.schedulingapp.dto.route;

import java.time.LocalDate;
import java.util.List;

/** Committed generation acknowledgement, not a live status read or a planning preview. */
public record RouteGenerationResult(LocalDate scheduledDate, List<RouteDirectory.Item> routes, long processingTimeMs) { }
