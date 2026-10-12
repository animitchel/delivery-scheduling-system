package com.mmu.schedulingapp.account;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountAuditRepository extends JpaRepository<AccountAuditEvent, Long> { }
