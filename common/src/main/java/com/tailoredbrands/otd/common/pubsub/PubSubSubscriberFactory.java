package com.tailoredbrands.otd.common.pubsub;

import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Builds streaming-pull {@link Subscriber}s: one parallel pull (ordering!), flow control of
 * 100 outstanding messages, emulator support identical to {@link PubSubPublisherFactory}.
 */
public final class PubSubSubscriberFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PubSubSubscriberFactory.class);

    private final String projectId;
    private final String emulatorHost;
    private final List<ManagedChannel> channels = new CopyOnWriteArrayList<>();

    public PubSubSubscriberFactory(String projectId, String emulatorHost) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.emulatorHost = emulatorHost == null || emulatorHost.isBlank() ? null : emulatorHost.trim();
    }

    public static PubSubSubscriberFactory fromEnvironment(String projectId) {
        return new PubSubSubscriberFactory(projectId, System.getenv(PubSubPublisherFactory.EMULATOR_HOST_ENV));
    }

    public boolean usesEmulator() {
        return emulatorHost != null;
    }

    public String projectId() {
        return projectId;
    }

    /**
     * Creates (but does not start) a subscriber. Call {@code startAsync().awaitRunning()} to start and
     * {@code stopAsync()} to stop it.
     */
    public Subscriber create(String subscription, MessageReceiver receiver) {
        ProjectSubscriptionName name = ProjectSubscriptionName.of(projectId, subscription);
        Subscriber.Builder builder = Subscriber.newBuilder(name, receiver)
                .setParallelPullCount(1)
                .setFlowControlSettings(FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount(100L)
                        .setMaxOutstandingRequestBytes(10L * 1024 * 1024)
                        .build());
        if (usesEmulator()) {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
            channels.add(channel);
            builder.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                    .setCredentialsProvider(NoCredentialsProvider.create());
        }
        log.info("Pub/Sub subscriber created: {} (emulator={})", name, usesEmulator() ? emulatorHost : "no");
        return builder.build();
    }

    @Override
    public void close() {
        channels.forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }
}
