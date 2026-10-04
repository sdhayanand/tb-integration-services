package com.tailoredbrands.otd.orderintake.config;

import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.pubsub.PubSubPublisherFactory;
import com.tailoredbrands.otd.common.pubsub.PubSubSubscriberFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PubSubConfig {

    @Bean
    public PubSubPublisherFactory pubSubPublisherFactory(OtdProperties properties) {
        return new PubSubPublisherFactory(properties.pubsub().project(), properties.pubsub().emulatorHost());
    }

    @Bean(destroyMethod = "close")
    public EventPublisher eventPublisher(PubSubPublisherFactory factory) {
        return factory.create();
    }

    @Bean(destroyMethod = "close")
    public PubSubSubscriberFactory pubSubSubscriberFactory(OtdProperties properties) {
        return new PubSubSubscriberFactory(properties.pubsub().project(), properties.pubsub().emulatorHost());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
