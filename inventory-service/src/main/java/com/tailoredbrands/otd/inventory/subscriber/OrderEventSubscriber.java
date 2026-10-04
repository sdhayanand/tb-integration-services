package com.tailoredbrands.otd.inventory.subscriber;

import com.google.api.core.ApiService;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.PubsubMessage;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.common.pubsub.PubSubSubscriberFactory;
import com.tailoredbrands.otd.inventory.config.InventoryProperties;
import com.tailoredbrands.otd.inventory.service.ReservationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Streaming-pull consumer of {@code orders-inventory-service} (exactly-once, ordered by storeId).
 * One parallel pull + ordered delivery means events of one store are processed sequentially.
 * Business/DB failures are nacked (redelivered, then dead-lettered by the subscription policy).
 */
@Component
public class OrderEventSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderEventSubscriber.class);

    private final PubSubSubscriberFactory factory;
    private final ReservationService reservations;
    private final String subscription;
    private final MeterRegistry meterRegistry;
    private volatile Subscriber subscriber;

    public OrderEventSubscriber(PubSubSubscriberFactory factory, ReservationService reservations,
                                InventoryProperties properties, MeterRegistry meterRegistry) {
        this.factory = factory;
        this.reservations = reservations;
        this.subscription = properties.pubsub().ordersSubscription();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        Subscriber s = factory.create(subscription, this::receive);
        s.addListener(new ApiService.Listener() {
            @Override
            public void failed(ApiService.State from, Throwable failure) {
                log.error("Order subscriber failed (state {}): {}", from, failure.getMessage(), failure);
            }
        }, Runnable::run);
        s.startAsync();
        subscriber = s;
        log.info("Order event subscriber starting on {}", subscription);
    }

    void receive(PubsubMessage message, AckReplyConsumer reply) {
        MDC.put("correlationId", message.getAttributesOrDefault(EventAttributes.CORRELATION_ID, ""));
        MDC.put("eventId", message.getAttributesOrDefault(EventAttributes.EVENT_ID, message.getMessageId()));
        MDC.put("orderId", message.getAttributesOrDefault(EventAttributes.ORDER_ID, ""));
        try {
            OrderEvent event = EventJson.fromJson(message.getData().toByteArray(), OrderEvent.class);
            reservations.handle(event);
            count("ok");
            reply.ack();
        } catch (RuntimeException e) {
            count("error");
            log.error("Order event {} failed, nacking: {}", message.getMessageId(), e.getMessage(), e);
            reply.nack();
        } finally {
            MDC.remove("correlationId");
            MDC.remove("eventId");
            MDC.remove("orderId");
        }
    }

    private void count(String result) {
        Counter.builder("otd.inventory.messages")
                .description("orders-v1 messages received by inventory-service")
                .tag("result", result)
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
                log.warn("Order subscriber did not stop cleanly: {}", e.getMessage());
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
        return Integer.MAX_VALUE - 10;
    }
}
