package com.tailoredbrands.otd.orderintake.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;
import java.util.function.LongSupplier;

/**
 * Generates {@code ORD-yyyy-nnnnnn} from the Postgres sequence {@code order_seq}
 * (ARCHITECTURE §5.1). The sequence is global, the year is informational.
 */
@Component
public class OrderIdGenerator {

    static final String PREFIX = "ORD-";

    private final LongSupplier sequence;
    private final Clock clock;

    /** Spring constructor; {@code @Autowired} because the class has a second (package-private) constructor for tests. */
    @Autowired
    public OrderIdGenerator(JdbcClient jdbc, Clock clock) {
        this(() -> jdbc.sql("SELECT nextval('order_seq')").query(Long.class).single(), clock);
    }

    OrderIdGenerator(LongSupplier sequence, Clock clock) {
        this.sequence = sequence;
        this.clock = clock;
    }

    public String next() {
        return format(Year.now(clock), sequence.getAsLong());
    }

    static String format(Year year, long sequenceValue) {
        if (sequenceValue < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        return String.format("%s%d-%06d", PREFIX, year.getValue(), sequenceValue);
    }
}
