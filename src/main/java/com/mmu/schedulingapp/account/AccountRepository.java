package com.mmu.schedulingapp.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByRole(AccountRole role);
    boolean existsByOperatorOperatorId(Integer operatorId);
}
