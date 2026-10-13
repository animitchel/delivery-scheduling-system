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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Operator o where o.operatorId = :id")
    java.util.Optional<Operator> lockById(@org.springframework.data.repository.query.Param("id") Integer id);

    @org.springframework.data.jpa.repository.Query("select o.operatorId from Operator o where o.availabilityStatus = com.mmu.schedulingapp.model.Operator.AvailabilityStatus.AVAILABLE order by o.operatorId")
    List<Integer> findAvailableIds();

    @org.springframework.data.jpa.repository.Query("select o.operatorId from Operator o where o.availabilityStatus = com.mmu.schedulingapp.model.Operator.AvailabilityStatus.AVAILABLE and not exists (select r.routeId from Route r where r.operator=o and r.routeStatus <> com.mmu.schedulingapp.model.Route.RouteStatus.COMPLETED) order by o.operatorId")
    List<Integer> findPlanningIds(org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select new com.mmu.schedulingapp.dto.operator.OperatorItem(o.operatorId,o.name,o.phoneNumber,o.email,o.vehicleType,o.maxCapacity,w.warehouseId,o.availabilityStatus) from Operator o left join o.warehouse w where lower(o.name) like :pattern escape '!' or lower(o.email) like :pattern escape '!'")
    org.springframework.data.domain.Page<com.mmu.schedulingapp.dto.operator.OperatorItem> directory(@org.springframework.data.repository.query.Param("pattern") String pattern,org.springframework.data.domain.Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select count(r)>0 from Route r where r.operator.operatorId=:id and r.routeStatus <> com.mmu.schedulingapp.model.Route.RouteStatus.COMPLETED")
    boolean hasActiveRoutes(@org.springframework.data.repository.query.Param("id") int id);
    @org.springframework.data.jpa.repository.Query("select count(d)>0 from Delivery d where d.operatorId.operatorId=:id and d.deliveryStatus in (com.mmu.schedulingapp.model.Delivery.DeliveryStatus.PENDING,com.mmu.schedulingapp.model.Delivery.DeliveryStatus.IN_PROGRESS)")
    boolean hasUnfinishedDeliveries(@org.springframework.data.repository.query.Param("id") int id);
    @org.springframework.data.jpa.repository.Query("select count(a)>0 from UserAccount a where a.operator.operatorId=:id")
    boolean hasAccounts(@org.springframework.data.repository.query.Param("id") int id);
    @org.springframework.data.jpa.repository.Query("select count(r)>0 from Route r where r.operator.operatorId=:id")
    boolean hasRoutes(@org.springframework.data.repository.query.Param("id") int id);
    @org.springframework.data.jpa.repository.Query("select count(d)>0 from Delivery d where d.operatorId.operatorId=:id")
    boolean hasDeliveries(@org.springframework.data.repository.query.Param("id") int id);
    // Find all operators by availability status
    List<Operator> findAllByAvailabilityStatus(Operator.AvailabilityStatus status);
}
