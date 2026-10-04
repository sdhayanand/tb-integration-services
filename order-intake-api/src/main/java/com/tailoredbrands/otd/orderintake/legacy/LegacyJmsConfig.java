package com.tailoredbrands.otd.orderintake.legacy;

import com.tailoredbrands.otd.orderintake.config.OtdProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

/**
 * Only active when {@code MIGRATION_PHASE=DUAL_RUN} and {@code LEGACY_JMS_URL} is set: connects to the
 * TIBCO EMS stand-in (Artemis, {@code tcp://host:61616}) with the Artemis Jakarta JMS client.
 */
@Configuration
@ConditionalOnProperty(name = "otd.migration.phase", havingValue = "DUAL_RUN")
@ConditionalOnExpression("'${otd.legacy.jms-url:}' != ''")
public class LegacyJmsConfig {

    private static final Logger log = LoggerFactory.getLogger(LegacyJmsConfig.class);

    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory legacyConnectionFactory(OtdProperties properties) {
        OtdProperties.Legacy legacy = properties.legacy();
        log.info("Migration phase DUAL_RUN: dual-writing orders to legacy JMS {} queue {}", legacy.jmsUrl(),
                legacy.jmsQueue());
        ActiveMQConnectionFactory artemis = new ActiveMQConnectionFactory(legacy.jmsUrl());
        if (legacy.jmsUser() != null && !legacy.jmsUser().isBlank()) {
            artemis.setUser(legacy.jmsUser());
            artemis.setPassword(legacy.jmsPassword());
        }
        CachingConnectionFactory caching = new CachingConnectionFactory(artemis);
        caching.setSessionCacheSize(5);
        return caching;
    }

    @Bean
    public JmsTemplate legacyJmsTemplate(ConnectionFactory legacyConnectionFactory) {
        JmsTemplate template = new JmsTemplate(legacyConnectionFactory);
        template.setDeliveryPersistent(true);
        template.setExplicitQosEnabled(true);
        return template;
    }

    @Bean
    public LegacyJmsDualWriter legacyJmsDualWriter(JmsTemplate legacyJmsTemplate, LegacyOrderXmlWriter xmlWriter,
                                                   OtdProperties properties, MeterRegistry meterRegistry) {
        return new LegacyJmsDualWriter(legacyJmsTemplate, xmlWriter, properties.legacy().jmsQueue(), meterRegistry);
    }
}
