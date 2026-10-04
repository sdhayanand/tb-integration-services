package com.tailoredbrands.otd.orderintake.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotent-consumer inbox (ARCHITECTURE §5.1 {@code inbox}): the event id is recorded in the same
 * transaction as the side effect, so a redelivered message is detected and skipped.
 */
@Repository
public class InboxRepository {

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} if the message was not seen before (and is now recorded), {@code false} if it
     * is a duplicate.
     */
    public boolean markProcessed(String messageId, String consumer) {
        int inserted = jdbc.sql("""
                INSERT INTO inbox (message_id, consumer, processed_at)
                VALUES (:messageId, :consumer, now())
                ON CONFLICT (message_id) DO NOTHING
                """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .update();
        return inserted == 1;
    }
}
