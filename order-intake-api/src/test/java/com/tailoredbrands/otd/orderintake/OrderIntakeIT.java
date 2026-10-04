package com.tailoredbrands.otd.orderintake;

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
import com.tailoredbrands.otd.common.event.InventoryEvent;
import com.tailoredbrands.otd.common.event.InventoryEventType;
import com.tailoredbrands.otd.common.event.InventoryLine;
import com.tailoredbrands.otd.common.event.InventoryLineStatus;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.event.OrderEventType;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.PubSubEmulatorContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end: POST /v1/orders -> outbox -> relay -> Pub/Sub emulator (orders-v1) -> test subscriber
 * sees the message with ordering key = storeId and the standard attributes; then an InventoryEvent
 * published to inventory-v1 flips the order status to RESERVED through the feedback subscriber.
 * Runs with failsafe (mvn verify); needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "otd.outbox.relay-delay-ms=200")
class OrderIntakeIT {

    static final String PROJECT = "tb-otd-it";
    static final String ORDERS_TOPIC = "orders-v1";
    static final String INVENTORY_TOPIC = "inventory-v1";
    static final String INVENTORY_SUBSCRIPTION = "inventory-order-intake";
    static final String TEST_SUBSCRIPTION = "orders-it-probe";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("otd").withUsername("otd").withPassword("otd");

    static final PubSubEmulatorContainer EMULATOR = new PubSubEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"));

    static ManagedChannel channel;
    static TransportChannelProvider channelProvider;
    static SubscriberStub subscriberStub;

    static {
        // started in a static block (not @Container) so the topology exists before the Spring context boots
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
        registry.add("otd.pubsub.orders-topic", () -> ORDERS_TOPIC);
        registry.add("otd.pubsub.inventory-subscription", () -> INVENTORY_SUBSCRIPTION);
    }

    static void createTopology() throws IOException {
        channel = ManagedChannelBuilder.forTarget(EMULATOR.getEmulatorEndpoint()).usePlaintext().build();
        channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
             SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                     .setTransportChannelProvider(channelProvider)
                     .setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            topics.createTopic(TopicName.of(PROJECT, ORDERS_TOPIC));
            topics.createTopic(TopicName.of(PROJECT, INVENTORY_TOPIC));
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName(ProjectSubscriptionName.of(PROJECT, TEST_SUBSCRIPTION).toString())
                    .setTopic(TopicName.of(PROJECT, ORDERS_TOPIC).toString())
                    .setEnableMessageOrdering(true)
                    .setAckDeadlineSeconds(30)
                    .build());
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName(ProjectSubscriptionName.of(PROJECT, INVENTORY_SUBSCRIPTION).toString())
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

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EventPublisher eventPublisher;

