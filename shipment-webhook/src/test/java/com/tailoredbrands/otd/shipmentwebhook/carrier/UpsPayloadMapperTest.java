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

class UpsPayloadMapperTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T16:00:00Z"), ZoneOffset.UTC);
    private final UpsPayloadMapper mapper = new UpsPayloadMapper();

    private static JsonNode json(String s) throws Exception {
        return EventJson.MAPPER.readTree(s);
    }

    @Test
    void mapsOutForDeliveryWithLocalTimeAndOffset() throws Exception {
        ShipmentEvent event = mapper.map(json("""
                {"trackingNumber":"1Z999AA10123456784",
                 "localActivityDate":"20261003","localActivityTime":"081500","gmtOffset":"-07:00",
                 "activityStatus":{"type":"I","code":"OT","description":"Out For Delivery Today"},
                 "activityLocation":{"city":"Oakland","stateProvince":"CA","countryCode":"US"},
                 "referenceNumbers":[{"code":"PO","value":"ORD-2026-000123"}]}
                """), "corr-1", CLOCK);

        assertThat(event.carrier()).isEqualTo("UPS");
        assertThat(event.orderId()).isEqualTo("ORD-2026-000123");
        assertThat(event.trackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(event.status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(event.statusTime()).isEqualTo(Instant.parse("2026-10-03T15:15:00Z"));
        assertThat(event.location()).isEqualTo("Oakland, CA");
        assertThat(event.eventType()).isEqualTo("SHIPMENT_UPDATED");
        assertThat(event.source()).isEqualTo("SHIPMENT_WEBHOOK");
        assertThat(event.schemaVersion()).isEqualTo("1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.eventTime()).isEqualTo(CLOCK.instant());
        assertThat(event.eventId()).isNotBlank();
    }

    @Test
    void mapsStatusTypes() throws Exception {
        assertThat(status("M", null)).isEqualTo(ShipmentStatus.LABEL_CREATED);
        assertThat(status("P", null)).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(status("I", "DP")).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(status("I", "OT")).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(status("D", null)).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(status("X", "48")).isEqualTo(ShipmentStatus.EXCEPTION);
    }

    @Test
    void fallsBackToOrdReferenceAndDefaultsTimeToNow() throws Exception {
        ShipmentEvent event = mapper.map(json("""
                {"trackingNumber":"1Z1","activityStatus":{"type":"D"},
                 "referenceNumbers":[{"code":"XX","value":"ORD-2026-000009"}]}
                """), "c", CLOCK);
        assertThat(event.orderId()).isEqualTo("ORD-2026-000009");
        assertThat(event.statusTime()).isEqualTo(CLOCK.instant());
        assertThat(event.location()).isNull();
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> mapper.map(json("{\"activityStatus\":{\"type\":\"D\"}}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("trackingNumber");
        assertThatThrownBy(() -> mapper.map(json("{\"trackingNumber\":\"1Z1\",\"activityStatus\":{\"type\":\"D\"}}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("order reference");
        assertThatThrownBy(() -> mapper.map(json("{\"trackingNumber\":\"1Z1\",\"orderId\":\"ORD-1\",\"activityStatus\":{\"type\":\"Z\"}}"), "c", CLOCK))
                .isInstanceOf(InvalidPayloadException.class).hasMessageContaining("activityStatus.type");
    }

    private ShipmentStatus status(String type, String code) throws Exception {
        String codeJson = code == null ? "" : ",\"code\":\"" + code + "\"";
        return mapper.map(json("{\"trackingNumber\":\"1Z1\",\"orderId\":\"ORD-1\",\"activityStatus\":{\"type\":\"" + type + "\""
                + codeJson + "}}"), "c", CLOCK).status();
    }
}
