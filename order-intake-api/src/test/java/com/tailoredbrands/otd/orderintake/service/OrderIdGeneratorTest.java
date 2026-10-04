package com.tailoredbrands.otd.orderintake.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdGeneratorTest {

    private static final Clock CLOCK_2026 = Clock.fixed(Instant.parse("2026-10-03T22:14:00Z"), ZoneOffset.UTC);

    @Test
    void formatsPrefixYearAndZeroPaddedSequence() {
        assertThat(OrderIdGenerator.format(Year.of(2026), 123)).isEqualTo("ORD-2026-000123");
        assertThat(OrderIdGenerator.format(Year.of(2026), 1)).isEqualTo("ORD-2026-000001");
        assertThat(OrderIdGenerator.format(Year.of(2027), 1_234_567)).isEqualTo("ORD-2027-1234567");
    }

    @Test
    void usesSequenceAndClock() {
        AtomicLong seq = new AtomicLong(41);
        OrderIdGenerator generator = new OrderIdGenerator(seq::incrementAndGet, CLOCK_2026);

        assertThat(generator.next()).isEqualTo("ORD-2026-000042");
        assertThat(generator.next()).isEqualTo("ORD-2026-000043");
    }

    @Test
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> OrderIdGenerator.format(Year.of(2026), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
