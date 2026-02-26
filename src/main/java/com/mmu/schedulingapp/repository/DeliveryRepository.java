package com.mmu.schedulingapp.repository;


import com.mmu.schedulingapp.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DeliveryRepository extends JpaRepository<Delivery, Integer> {
    List<Delivery> findByScheduledDateLessThanEqualAndDeliveryStatus(
            LocalDate date, Delivery.DeliveryStatus status);
}
