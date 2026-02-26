package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Recipient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientRepository extends JpaRepository<Recipient, Integer> {
}
