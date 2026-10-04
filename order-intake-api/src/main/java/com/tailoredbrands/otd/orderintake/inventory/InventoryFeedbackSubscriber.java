package com.tailoredbrands.otd.orderintake.inventory;

import com.google.api.core.ApiService;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.PubsubMessage;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.common.pubsub.PubSubSubscriberFactory;
import com.tailoredbrands.otd.orderintake.config.OtdProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Streaming-pull subscriber on {@code inventory-order-intake} (topic {@code inventory-v1}).
 * Starts after the rest of the context (late phase) and stops first on shutdown so in-flight messages
 * are acked or nacked before the DB pool closes. Errors are nacked; the subscription's dead-letter
 * policy (5 attempts) moves poison messages to {@code events-dlq}.
 */
@Component
public class InventoryFeedbackSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InventoryFeedbackSubscriber.class);

    private final PubSubSubscriberFactory factory;
    private final InventoryFeedbackHandler handler;
    private final String subscription;
    private final MeterRegistry meterRegistry;
    private volatile Subscriber subscriber;

    public InventoryFeedbackSubscriber(PubSubSubscriberFactory factory, InventoryFeedbackHandler handler,
                                       OtdProperties properties, MeterRegistry meterRegistry) {
        this.factory = factory;
        this.handler = handler;
        this.subscription = properties.pubsub().inventorySubscription();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        Subscriber s = factory.create(subscription, this::receive);
        s.addListener(new ApiService.Listener() {
            @Override
            public void failed(ApiService.State from, Throwable failure) {
                log.error("Inventory subscriber failed (state {}): {}", from, failure.getMessage(), failure);
            }
        }, Runnable::run);
        s.startAsync();
        subscriber = s;
        log.info("Inventory feedback subscriber starting on {}", subscription);
    }

    void receive(PubsubMessage message, AckReplyConsumer reply) {
        String correlationId = message.getAttributesOrDefault(EventAttributes.CORRELATION_ID, "");
        MDC.put("correlationId", correlationId);
        MDC.put("eventId", message.getAttributesOrDefault(EventAttributes.EVENT_ID, message.getMessageId()));
        try {
            InventoryEvent event = EventJson.fromJson(message.getData().toByteArray(), InventoryEvent.class);
            MDC.put("orderId", event.orderId() == null ? "" : event.orderId());
            InventoryFeedbackHandler.Outcome outcome = handler.handle(event);
            count("ok", outcome.name().toLowerCase());
            reply.ack();
        } catch (RuntimeException e) {
            count("error", e.getClass().getSimpleName());
            log.error("Inventory event {} failed, nacking: {}", message.getMessageId(), e.getMessage(), e);
            reply.nack();
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
            MDC.remove("orderId");
        }
    }

    private void count(String result, String detail) {
        Counter.builder("otd.inventory.feedback")
                .description("Inventory feedback messages processed")
                .tag("result", result)
                .tag("detail", detail)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void stop() {
        Subscriber s = subscriber;
        if (s != null) {
            try {
                s.stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Inventory subscriber did not stop cleanly: {}", e.getMessage());
            }
            subscriber = null;
        }
    }

    @Override
    public boolean isRunning() {
        Subscriber s = subscriber;
        return s != null && s.isRunning();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 10; // start late, stop early
    }
}
