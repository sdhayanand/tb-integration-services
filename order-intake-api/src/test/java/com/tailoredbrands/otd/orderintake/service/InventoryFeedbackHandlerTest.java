package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.inventory.InventoryFeedbackHandler;
import com.tailoredbrands.otd.orderintake.inventory.InventoryFeedbackHandler.Outcome;
import com.tailoredbrands.otd.orderintake.repository.InboxRepository;
import com.tailoredbrands.otd.orderintake.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryFeedbackHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T22:14:30Z");

    private final InboxRepository inbox = mock(InboxRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryFeedbackHandler handler =
            new InventoryFeedbackHandler(inbox, orders, Clock.fixed(NOW, ZoneOffset.UTC));

    private static InventoryEvent event(String id, InventoryEventType type) {
        return new InventoryEvent(id, type, NOW, "1", InventoryEvent.SOURCE_INVENTORY_SERVICE, "c1",
                "ORD-2026-000123", "0412", List.of());
    }

    @Test
    void reservedEventMovesOrderToReserved() {
        when(inbox.markProcessed("e1", InventoryFeedbackHandler.CONSUMER)).thenReturn(true);
        when(orders.findStatus("ORD-2026-000123")).thenReturn(Optional.of(OrderStatus.CREATED));

        assertThat(handler.handle(event("e1", InventoryEventType.INVENTORY_RESERVED))).isEqualTo(Outcome.APPLIED);

        verify(orders).updateStatus("ORD-2026-000123", OrderStatus.RESERVED, NOW);
    }

    @Test
    void backorderedEventMovesOrderToBackordered() {
        when(inbox.markProcessed(anyString(), anyString())).thenReturn(true);
        when(orders.findStatus(anyString())).thenReturn(Optional.of(OrderStatus.CREATED));

        handler.handle(event("e2", InventoryEventType.INVENTORY_BACKORDERED));

        verify(orders).updateStatus(eq("ORD-2026-000123"), eq(OrderStatus.BACKORDERED), any());
    }

    @Test
    void duplicateIsSkipped() {
        when(inbox.markProcessed("e1", InventoryFeedbackHandler.CONSUMER)).thenReturn(false);

        assertThat(handler.handle(event("e1", InventoryEventType.INVENTORY_RESERVED))).isEqualTo(Outcome.DUPLICATE);

        verify(orders, never()).updateStatus(any(), any(), any());
    }

    @Test
    void cancelledOrderIsNotResurrected() {
        when(inbox.markProcessed(anyString(), anyString())).thenReturn(true);
        when(orders.findStatus(anyString())).thenReturn(Optional.of(OrderStatus.CANCELLED));

        assertThat(handler.handle(event("e3", InventoryEventType.INVENTORY_RESERVED))).isEqualTo(Outcome.IGNORED);

        verify(orders, never()).updateStatus(any(), any(), any());
    }

    @Test
    void unknownOrderIsReportedNotFailed() {
        when(inbox.markProcessed(anyString(), anyString())).thenReturn(true);
        when(orders.findStatus(anyString())).thenReturn(Optional.empty());

        assertThat(handler.handle(event("e4", InventoryEventType.INVENTORY_BACKORDERED))).isEqualTo(Outcome.UNKNOWN_ORDER);
    }
}
