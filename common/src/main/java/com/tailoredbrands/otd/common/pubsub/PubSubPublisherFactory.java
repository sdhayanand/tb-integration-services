package com.tailoredbrands.otd.common.pubsub;

import com.google.api.core.ApiFuture;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds Pub/Sub {@link Publisher}s with message ordering enabled and returns them behind the
 * {@link EventPublisher} interface. Honors {@code PUBSUB_EMULATOR_HOST}: when set, a plaintext
 * gRPC channel to that host and {@link NoCredentialsProvider} are used (CONVENTIONS "Pub/Sub in Java").
 */
public final class PubSubPublisherFactory {

    public static final String EMULATOR_HOST_ENV = "PUBSUB_EMULATOR_HOST";

    private static final Logger log = LoggerFactory.getLogger(PubSubPublisherFactory.class);

    private final String projectId;
    private final String emulatorHost;
    private final Duration publishTimeout;

    public PubSubPublisherFactory(String projectId, String emulatorHost) {
        this(projectId, emulatorHost, Duration.ofSeconds(30));
    }

    public PubSubPublisherFactory(String projectId, String emulatorHost, Duration publishTimeout) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.emulatorHost = emulatorHost == null || emulatorHost.isBlank() ? null : emulatorHost.trim();
        this.publishTimeout = Objects.requireNonNull(publishTimeout, "publishTimeout");
    }

    /** Reads {@code PUBSUB_EMULATOR_HOST} from the process environment. */
    public static PubSubPublisherFactory fromEnvironment(String projectId) {
        return new PubSubPublisherFactory(projectId, System.getenv(EMULATOR_HOST_ENV));
    }

    public boolean usesEmulator() {
        return emulatorHost != null;
    }

    public String projectId() {
        return projectId;
    }

    /** Returns a lazily-connecting publisher that manages one {@link Publisher} per topic. */
    public EventPublisher create() {
        log.info("Pub/Sub publisher factory: project={} emulator={}", projectId,
                usesEmulator() ? emulatorHost : "(none, real API)");
        return new PubSubEventPublisher();
    }

    private Publisher newPublisher(String topic, List<ManagedChannel> channels) throws IOException {
        Publisher.Builder builder = Publisher.newBuilder(TopicName.of(projectId, topic))
                .setEnableMessageOrdering(true);
        if (usesEmulator()) {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
            channels.add(channel);
            builder.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                    .setCredentialsProvider(NoCredentialsProvider.create());
        }
        return builder.build();
    }

    private final class PubSubEventPublisher implements EventPublisher {

        private final Map<String, Publisher> publishers = new ConcurrentHashMap<>();
        private final List<ManagedChannel> channels = new CopyOnWriteArrayList<>();

        @Override
        public String publish(String topic, String orderingKey, String jsonPayload, Map<String, String> attributes) {
            Publisher publisher = publishers.computeIfAbsent(topic, t -> {
                try {
                    return newPublisher(t, channels);
                } catch (IOException e) {
                    throw new PublishException("Cannot create publisher for topic " + t, e);
                }
            });

            PubsubMessage.Builder message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8(jsonPayload));
            if (attributes != null) {
                attributes.forEach((k, v) -> {
                    if (k != null && v != null) {
                        message.putAttributes(k, v);
                    }
                });
            }
            String key = orderingKey == null ? "" : orderingKey;
            if (!key.isEmpty()) {
                message.setOrderingKey(key);
            }

            ApiFuture<String> future = publisher.publish(message.build());
            try {
                return future.get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resume(publisher, key);
                throw new PublishException("Interrupted while publishing to " + topic, e);
            } catch (ExecutionException e) {
                resume(publisher, key);
                throw new PublishException("Publish to " + topic + " failed: " + e.getCause().getMessage(), e.getCause());
            } catch (TimeoutException e) {
                resume(publisher, key);
                throw new PublishException("Publish to " + topic + " timed out after " + publishTimeout, e);
            }
        }

        /** After a failure the publisher pauses the ordering key; resume so the next retry can go through. */
        private void resume(Publisher publisher, String key) {
            if (!key.isEmpty()) {
                publisher.resumePublish(key);
            }
        }

        @Override
        public void close() {
            publishers.values().forEach(p -> {
                try {
                    p.shutdown();
                    p.awaitTermination(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Publisher shutdown interrupted: {}", e.getMessage());
                }
            });
            publishers.clear();
            channels.forEach(ManagedChannel::shutdownNow);
            channels.clear();
        }
    }
}
