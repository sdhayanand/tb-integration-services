package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderEventType;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.orderintake.config.OtdProperties;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.domain.StoredOrder;
import com.tailoredbrands.otd.orderintake.error.OrderNotFoundException;
import com.tailoredbrands.otd.orderintake.repository.OrderRepository;
import com.tailoredbrands.otd.orderintake.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The single order-creation path used by REST and SOAP: validate, assign id, persist order + lines
 * + outbox row in ONE transaction. Publishing happens asynchronously from the outbox.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final OrderIdGenerator idGenerator;
    private final OrderValidator validator;
    private final OtdProperties properties;
    private final ApplicationEventPublisher applicationEvents;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public OrderService(OrderRepository orders, OutboxRepository outbox, OrderIdGenerator idGenerator,
                        OrderValidator validator, OtdProperties properties,
                        ApplicationEventPublisher applicationEvents, MeterRegistry meterRegistry, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.idGenerator = idGenerator;
        this.validator = validator;
        this.properties = properties;
        this.applicationEvents = applicationEvents;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * @param draft         canonical order without id
     * @param source        who is submitting (ORDER_INTAKE_API / LEGACY_SOAP_ADAPTER)
     * @param correlationId end-to-end correlation id
     * @return the ORDER_CREATED event that was written to the outbox
     */
    @Transactional
    public OrderEvent create(Order draft, EventSource source, String correlationId) {
        validator.validate(draft);

        Instant now = Instant.now(clock);
        Order order = draft.withOrderId(idGenerator.next());
        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), OrderEventType.ORDER_CREATED, now,
                OrderEvent.SCHEMA_VERSION, source, correlationId, null, order);

        MDC.put("orderId", order.orderId());
        MDC.put("eventId", event.eventId());
        try {
            orders.insert(order, OrderStatus.CREATED, correlationId, now);
            outbox.append(order.orderId(), properties.pubsub().ordersTopic(), order.storeId(),
                    EventJson.toJson(event), EventJson.toJson(EventAttributes.forOrderEvent(event)));

            Counter.builder("otd.orders.created")
                    .description("Orders accepted by order-intake-api")
                    .tag("source", source.name())
                    .tag("orderType", order.orderType().name())
                    .register(meterRegistry)
                    .increment();
            log.info("Order created: orderId={} type={} store={} lines={} source={}", order.orderId(),
                    order.orderType(), order.storeId(), order.lines().size(), source);

            // delivered AFTER_COMMIT to the dual-writer (if enabled)
            applicationEvents.publishEvent(new OrderCreatedEvent(event));
            return event;
        } finally {
            MDC.remove("orderId");
            MDC.remove("eventId");
        }
    }

    @Transactional(readOnly = true)
    public StoredOrder get(String orderId) {
        return orders.find(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
