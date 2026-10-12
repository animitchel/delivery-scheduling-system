package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RouteRepository extends JpaRepository<Route, Integer> {
    @Query(value = DriverAssignmentQueries.OWNED_ROUTES + " ORDER BY r.scheduled_date DESC, r.route_id DESC", nativeQuery = true)
    List<Route> findOwnedRoutes(@Param("accountId") Long accountId);

    @Query(value = DriverAssignmentQueries.OWNED_ROUTES + " AND r.route_id = :routeId", nativeQuery = true)
    java.util.Optional<Route> findOwnedRoute(@Param("accountId") Long accountId, @Param("routeId") Integer routeId);

    List<Route> findByOperator(Operator operatorId);
}
