package com.tailoredbrands.otd.orderintake.inventory;

import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.repository.InboxRepository;
import com.tailoredbrands.otd.orderintake.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Applies an {@link InventoryEvent} to {@code orders.status} idempotently: the event id is recorded in
 * the {@code inbox} table in the same transaction as the update, so redeliveries are no-ops.
 */
@Service
public class InventoryFeedbackHandler {

    public static final String CONSUMER = "order-intake-api";

    private static final Logger log = LoggerFactory.getLogger(InventoryFeedbackHandler.class);

    private final InboxRepository inbox;
    private final OrderRepository orders;
    private final Clock clock;

    public InventoryFeedbackHandler(InboxRepository inbox, OrderRepository orders, Clock clock) {
        this.inbox = inbox;
        this.orders = orders;
        this.clock = clock;
    }

    public enum Outcome { APPLIED, DUPLICATE, IGNORED, UNKNOWN_ORDER }

    @Transactional
    public Outcome handle(InventoryEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("InventoryEvent without eventId");
        }
        if (!inbox.markProcessed(event.eventId(), CONSUMER)) {
            log.info("Duplicate inventory event ignored: eventId={} orderId={}", event.eventId(), event.orderId());
            return Outcome.DUPLICATE;
        }
        OrderStatus target = switch (event.eventType()) {
            case INVENTORY_RESERVED -> OrderStatus.RESERVED;
            case INVENTORY_BACKORDERED -> OrderStatus.BACKORDERED;
            case INVENTORY_RELEASED -> null; // release follows a cancellation; status already CANCELLED
        };
        if (target == null) {
            return Outcome.IGNORED;
        }
        Optional<OrderStatus> current = orders.findStatus(event.orderId());
        if (current.isEmpty()) {
            // can happen in SHADOW phase when the order came through the EMS bridge, not this API
            log.warn("Inventory event for unknown order: orderId={} eventId={}", event.orderId(), event.eventId());
            return Outcome.UNKNOWN_ORDER;
        }
        if (current.get() == OrderStatus.CANCELLED) {
            log.info("Order {} already CANCELLED; inventory event {} ignored", event.orderId(), event.eventType());
            return Outcome.IGNORED;
        }
        orders.updateStatus(event.orderId(), target, Instant.now(clock));
        log.info("Order status updated: orderId={} {} -> {} (eventId={})", event.orderId(), current.get(), target,
                event.eventId());
        return Outcome.APPLIED;
    }
}
