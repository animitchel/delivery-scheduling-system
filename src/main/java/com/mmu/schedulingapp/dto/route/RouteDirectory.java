package com.mmu.schedulingapp.dto.route;

import com.mmu.schedulingapp.model.Route;
import java.time.LocalDate;
import java.util.List;

public record RouteDirectory(List<Item> routes, int page, int size, long totalElements, int totalPages) {
    public record Item(Integer routeId, Integer operatorId, String operatorName, LocalDate scheduledDate,
                       Route.RouteStatus routeStatus, long stopCount, long finishedCount) { }
}
