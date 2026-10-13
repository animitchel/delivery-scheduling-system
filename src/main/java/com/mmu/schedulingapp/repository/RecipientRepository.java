package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Recipient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientRepository extends JpaRepository<Recipient, Integer> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Recipient r where r.recipientId=:id")
    java.util.Optional<Recipient> lockForUpdate(@org.springframework.data.repository.query.Param("id") Integer id);

    @org.springframework.data.jpa.repository.Query("select new com.mmu.schedulingapp.dto.recipient.RecipientDirectory$Item(r.recipientId,r.firstName,r.lastName,r.address,r.contactNumber,r.priorityLevel) from Recipient r where lower(concat(r.firstName,' ',r.lastName)) like :pattern escape '!' or lower(r.address) like :pattern escape '!'")
    org.springframework.data.domain.Page<com.mmu.schedulingapp.dto.recipient.RecipientDirectory.Item> directory(@org.springframework.data.repository.query.Param("pattern") String pattern,org.springframework.data.domain.Pageable pageable);

}
