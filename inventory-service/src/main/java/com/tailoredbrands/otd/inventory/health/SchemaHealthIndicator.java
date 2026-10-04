package com.tailoredbrands.otd.inventory.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * inventory-service does not run Flyway (order-intake-api owns the schema, ARCHITECTURE §5.1), so
 * this indicator checks that migration V1 has been applied and is part of the readiness group:
 * the pod receives no traffic until the shared schema exists.
 */
@Component("schema")
public class SchemaHealthIndicator implements HealthIndicator {

    static final String REQUIRED_VERSION = "1";

    private final JdbcClient jdbc;

    public SchemaHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Long applied = jdbc.sql("""
                    SELECT count(*) FROM flyway_schema_history
                     WHERE version = :version AND success = true
                    """)
                    .param("version", REQUIRED_VERSION)
                    .query(Long.class)
                    .single();
            if (applied != null && applied > 0) {
                return Health.up().withDetail("flywayVersion", REQUIRED_VERSION).build();
            }
            return Health.down().withDetail("reason", "Flyway V1__schema.sql not applied (start order-intake-api first)").build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", "flyway_schema_history not readable: " + e.getMessage()).build();
        }
    }
}
