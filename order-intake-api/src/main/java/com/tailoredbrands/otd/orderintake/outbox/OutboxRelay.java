package com.tailoredbrands.otd.orderintake.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.pubsub.PublishException;
import com.tailoredbrands.otd.orderintake.config.OtdProperties;
import com.tailoredbrands.otd.orderintake.repository.OutboxRepository;
import com.tailoredbrands.otd.orderintake.repository.OutboxRepository.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transactional-outbox relay: every {@code otd.outbox.relay-delay-ms} (500ms) it locks up to
 * {@code batchSize} unpublished rows ({@code FOR UPDATE SKIP LOCKED}), publishes them in id order with
 * their ordering key, and marks them published in the same transaction.
 *
 * <p>Ordering: rows are published sequentially in id order and the batch stops at the first failure, so
 * per-key order is preserved within one relay instance. With several replicas, the DB lock makes
 * them skip each other's rows, which can interleave keys across replicas; run one replica of the relay
 * (or shard by key) when strict cross-replica ordering is required - see README.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {
    };

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;
    private final Timer batchTimer;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, PlatformTransactionManager txManager,
                       OtdProperties properties, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(txManager);
        this.batchSize = Math.max(1, properties.outbox().batchSize());
        this.published = Counter.builder("otd.outbox.published")
                .description("Outbox rows successfully published to Pub/Sub").register(meterRegistry);
        this.failed = Counter.builder("otd.outbox.failed")
                .description("Outbox publish attempts that failed (row stays pending)").register(meterRegistry);
        this.batchTimer = Timer.builder("otd.outbox.batch")
                .description("Outbox relay batch duration").register(meterRegistry);
        Gauge.builder("otd.outbox.backlog", outbox, OutboxRepository::countUnpublished)
                .description("Unpublished outbox rows").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${otd.outbox.relay-delay-ms:500}",
            initialDelayString = "${otd.outbox.relay-delay-ms:500}")
    public void relay() {
        try {
            int relayed;
            do {
                relayed = batchTimer.record(this::relayBatch);
            } while (relayed == batchSize); // drain quickly when a burst is waiting
        } catch (RuntimeException e) {
            // never let the scheduler die; the rows stay pending and the next tick retries
            log.error("Outbox relay tick failed: {}", e.getMessage(), e);
        }
    }

    /** One locked batch; returns the number of rows published. Package-private for tests. */
    int relayBatch() {
        Integer count = transaction.execute(status -> {
            List<OutboxMessage> batch = outbox.lockUnpublished(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            List<Long> done = new ArrayList<>(batch.size());
            for (OutboxMessage message : batch) {
                try {
                    String messageId = publisher.publish(message.topic(), message.orderingKey(), message.payload(),
                            attributes(message));
                    done.add(message.id());
                    published.increment();
                    log.debug("Published outbox id={} aggregate={} topic={} key={} messageId={}", message.id(),
                            message.aggregateId(), message.topic(), message.orderingKey(), messageId);
                } catch (PublishException e) {
                    failed.increment();
                    log.warn("Publish failed for outbox id={} aggregate={} (will retry): {}", message.id(),
                            message.aggregateId(), e.getMessage());
                    break; // keep per-key ordering: do not publish later rows before this one
                }
            }
            outbox.markPublished(done);
            return done.size();
        });
        return count == null ? 0 : count;
    }

    private static Map<String, String> attributes(OutboxMessage message) {
        if (message.attributes() == null || message.attributes().isBlank()) {
            return Map.of();
        }
        try {
            return EventJson.MAPPER.readValue(message.attributes(), ATTRIBUTES);
        } catch (IOException e) {
            log.warn("Outbox id={} has unreadable attributes; publishing without them", message.id());
            return Map.of();
        }
    }
}
