package com.walletledger.domain.outbox;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Called from inside the same transaction as the business write it
 * describes — that's what makes this a correct "transactional outbox"
 * (see docs/04-design-decisions.md #9).
 */
@Component
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventPublisher(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    public void publish(String eventType, Map<String, Object> payload) {
        OutboxEvent event = OutboxEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .build();
        outboxEventRepository.save(event);
    }
}
