package com.tailoredbrands.otd.orderintake.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Transactional outbox (ARCHITECTURE §5.1 {@code outbox}). Rows are appended inside the business
 * transaction and relayed to Pub/Sub by {@code OutboxRelay}.
 */
@Repository
public class OutboxRepository {

    /** One pending outbox row; {@code payload} and {@code attributes} are JSON text. */
    public record OutboxMessage(long id, String aggregateId, String topic, String orderingKey, String payload,
                                String attributes) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String aggregateId, String topic, String orderingKey, String payloadJson, String attributesJson) {
        jdbc.sql("""
                INSERT INTO outbox (aggregate_id, topic, ordering_key, payload, attributes, created_at)
                VALUES (:aggregateId, :topic, :orderingKey, CAST(:payload AS jsonb), CAST(:attributes AS jsonb), now())
                """)
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("orderingKey", orderingKey)
                .param("payload", payloadJson)
                .param("attributes", attributesJson)
                .update();
    }

    /**
     * Locks and returns up to {@code limit} unpublished rows in insertion order. Must be called inside
     * a transaction; concurrent relays skip each other's rows ({@code FOR UPDATE SKIP LOCKED}).
     */
    public List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, aggregate_id, topic, ordering_key, payload::text AS payload, attributes::text AS attributes
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getString("aggregate_id"),
                        rs.getString("topic"),
                        rs.getString("ordering_key"),
                        rs.getString("payload"),
                        rs.getString("attributes")))
                .list();
    }

    public int markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }
}
