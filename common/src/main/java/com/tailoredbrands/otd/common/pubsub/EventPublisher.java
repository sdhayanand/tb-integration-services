package com.tailoredbrands.otd.common.pubsub;

import java.util.Map;

/**
 * Thin publishing abstraction used by the services (and mocked in unit tests).
 * Implementations are thread-safe.
 */
public interface EventPublisher extends AutoCloseable {

    /**
     * Publishes one message and blocks until Pub/Sub acknowledges it.
     *
     * @param topic       short topic id, e.g. {@code orders-v1}
     * @param orderingKey ordering key (storeId); {@code null}/empty publishes without ordering
     * @param jsonPayload UTF-8 JSON document (the event)
     * @param attributes  message attributes (see {@link EventAttributes}); {@code null} allowed
     * @return the Pub/Sub message id
     * @throws PublishException when the publish fails or times out
     */
    String publish(String topic, String orderingKey, String jsonPayload, Map<String, String> attributes);

    @Override
    void close();
}
