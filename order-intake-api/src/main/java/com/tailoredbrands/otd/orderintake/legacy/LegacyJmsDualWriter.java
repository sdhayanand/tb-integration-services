package com.tailoredbrands.otd.orderintake.legacy;

import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.orderintake.service.OrderCreatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Migration phase DUAL_RUN (ARCHITECTURE §7): after an order is committed (and queued for Pub/Sub via
 * the outbox), also send the legacy XML to EMS queue {@code TB.ORDERS.OUT} so legacy consumers keep
 * seeing the traffic. Pub/Sub is the system of record; a JMS failure is counted and logged, never
 * propagated (the reconciler reports the gap).
 */
public class LegacyJmsDualWriter {

    private static final Logger log = LoggerFactory.getLogger(LegacyJmsDualWriter.class);

    private final JmsTemplate jmsTemplate;
    private final LegacyOrderXmlWriter xmlWriter;
    private final String queue;
    private final Counter sent;
    private final Counter failed;

    public LegacyJmsDualWriter(JmsTemplate jmsTemplate, LegacyOrderXmlWriter xmlWriter, String queue,
                               MeterRegistry meterRegistry) {
        this.jmsTemplate = jmsTemplate;
        this.xmlWriter = xmlWriter;
        this.queue = queue;
        this.sent = Counter.builder("otd.legacy.dualwrite").tag("result", "sent").register(meterRegistry);
        this.failed = Counter.builder("otd.legacy.dualwrite").tag("result", "failed").register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent created) {
        OrderEvent event = created.event();
        String xml = xmlWriter.write(event.order());
        try {
            jmsTemplate.convertAndSend(queue, xml, message -> {
                message.setJMSCorrelationID(event.correlationId());
                message.setStringProperty(EventAttributes.EVENT_ID, event.eventId());
                message.setStringProperty(EventAttributes.EVENT_TYPE, event.eventType().name());
                message.setStringProperty(EventAttributes.SOURCE, event.source().name());
                message.setStringProperty(EventAttributes.STORE_ID, event.order().storeId());
                message.setStringProperty(EventAttributes.ORDER_ID, event.order().orderId());
                return message;
            });
            sent.increment();
            log.info("Dual-write to legacy EMS {}: orderId={}", queue, event.order().orderId());
        } catch (JmsException e) {
            failed.increment();
            log.error("Dual-write to legacy EMS {} failed for orderId={}: {}", queue, event.order().orderId(),
                    e.getMessage());
        }
    }
}
