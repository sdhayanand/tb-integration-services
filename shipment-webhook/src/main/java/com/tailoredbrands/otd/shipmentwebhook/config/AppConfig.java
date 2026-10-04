package com.tailoredbrands.otd.shipmentwebhook.config;

import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.pubsub.PubSubPublisherFactory;
import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import com.tailoredbrands.otd.shipmentwebhook.security.HmacSignatureVerifier;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean(destroyMethod = "close")
    public EventPublisher eventPublisher(WebhookProperties properties) {
        return new PubSubPublisherFactory(properties.pubsub().project(), properties.pubsub().emulatorHost()).create();
    }

    /**
     * Signature verification is mandatory unless the secret is empty AND the {@code local} profile is
     * active (the {@code allowUnsigned} flag is only set in that profile).
     */
    @Bean
    public HmacSignatureVerifier hmacSignatureVerifier(WebhookProperties properties, Environment environment) {
        String secret = properties.webhook().sharedSecret();
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        boolean skip = (secret == null || secret.isBlank()) && local && properties.webhook().allowUnsigned();
        if (skip) {
            log.warn("WEBHOOK_SHARED_SECRET is empty and profile 'local' is active: carrier signatures are NOT verified");
        } else if (secret == null || secret.isBlank()) {
            log.error("WEBHOOK_SHARED_SECRET is empty outside the local profile: every carrier event will be rejected (401)");
        }
        return new HmacSignatureVerifier(secret, skip);
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Tailored Brands OTD - Shipment Webhook")
                .description("Carrier (UPS / FedEx) tracking webhooks -> canonical ShipmentEvent on Pub/Sub shipments-v1.")
                .version("v1"));
    }
}