    @Test
    void orderIsPersistedRelayedToPubSubWithOrderingKeyAndStatusFollowsInventoryFeedback() throws Exception {
        String correlationId = "store-0412-txn-889213";
        String body = """
                {
                  "orderType": "TAILORED",
                  "storeId": "0412",
                  "customerId": "C-77812",
                  "promisedDate": "2026-10-10",
                  "lines": [
                    { "sku": "MW-SUIT-NAVY-42R", "quantity": 1, "unitPrice": 599.99, "fulfillmentType": "STORE_PICKUP" },
                    { "sku": "ALT-HEM-TROUSER", "quantity": 1, "unitPrice": 50.00, "fulfillmentType": "ALTERATION",
                      "alteration": { "type": "HEM", "measurementInches": 31.5, "tailorShopId": "TS-EASTBAY" } }
                  ]
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);

        // ---- POST /v1/orders -----------------------------------------------------------------
        ResponseEntity<Map> created = rest.postForEntity("/v1/orders", new HttpEntity<>(body, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) created.getBody().get("orderId");
        assertThat(orderId).matches("ORD-\\d{4}-\\d{6,}");
        assertThat(created.getBody().get("status")).isEqualTo("CREATED");
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath()).endsWith("/v1/orders/" + orderId);
        assertThat(created.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);

        // ---- GET /v1/orders/{id} ---------------------------------------------------------------
        ResponseEntity<Map> fetched = rest.getForEntity("/v1/orders/" + orderId, Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("status")).isEqualTo("CREATED");
        assertThat(fetched.getBody().get("totalAmount")).isEqualTo(649.99);
        assertThat((List<?>) fetched.getBody().get("lines")).hasSize(2);

        // ---- outbox relay -> Pub/Sub ------------------------------------------------------------
        List<ReceivedMessage> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            received.addAll(pull(TEST_SUBSCRIPTION));
            assertThat(received).anyMatch(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", "")));
        });
        ReceivedMessage message = received.stream()
                .filter(m -> orderId.equals(m.getMessage().getAttributesOrDefault("orderId", "")))
                .findFirst().orElseThrow();

        assertThat(message.getMessage().getOrderingKey()).isEqualTo("0412");
        assertThat(message.getMessage().getAttributesMap())
                .containsEntry(EventAttributes.EVENT_TYPE, "ORDER_CREATED")
                .containsEntry(EventAttributes.SCHEMA_VERSION, "1")
                .containsEntry(EventAttributes.SOURCE, "ORDER_INTAKE_API")
                .containsEntry(EventAttributes.STORE_ID, "0412")
                .containsEntry(EventAttributes.CORRELATION_ID, correlationId)
                .doesNotContainKey(EventAttributes.LEGACY_MESSAGE_ID);

        OrderEvent event = EventJson.fromJson(message.getMessage().getData().toByteArray(), OrderEvent.class);
        assertThat(event.eventType()).isEqualTo(OrderEventType.ORDER_CREATED);
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.order().orderId()).isEqualTo(orderId);
        assertThat(event.order().lines()).hasSize(2);
        assertThat(event.order().lines().get(1).alteration().tailorShopId()).isEqualTo("TS-EASTBAY");

        // ---- inventory feedback -> status RESERVED ---------------------------------------------
        InventoryEvent inventoryEvent = new InventoryEvent("it-inv-" + orderId, InventoryEventType.INVENTORY_RESERVED,
                Instant.now(), "1", InventoryEvent.SOURCE_INVENTORY_SERVICE, correlationId, orderId, "0412",
                List.of(new InventoryLine(1, "MW-SUIT-NAVY-42R", 1, InventoryLineStatus.RESERVED, "0412")));
        eventPublisher.publish(INVENTORY_TOPIC, "0412", EventJson.toJson(inventoryEvent),
                EventAttributes.forInventoryEvent(inventoryEvent));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            ResponseEntity<Map> after = rest.getForEntity("/v1/orders/" + orderId, Map.class);
            assertThat(after.getBody().get("status")).isEqualTo("RESERVED");
        });
    }

    @Test
    void validationErrorsAreProblemDetails() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.postForEntity("/v1/orders",
                new HttpEntity<>("{\"orderType\":\"RETAIL\",\"storeId\":\"12\",\"lines\":[]}", headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody().get("title")).isEqualTo("Validation failed");
        assertThat((List<?>) response.getBody().get("errors")).isNotEmpty();
        assertThat(response.getBody().get("correlationId")).isNotNull();
    }

    @Test
    void unknownOrderIs404ProblemDetail() {
        ResponseEntity<Map> response = rest.getForEntity("/v1/orders/ORD-1999-000000", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("orderId")).isEqualTo("ORD-1999-000000");
    }

    @Test
    void legacyTransformHelperAndSoapEndpointWork() throws Exception {
        String legacyXml = new String(getClass().getClassLoader().getResourceAsStream("legacy-order-sample.xml")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        HttpHeaders xml = new HttpHeaders();
        xml.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> transformed = rest.postForEntity("/v1/legacy/transform", new HttpEntity<>(legacyXml, xml),
                String.class);
        assertThat(transformed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transformed.getBody()).contains("\"orderType\":\"TAILORED\"").contains("\"storeId\":\"0412\"");

        // WSDL is exposed
        ResponseEntity<String> wsdl = rest.getForEntity("/ws/orders.wsdl", String.class);
        assertThat(wsdl.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wsdl.getBody()).contains("SubmitOrderRequest").contains("OrdersPort");

        // SOAP submit through the same OrderService path (source = LEGACY_SOAP_ADAPTER)
        String envelope = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                """ + legacyXml.substring(legacyXml.indexOf("<SubmitOrderRequest")) + """
                  </soapenv:Body>
                </soapenv:Envelope>
                """;
        HttpHeaders soap = new HttpHeaders();
        soap.setContentType(MediaType.TEXT_XML);
        ResponseEntity<String> soapResponse = rest.postForEntity("/ws/orders", new HttpEntity<>(envelope, soap), String.class);
        assertThat(soapResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(soapResponse.getBody()).contains("SubmitOrderResponse").contains("ACCEPTED").contains("ORD-");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            List<ReceivedMessage> messages = pull(TEST_SUBSCRIPTION);
            assertThat(messages).anyMatch(m -> "LEGACY_SOAP_ADAPTER".equals(
                    m.getMessage().getAttributesOrDefault(EventAttributes.SOURCE, "")));
        });
    }

    /** Synchronous pull + ack on the probe subscription. */
    @SuppressWarnings("deprecation") // returnImmediately: fine for a test probe, avoids long-polling the emulator
    private static List<ReceivedMessage> pull(String subscription) {
        PullResponse response = subscriberStub.pullCallable().call(PullRequest.newBuilder()
                .setSubscription(ProjectSubscriptionName.of(PROJECT, subscription).toString())
                .setMaxMessages(50)
                .setReturnImmediately(true)
                .build());
        List<ReceivedMessage> messages = response.getReceivedMessagesList();
        if (!messages.isEmpty()) {
            subscriberStub.acknowledgeCallable().call(AcknowledgeRequest.newBuilder()
                    .setSubscription(ProjectSubscriptionName.of(PROJECT, subscription).toString())
                    .addAllAckIds(messages.stream().map(ReceivedMessage::getAckId).toList())
                    .build());
        }
        return messages;
    }
}
