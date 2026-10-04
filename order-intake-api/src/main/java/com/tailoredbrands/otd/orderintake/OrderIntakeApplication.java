package com.tailoredbrands.otd.orderintake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-intake-api: REST + SOAP order intake, transactional outbox relay to Pub/Sub {@code orders-v1},
 * inventory feedback consumer, optional dual-write to the legacy EMS during migration phase DUAL_RUN.
 *
 * <p>The Artemis/JMS auto-configuration is excluded on purpose: the legacy JMS connection must only
 * exist in DUAL_RUN (see {@code legacy.LegacyJmsConfig}); otherwise Boot would wire a connection
 * factory to localhost:61616 and the JMS health indicator would report DOWN.
 */
@SpringBootApplication(exclude = {ArtemisAutoConfiguration.class, JmsAutoConfiguration.class})
@ConfigurationPropertiesScan
@EnableScheduling
public class OrderIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderIntakeApplication.class, args);
    }
}
