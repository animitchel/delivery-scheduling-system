package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorRepository extends JpaRepository<Operator, Integer> {
    @org.springframework.data.jpa.repository.Query("""
            select operator from Operator operator
            where not exists (select account.id from UserAccount account where account.operator = operator)
              and lower(operator.name) like lower(concat('%', :search, '%'))
            """)
    org.springframework.data.domain.Page<Operator> findEligibleForAccount(
            @org.springframework.data.repository.query.Param("search") String search,
            org.springframework.data.domain.Pageable pageable);

    // Find all operators by availability status
    List<Operator> findAllByAvailabilityStatus(Operator.AvailabilityStatus status);
}
