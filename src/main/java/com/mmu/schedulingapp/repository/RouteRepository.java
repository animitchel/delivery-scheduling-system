package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Operator;
import com.mmu.schedulingapp.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RouteRepository extends JpaRepository<Route, Integer> {
    List<Route> findByOperator(Operator operatorId);
}
