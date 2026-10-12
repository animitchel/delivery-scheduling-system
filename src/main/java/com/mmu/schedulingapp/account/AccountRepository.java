package com.mmu.schedulingapp.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<UserAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.id = :id")
    Optional<UserAccount> findForUpdate(@Param("id") Long id);

    Optional<UserAccount> findByUsername(String username);
    boolean existsByIdAndRole(Long id, AccountRole role);
    boolean existsByUsername(String username);
    boolean existsByRole(AccountRole role);
    boolean existsByOperatorOperatorId(Integer operatorId);
}
