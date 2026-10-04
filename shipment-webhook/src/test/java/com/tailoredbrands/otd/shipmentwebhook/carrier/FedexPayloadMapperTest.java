package com.tailoredbrands.otd.shipmentwebhook.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.event.ShipmentStatus;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidPayloadException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FedexPayloadMapperTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneOffset.UTC);
    private final FedexPayloadMapper mapper = new FedexPayloadMapper();

    private static JsonNode json(String s) throws Exception {
        return EventJson.MAPPER.readTree(s);
    }

    @Test
    void mapsFedexShape() throws Exception {
        ShipmentEvent event = mapper.map(json("""
                {"trackingNumber":"794644790138","shipperReference":"ORD-2026-000123",
                 "latestStatusDetail":{"code":"OD","description":"On FedEx vehicle for delivery"},
                 "eventDateTime":"2026-10-03T08:15:00-07:00",
                 "scanLocation":{"city":"OAKLAND","stateOrProvinceCode":"CA","countryCode":"US"}}
                """), "corr-2", CLOCK);

        assertThat(event.carrier()).isEqualTo("FEDEX");
        assertThat(event.orderId()).isEqualTo("ORD-2026-000123");
        assertThat(event.trackingNumber()).isEqualTo("794644790138");
        assertThat(event.status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(event.statusTime()).isEqualTo(Instant.parse("2026-10-03T15:15:00Z"));
        assertThat(event.location()).isEqualTo("Oakland, CA");
    }

    @Test
    void mapsCodes() throws Exception {
        assertThat(status("OC")).isEqualTo(ShipmentStatus.LABEL_CREATED);
        assertThat(status("PU")).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(status("IT")).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(status("od")).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(status("DL")).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(status("DE")).isEqualTo(ShipmentStatus.EXCEPTION);
    }

    @Test
    void customerReferenceIsAcceptedAsFallback() throws Exception {
        ShipmentEvent event = mapper.map(json("""
                {"trackingNumber":"7946","customerReference":"ORD-2026-000777","latestStatusDetail":{"code":"DL"}}
                """), "c", CLOCK);
        assertThat(event.orderId()).isEqualTo("ORD-2026-000777");
        assertThat(event.statusTime()).isEqualTo(CLOCK.instant());
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> mapper.map(json("{\"trackingNumber\":\"7946\",\"latestStatusDetail\":{\"code\":\"DL\"}}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("order reference");
        assertThatThrownBy(() -> mapper.map(json("{\"trackingNumber\":\"7946\",\"shipperReference\":\"ORD-1\",\"latestStatusDetail\":{\"code\":\"ZZ\"}}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("ZZ");
        assertThatThrownBy(() -> mapper.map(json("{\"trackingNumber\":\"7946\",\"shipperReference\":\"ORD-1\",\"latestStatusDetail\":{\"code\":\"DL\"},\"eventDateTime\":\"yesterday\"}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("eventDateTime");
    }

    private ShipmentStatus status(String code) throws Exception {
        return mapper.map(json("{\"trackingNumber\":\"7946\",\"shipperReference\":\"ORD-1\",\"latestStatusDetail\":{\"code\":\""
                + code + "\"}}"), "c", CLOCK).status();
    }
}
