package com.walletledger.domain.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Drains PENDING outbox events to PROCESSED. No real downstream consumer
 * wired up yet — this is intentionally a stub extension point (see
 * docs/02-tech-stack.md).
 */
@Component
@RequiredArgsConstructor
public class OutboxEventScheduler {

    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processPending() {
        List<OutboxEvent> batch = outboxEventRepository.findPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        batch.forEach(event -> {
            event.setStatus(OutboxEventStatus.PROCESSED);
            event.setProcessedAt(now);
        });
        outboxEventRepository.saveAll(batch);
    }
}
