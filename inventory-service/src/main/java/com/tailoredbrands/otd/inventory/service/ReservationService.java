package com.tailoredbrands.otd.inventory.service;

import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.InventoryLine;
import com.tailoredbrands.otd.common.event.InventoryLineStatus;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.inventory.config.InventoryProperties;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository.Reservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reservation logic. {@code ORDER_CREATED}: for every non-alteration line try the order's store, then the
 * DC, else backorder; {@code ORDER_CANCELLED}: release. One transaction per event with the inbox row,
 * the reservation rows and the stock update; the {@link InventoryEvent} is published right before
 * commit so a failed publish rolls everything back and the message is redelivered. The inventory
 * event id is derived deterministically from the order event id, so the rare "published but commit
 * failed" case yields a duplicate that downstream consumers dedupe.
 */
@Service
public class ReservationService {

    public static final String CONSUMER = "inventory-service";

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final InventoryRepository repository;
    private final EventPublisher publisher;
    private final String inventoryTopic;
    private final String fallbackLocation;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ReservationService(InventoryRepository repository, EventPublisher publisher, InventoryProperties properties,
                              MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.inventoryTopic = properties.pubsub().inventoryTopic();
        this.fallbackLocation = properties.stock().fallbackLocation();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * @return the published inventory event, or empty when the message was a duplicate / not relevant
     */
    @Transactional
    public Optional<InventoryEvent> handle(OrderEvent event) {
        if (event.eventId() == null || event.eventId().isBlank() || event.order() == null) {
            throw new IllegalArgumentException("OrderEvent without eventId/order");
        }
        if (!repository.markProcessed(event.eventId(), CONSUMER)) {
            log.info("Duplicate order event ignored: eventId={} orderId={}", event.eventId(), event.order().orderId());
            count("duplicate");
            return Optional.empty();
        }
        InventoryEvent result = switch (event.eventType()) {
            case ORDER_CREATED -> reserve(event);
            case ORDER_CANCELLED -> release(event);
            case ORDER_UPDATED -> null; // amendments are out of scope for the demo; logged and acked
        };
        if (result == null) {
            log.info("Order event {} ({}) needs no inventory action", event.eventId(), event.eventType());
            count("ignored");
            return Optional.empty();
        }
        publisher.publish(inventoryTopic, result.storeId(), EventJson.toJson(result),
                EventAttributes.forInventoryEvent(result));
        count(result.eventType().name().toLowerCase());
        log.info("Inventory event published: {} orderId={} lines={}", result.eventType(), result.orderId(),
                result.lines().size());
        return Optional.of(result);
    }

    private InventoryEvent reserve(OrderEvent event) {
        Order order = event.order();
        List<InventoryLine> lines = new ArrayList<>();
        boolean allReserved = true;
        for (OrderLine line : order.lines()) {
            if (line.fulfillmentType() == FulfillmentType.ALTERATION) {
                continue; // services, not stock
            }
            String location = reserveAt(line, order.storeId());
            InventoryLineStatus status = location == null ? InventoryLineStatus.BACKORDERED : InventoryLineStatus.RESERVED;
            allReserved &= status == InventoryLineStatus.RESERVED;
            repository.insertReservation(new Reservation(order.orderId(), line.lineNumber(), line.sku(), location,
                    line.quantity(), status));
            lines.add(new InventoryLine(line.lineNumber(), line.sku(), line.quantity(), status, location));
            log.debug("Line {} sku={} qty={} -> {} at {}", line.lineNumber(), line.sku(), line.quantity(), status, location);
        }
        InventoryEventType type = allReserved ? InventoryEventType.INVENTORY_RESERVED : InventoryEventType.INVENTORY_BACKORDERED;
        return inventoryEvent(event, type, lines);
    }

    /** Store first, then the DC; {@code null} when neither has enough available stock. */
    private String reserveAt(OrderLine line, String storeId) {
        if (storeId != null && repository.tryReserve(line.sku(), storeId, line.quantity())) {
            return storeId;
        }
        if (fallbackLocation != null && !fallbackLocation.equals(storeId)
                && repository.tryReserve(line.sku(), fallbackLocation, line.quantity())) {
            return fallbackLocation;
        }
        return null;
    }

    private InventoryEvent release(OrderEvent event) {
        Order order = event.order();
        List<InventoryLine> lines = new ArrayList<>();
        for (Reservation reservation : repository.findReservations(order.orderId())) {
            if (reservation.status() == InventoryLineStatus.RESERVED && reservation.locationId() != null) {
                repository.release(reservation.sku(), reservation.locationId(), reservation.quantity());
            }
            if (reservation.status() != InventoryLineStatus.RELEASED) {
                repository.updateReservationStatus(order.orderId(), reservation.lineNumber(), InventoryLineStatus.RELEASED);
            }
            lines.add(new InventoryLine(reservation.lineNumber(), reservation.sku(), reservation.quantity(),
                    InventoryLineStatus.RELEASED, reservation.locationId()));
        }
        return inventoryEvent(event, InventoryEventType.INVENTORY_RELEASED, lines);
    }

    private InventoryEvent inventoryEvent(OrderEvent event, InventoryEventType type, List<InventoryLine> lines) {
        String eventId = UUID.nameUUIDFromBytes((event.eventId() + ":" + type.name()).getBytes(StandardCharsets.UTF_8))
                .toString();
        return new InventoryEvent(eventId, type, Instant.now(clock), InventoryEvent.SCHEMA_VERSION,
                InventoryEvent.SOURCE_INVENTORY_SERVICE, event.correlationId(), event.order().orderId(),
                event.order().storeId(), lines);
    }

    private void count(String outcome) {
        Counter.builder("otd.inventory.orders")
                .description("Order events handled by inventory-service")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
