package com.mmu.schedulingapp.repository;


import com.mmu.schedulingapp.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DeliveryRepository extends JpaRepository<Delivery, Integer> {
    @Query(value = DriverAssignmentQueries.SELECT_DELIVERY, nativeQuery = true)
    java.util.Optional<Delivery> findOwnedDelivery(@Param("accountId") Long accountId, @Param("authRevision") long authRevision, @Param("deliveryId") Integer deliveryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = DriverAssignmentQueries.UPDATE_STATUS, nativeQuery = true)
    int updateOwnedDeliveryStatus(@Param("accountId") Long accountId, @Param("authRevision") long authRevision, @Param("deliveryId") Integer deliveryId,
                                  @Param("status") String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.deliveryId = :id")
    java.util.Optional<Delivery> lockById(@Param("id") Integer id);

    @Query("select d.deliveryId from Delivery d where d.scheduledDate <= :date and d.deliveryStatus = com.mmu.schedulingapp.model.Delivery.DeliveryStatus.PENDING order by d.deliveryId")
    List<Integer> findPendingIds(@Param("date") LocalDate date, org.springframework.data.domain.Pageable pageable);

    List<Delivery> findByScheduledDateLessThanEqualAndDeliveryStatus(
            LocalDate date, Delivery.DeliveryStatus status);
}
