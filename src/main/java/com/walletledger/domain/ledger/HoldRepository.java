package com.walletledger.domain.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HoldRepository extends JpaRepository<Hold, String> {

    Optional<Hold> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Hold h WHERE h.id = :id")
    Optional<Hold> findByIdForUpdate(@Param("id") String id);

    /**
     * Active holds already past expiry, locked so multiple scheduler instances
     * can run concurrently without double-processing the same hold.
     */
    @Query(value = "SELECT * FROM holds "
            + "WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at < now() "
            + "ORDER BY expires_at "
            + "LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Hold> findExpiredActiveHolds(@Param("limit") int limit);
}
