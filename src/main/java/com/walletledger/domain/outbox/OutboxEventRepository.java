package com.walletledger.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    // Lock is already expressed directly in the native SQL (FOR UPDATE SKIP
    // LOCKED) — @Lock is illegal on a native query (Hibernate has no way to
    // inject a JPA lock mode into raw SQL) and throws at runtime if added.
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findPendingBatch(@Param("limit") int limit);
}
