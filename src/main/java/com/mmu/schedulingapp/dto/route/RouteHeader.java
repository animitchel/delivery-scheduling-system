package com.mmu.schedulingapp.dto.route;

import com.mmu.schedulingapp.model.Route;
import java.time.LocalDate;

/** Scalar directory projection: no stop or recipient entities are materialized. */
public record RouteHeader(Integer routeId, Integer operatorId, String operatorName,
                          LocalDate scheduledDate, Route.RouteStatus routeStatus) { }
