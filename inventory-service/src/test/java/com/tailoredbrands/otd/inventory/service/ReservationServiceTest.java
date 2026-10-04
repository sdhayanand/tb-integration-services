package com.tailoredbrands.otd.inventory.service;

import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.InventoryLine;
import com.tailoredbrands.otd.common.event.InventoryLineStatus;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderEventType;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.pubsub.PublishException;
import com.tailoredbrands.otd.inventory.config.InventoryProperties;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository;
import com.tailoredbrands.otd.inventory.repository.InventoryRepository.Reservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T22:14:06Z");

    private final InventoryRepository repository = mock(InventoryRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ReservationService service;

    @BeforeEach
    void setUp() {
        InventoryProperties properties = new InventoryProperties(
                new InventoryProperties.PubSub("p", "", "orders-inventory-service", "inventory-v1"),
                new InventoryProperties.Stock("DC01"));
        service = new ReservationService(repository, publisher, properties, meters, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.markProcessed(anyString(), anyString())).thenReturn(true);
        when(publisher.publish(anyString(), anyString(), anyString(), anyMap())).thenReturn("msg-1");
    }

    private static OrderEvent orderCreated(String eventId, List<OrderLine> lines) {
        Order order = new Order("ORD-2026-000123", OrderType.TAILORED, Channel.STORE, "0412", "C-77812",
                Instant.parse("2026-10-03T22:14:00Z"), null, "USD", new BigDecimal("649.99"), lines, null, null);
        return new OrderEvent(eventId, OrderEventType.ORDER_CREATED, Instant.parse("2026-10-03T22:14:05Z"), "1",
                EventSource.ORDER_INTAKE_API, "corr-1", null, order);
    }

    @Test
    void reservesAtStoreSkipsAlterationLinesAndPublishesReservedWithOrderingKeyStoreId() {
        when(repository.tryReserve("MW-SUIT-NAVY-42R", "0412", 1)).thenReturn(true);
        OrderEvent event = orderCreated("evt-1", List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null),
                new OrderLine(2, "ALT-HEM-TROUSER", 1, new BigDecimal("50.00"), FulfillmentType.ALTERATION,
                        new Alteration("HEM", new BigDecimal("31.5"), "TS-EASTBAY"))));

        Optional<InventoryEvent> result = service.handle(event);

        assertThat(result).isPresent();
        InventoryEvent inv = result.get();
        assertThat(inv.eventType()).isEqualTo(InventoryEventType.INVENTORY_RESERVED);
        assertThat(inv.orderId()).isEqualTo("ORD-2026-000123");
        assertThat(inv.storeId()).isEqualTo("0412");
        assertThat(inv.correlationId()).isEqualTo("corr-1");
        assertThat(inv.source()).isEqualTo("INVENTORY_SERVICE");
        assertThat(inv.eventTime()).isEqualTo(NOW);
        assertThat(inv.lines()).containsExactly(
                new InventoryLine(1, "MW-SUIT-NAVY-42R", 1, InventoryLineStatus.RESERVED, "0412"));

        verify(repository).insertReservation(new Reservation("ORD-2026-000123", 1, "MW-SUIT-NAVY-42R", "0412", 1,
                InventoryLineStatus.RESERVED));
        verify(repository, never()).tryReserve(eq("ALT-HEM-TROUSER"), anyString(), anyInt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq("inventory-v1"), eq("0412"), anyString(), attributes.capture());
        assertThat(attributes.getValue())
                .containsEntry("eventType", "INVENTORY_RESERVED")
                .containsEntry("storeId", "0412")
                .containsEntry("correlationId", "corr-1")
                .containsEntry("source", "INVENTORY_SERVICE");
    }

    @Test
    void fallsBackToDcThenBackorders() {
        when(repository.tryReserve("JAB-SHIRT-WHITE-16", "0412", 2)).thenReturn(false);
        when(repository.tryReserve("JAB-SHIRT-WHITE-16", "DC01", 2)).thenReturn(true);
        when(repository.tryReserve("MW-SHOE-OXFORD-10", "0412", 1)).thenReturn(false);
        when(repository.tryReserve("MW-SHOE-OXFORD-10", "DC01", 1)).thenReturn(false);
        OrderEvent event = orderCreated("evt-2", List.of(
                new OrderLine(1, "JAB-SHIRT-WHITE-16", 2, new BigDecimal("79.50"), FulfillmentType.SHIP_TO_HOME, null),
                new OrderLine(2, "MW-SHOE-OXFORD-10", 1, new BigDecimal("129.00"), FulfillmentType.STORE_PICKUP, null)));

        InventoryEvent inv = service.handle(event).orElseThrow();

        assertThat(inv.eventType()).isEqualTo(InventoryEventType.INVENTORY_BACKORDERED);
        assertThat(inv.lines()).containsExactly(
                new InventoryLine(1, "JAB-SHIRT-WHITE-16", 2, InventoryLineStatus.RESERVED, "DC01"),
                new InventoryLine(2, "MW-SHOE-OXFORD-10", 1, InventoryLineStatus.BACKORDERED, null));
        verify(repository).insertReservation(new Reservation("ORD-2026-000123", 2, "MW-SHOE-OXFORD-10", null, 1,
                InventoryLineStatus.BACKORDERED));
    }

    @Test
    void duplicateEventIsIgnoredWithoutSideEffects() {
        when(repository.markProcessed("evt-1", ReservationService.CONSUMER)).thenReturn(false);
        OrderEvent event = orderCreated("evt-1", List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null)));

        assertThat(service.handle(event)).isEmpty();

        verify(repository, never()).tryReserve(anyString(), anyString(), anyInt());
        verify(publisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    void cancellationReleasesReservedLinesAndPublishesReleased() {
        when(repository.findReservations("ORD-2026-000123")).thenReturn(List.of(
                new Reservation("ORD-2026-000123", 1, "MW-SUIT-NAVY-42R", "0412", 1, InventoryLineStatus.RESERVED),
                new Reservation("ORD-2026-000123", 2, "MW-SHOE-OXFORD-10", null, 1, InventoryLineStatus.BACKORDERED)));
        OrderEvent created = orderCreated("evt-3", List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null)));
        OrderEvent cancelled = new OrderEvent("evt-4", OrderEventType.ORDER_CANCELLED, NOW, "1",
                EventSource.ORDER_INTAKE_API, "corr-2", null, created.order());

        InventoryEvent inv = service.handle(cancelled).orElseThrow();

        assertThat(inv.eventType()).isEqualTo(InventoryEventType.INVENTORY_RELEASED);
        assertThat(inv.lines()).extracting(InventoryLine::status).containsOnly(InventoryLineStatus.RELEASED);
        verify(repository).release("MW-SUIT-NAVY-42R", "0412", 1);
        verify(repository, never()).release(eq("MW-SHOE-OXFORD-10"), any(), anyInt());
        verify(repository).updateReservationStatus("ORD-2026-000123", 1, InventoryLineStatus.RELEASED);
        verify(repository).updateReservationStatus("ORD-2026-000123", 2, InventoryLineStatus.RELEASED);
    }

    @Test
    void inventoryEventIdIsDeterministicPerOrderEvent() {
        when(repository.tryReserve(anyString(), anyString(), anyInt())).thenReturn(true);
        OrderEvent event = orderCreated("evt-5", List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null)));

        String first = service.handle(event).orElseThrow().eventId();
        String second = service.handle(event).orElseThrow().eventId();

        assertThat(first).isEqualTo(second).isNotEqualTo("evt-5");
    }

    @Test
    void publishFailurePropagatesSoTheTransactionRollsBackAndTheMessageIsNacked() {
        when(repository.tryReserve(anyString(), anyString(), anyInt())).thenReturn(true);
        when(publisher.publish(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new PublishException("emulator down", null));
        OrderEvent event = orderCreated("evt-6", List.of(
                new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null)));

        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(PublishException.class);
    }
}
