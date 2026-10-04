package com.tailoredbrands.otd.inventory;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.TopicName;
import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderEventType;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.PubSubEmulatorContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Publishes an ORDER_CREATED event to the emulator; asserts the reservation row, the stock update,
 * the INVENTORY_RESERVED event on inventory-v1 (ordering key = storeId) and the GET endpoint; then
 * cancels and asserts the release. Uses order-intake-api's Flyway migrations (filesystem location).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:../order-intake-api/src/main/resources/db/migration"
})
class InventoryServiceIT {

    static final String PROJECT = "tb-otd-it";
    static final String ORDERS_TOPIC = "orders-v1";
    static final String INVENTORY_TOPIC = "inventory-v1";
    static final String ORDERS_SUBSCRIPTION = "orders-inventory-service";
    static final String PROBE_SUBSCRIPTION = "inventory-it-probe";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("otd").withUsername("otd").withPassword("otd");
    static final PubSubEmulatorContainer EMULATOR = new PubSubEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"));

    static ManagedChannel channel;
    static SubscriberStub subscriberStub;

    static {
        POSTGRES.start();
        EMULATOR.start();
        try {
            createTopology();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Pub/Sub topology in emulator", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("otd.pubsub.project", () -> PROJECT);
        registry.add("otd.pubsub.emulator-host", EMULATOR::getEmulatorEndpoint);
        registry.add("otd.pubsub.orders-subscription", () -> ORDERS_SUBSCRIPTION);
        registry.add("otd.pubsub.inventory-topic", () -> INVENTORY_TOPIC);
    }

    static void createTopology() throws IOException {
        channel = ManagedChannelBuilder.forTarget(EMULATOR.getEmulatorEndpoint()).usePlaintext().build();
        TransportChannelProvider channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
             SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                     .setTransportChannelProvider(channelProvider)
                     .setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            topics.createTopic(TopicName.of(PROJECT, ORDERS_TOPIC));
            topics.createTopic(TopicName.of(PROJECT, INVENTORY_TOPIC));
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName(ProjectSubscriptionName.of(PROJECT, ORDERS_SUBSCRIPTION).toString())
                    .setTopic(TopicName.of(PROJECT, ORDERS_TOPIC).toString())
                    .setEnableMessageOrdering(true)
                    .setAckDeadlineSeconds(30)
                    .build());
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName(ProjectSubscriptionName.of(PROJECT, PROBE_SUBSCRIPTION).toString())
                    .setTopic(TopicName.of(PROJECT, INVENTORY_TOPIC).toString())
                    .setEnableMessageOrdering(true)
                    .setAckDeadlineSeconds(30)
                    .build());
        }
        subscriberStub = GrpcSubscriberStub.create(SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
    }

    @AfterAll
    static void shutdown() throws InterruptedException {
        if (subscriberStub != null) {
            subscriberStub.close();
        }
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        EMULATOR.stop();
        POSTGRES.stop();
    }

    @Autowired
    EventPublisher publisher;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestRestTemplate rest;

    @Test
    void orderCreatedReservesStockAndPublishesInventoryEventThenCancelReleases() {
        String orderId = "ORD-2026-" + String.format("%06d", (int) (System.nanoTime() % 1_000_000));
        String correlationId = "it-" + UUID.randomUUID();
        Order order = new Order(orderId, OrderType.TAILORED, Channel.STORE, "0412", "C-77812",
                Instant.parse("2026-10-03T22:14:00Z"), null, "USD", new BigDecimal("649.99"),
                List.of(
                        new OrderLine(1, "MW-SUIT-NAVY-42R", 1, new BigDecimal("599.99"), FulfillmentType.STORE_PICKUP, null),
                        new OrderLine(2, "ALT-HEM-TROUSER", 1, new BigDecimal("50.00"), FulfillmentType.ALTERATION,
                                new Alteration("HEM", new BigDecimal("31.5"), "TS-EASTBAY"))),
                null, null);
        OrderEvent created = new OrderEvent(UUID.randomUUID().toString(), OrderEventType.ORDER_CREATED, Instant.now(),
                "1", EventSource.ORDER_INTAKE_API, correlationId, null, order);

        int reservedBefore = reservedAt("MW-SUIT-NAVY-42R", "0412");

        // ---- ORDER_CREATED -> reservation + INVENTORY_RESERVED ----------------------------------
        publisher.publish(ORDERS_TOPIC, "0412", EventJson.toJson(created), EventAttributes.forOrderEvent(created));

        List<ReceivedMessage> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            received.addAll(pull());
            assertThat(received).anyMatch(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", "")));
        });
        ReceivedMessage message = received.stream()
                .filter(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", "")))
                .findFirst().orElseThrow();
        assertThat(message.getMessage().getOrderingKey()).isEqualTo("0412");
        assertThat(message.getMessage().getAttributesMap())
                .containsEntry("eventType", "INVENTORY_RESERVED")
                .containsEntry("source", "INVENTORY_SERVICE")
                .containsEntry("storeId", "0412")
                .containsEntry("correlationId", correlationId);
        InventoryEvent inventoryEvent = EventJson.fromJson(message.getMessage().getData().toByteArray(), InventoryEvent.class);
        assertThat(inventoryEvent.eventType()).isEqualTo(InventoryEventType.INVENTORY_RESERVED);
        assertThat(inventoryEvent.lines()).hasSize(1); // alteration line is not stock
        assertThat(inventoryEvent.lines().get(0).locationId()).isEqualTo("0412");

        assertThat(reservedAt("MW-SUIT-NAVY-42R", "0412")).isEqualTo(reservedBefore + 1);
        assertThat(jdbc.sql("SELECT status FROM inventory_reservations WHERE order_id = :id AND line_number = 1")
                .param("id", orderId).query(String.class).single()).isEqualTo("RESERVED");
        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE message_id = :id AND consumer = 'inventory-service'")
                .param("id", created.eventId()).query(Long.class).single()).isEqualTo(1L);

        // ---- GET /v1/inventory/{sku} ------------------------------------------------------------
        ResponseEntity<Map> availability = rest.getForEntity("/v1/inventory/MW-SUIT-NAVY-42R", Map.class);
        assertThat(availability.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(availability.getBody().get("sku")).isEqualTo("MW-SUIT-NAVY-42R");
        List<Map<String, Object>> locations = (List<Map<String, Object>>) availability.getBody().get("locations");
        assertThat(locations).extracting(l -> l.get("locationId")).containsExactly("0412", "0875", "DC01");

        assertThat(rest.getForEntity("/v1/inventory/NOPE-SKU", Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // ---- redelivery of the same event is a no-op ----------------------------------------------
        publisher.publish(ORDERS_TOPIC, "0412", EventJson.toJson(created), EventAttributes.forOrderEvent(created));

        // ---- ORDER_CANCELLED -> release ---------------------------------------------------------
        OrderEvent cancelled = new OrderEvent(UUID.randomUUID().toString(), OrderEventType.ORDER_CANCELLED, Instant.now(),
                "1", EventSource.ORDER_INTAKE_API, correlationId, null, order);
        publisher.publish(ORDERS_TOPIC, "0412", EventJson.toJson(cancelled), EventAttributes.forOrderEvent(cancelled));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            received.addAll(pull());
            assertThat(received).anyMatch(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", ""))
                    && "INVENTORY_RELEASED".equals(m.getMessage().getAttributesOrDefault("eventType", "")));
        });
        assertThat(reservedAt("MW-SUIT-NAVY-42R", "0412")).isEqualTo(reservedBefore);
        assertThat(jdbc.sql("SELECT status FROM inventory_reservations WHERE order_id = :id AND line_number = 1")
                .param("id", orderId).query(String.class).single()).isEqualTo("RELEASED");
        // the duplicate ORDER_CREATED did not reserve again
        assertThat(received.stream().filter(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", ""))
                && "INVENTORY_RESERVED".equals(m.getMessage().getAttributesOrDefault("eventType", ""))).count()).isEqualTo(1);
    }

    @Test
    void schemaHealthIndicatorIsUpWhenMigrationsApplied() {
        ResponseEntity<Map> readiness = rest.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().get("status")).isEqualTo("UP");
    }

    private int reservedAt(String sku, String location) {
        return jdbc.sql("SELECT reserved FROM inventory WHERE sku = :sku AND location_id = :loc")
                .param("sku", sku).param("loc", location).query(Integer.class).single();
    }

    @SuppressWarnings("deprecation")
    private static List<ReceivedMessage> pull() {
        PullResponse response = subscriberStub.pullCallable().call(PullRequest.newBuilder()
                .setSubscription(ProjectSubscriptionName.of(PROJECT, PROBE_SUBSCRIPTION).toString())
                .setMaxMessages(50)
                .setReturnImmediately(true)
                .build());
        List<ReceivedMessage> messages = response.getReceivedMessagesList();
        if (!messages.isEmpty()) {
            subscriberStub.acknowledgeCallable().call(AcknowledgeRequest.newBuilder()
                    .setSubscription(ProjectSubscriptionName.of(PROJECT, PROBE_SUBSCRIPTION).toString())
                    .addAllAckIds(messages.stream().map(ReceivedMessage::getAckId).toList())
                    .build());
        }
        return messages;
    }
}
