package com.tailoredbrands.otd.orderintake.service;

import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.pubsub.PublishException;
import com.tailoredbrands.otd.orderintake.config.OtdProperties;
import com.tailoredbrands.otd.orderintake.outbox.OutboxRelay;
import com.tailoredbrands.otd.orderintake.repository.OutboxRepository;
import com.tailoredbrands.otd.orderintake.repository.OutboxRepository.OutboxMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OtdProperties properties = new OtdProperties(
            new OtdProperties.PubSub("p", "", "orders-v1", "inventory-order-intake"),
            new OtdProperties.Outbox(100, 500),
            new OtdProperties.Migration("PUBSUB_PRIMARY"),
            new OtdProperties.Legacy("", "", "", "TB.ORDERS.OUT", ""));

    private static OutboxMessage row(long id, String key) {
        return new OutboxMessage(id, "ORD-2026-00000" + id, "orders-v1", key, "{\"eventId\":\"e" + id + "\"}",
                "{\"eventType\":\"ORDER_CREATED\",\"storeId\":\"" + key + "\"}");
    }

    @Test
    void publishesPendingRowsWithOrderingKeyAndAttributesThenMarksThem() {
        when(repository.lockUnpublished(100)).thenReturn(List.of(row(1, "0412"), row(2, "0875")));
        when(publisher.publish(anyString(), anyString(), anyString(), anyMap())).thenReturn("m1", "m2");

        OutboxRelay relay = new OutboxRelay(repository, publisher, txManager, properties, meters);
        int relayed = relay.relayBatch();

        assertThat(relayed).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq("orders-v1"), eq("0412"), eq("{\"eventId\":\"e1\"}"), attributes.capture());
        assertThat(attributes.getValue()).containsEntry("eventType", "ORDER_CREATED").containsEntry("storeId", "0412");
        verify(repository).markPublished(List.of(1L, 2L));
        assertThat(meters.get("otd.outbox.published").counter().count()).isEqualTo(2.0);
    }

    @Test
    void stopsAtFirstFailureToPreserveOrderAndKeepsFailedRowPending() {
        when(repository.lockUnpublished(anyInt())).thenReturn(List.of(row(1, "0412"), row(2, "0412"), row(3, "0875")));
        when(publisher.publish(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn("m1")
                .thenThrow(new PublishException("boom", null));

        OutboxRelay relay = new OutboxRelay(repository, publisher, txManager, properties, meters);
        int relayed = relay.relayBatch();

        assertThat(relayed).isEqualTo(1);
        verify(repository).markPublished(List.of(1L));
        verify(publisher, never()).publish(any(), eq("0875"), any(), any());
        assertThat(meters.get("otd.outbox.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void emptyOutboxIsANoOp() {
        when(repository.lockUnpublished(anyInt())).thenReturn(List.of());

        OutboxRelay relay = new OutboxRelay(repository, publisher, txManager, properties, meters);

        assertThat(relay.relayBatch()).isZero();
        verify(publisher, never()).publish(any(), any(), any(), any());
        verify(repository, never()).markPublished(anyList());
    }
}
