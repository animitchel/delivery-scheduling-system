package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorRepository extends JpaRepository<Operator, Integer> {
    // Find all operators by availability status
    List<Operator> findAllByAvailabilityStatus(Operator.AvailabilityStatus status);
}
