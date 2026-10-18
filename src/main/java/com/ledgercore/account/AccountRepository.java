package com.ledgercore.account;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<AccountRef> findRefById(UUID id);

    Optional<AccountRef> findRefByAccountNumber(String accountNumber);

    Optional<AccountRef> findRefByTypeAndCurrency(AccountType type, String currency);

    List<Account> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    long countByOwnerIdAndType(UUID ownerId, AccountType type);

    Optional<Account> findFirstByTypeAndCurrency(AccountType type, String currency);

    Page<Account> findByStatus(AccountStatus status, Pageable pageable);

    /**
     * Row-locks all given accounts (SELECT ... FOR UPDATE) in ascending id order. Always acquiring locks in the same
     * global order is what makes A->B and B->A transfers deadlock-free.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdOrdered(@Param("ids") Collection<UUID> ids);
}
