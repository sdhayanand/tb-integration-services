package com.tailoredbrands.otd.orderintake.config;

import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebConfig {

    /** Correlation-id filter at highest precedence so every log line (REST and SOAP) carries it. */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Tailored Brands OTD - Order Intake API")
                .description("Order intake (REST + SOAP legacy adapter) publishing canonical OrderEvents to Pub/Sub orders-v1 "
                        + "through a transactional outbox.")
                .version("v1")
                .license(new License().name("Apache-2.0")));
    }
}
