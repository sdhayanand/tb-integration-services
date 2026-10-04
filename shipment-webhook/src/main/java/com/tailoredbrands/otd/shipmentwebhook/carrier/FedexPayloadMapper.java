package com.tailoredbrands.otd.shipmentwebhook.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.event.ShipmentStatus;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidPayloadException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * FedEx Track style payload (different shape from UPS on purpose):
 * <pre>
 * { "trackingNumber": "794644790138",
 *   "shipperReference": "ORD-2026-000123",
 *   "latestStatusDetail": { "code": "OD", "description": "On FedEx vehicle for delivery" },
 *   "eventDateTime": "2026-10-03T08:15:00-07:00",
 *   "scanLocation": { "city": "OAKLAND", "stateOrProvinceCode": "CA", "countryCode": "US" } }
 * </pre>
 * Codes: OC label created; PU/DP/AR/IT/IX in transit; OD out for delivery; DL delivered;
 * DE/SE/CA/RS exception.
 */
@Component
public class FedexPayloadMapper implements CarrierPayloadMapper {

    public static final String CARRIER = "FEDEX";

    @Override
    public String carrier() {
        return CARRIER;
    }

    @Override
    public ShipmentEvent map(JsonNode payload, String correlationId, Clock clock) {
        String trackingNumber = CarrierPayloadMapper.requiredText(payload, "trackingNumber");
        String orderId = CarrierPayloadMapper.optionalText(payload, "shipperReference");
        if (orderId == null) {
            orderId = CarrierPayloadMapper.optionalText(payload, "customerReference");
        }
        if (orderId == null) {
            throw new InvalidPayloadException("missing order reference (shipperReference or customerReference)");
        }
        String code = CarrierPayloadMapper.requiredText(payload, "latestStatusDetail.code").toUpperCase(Locale.ROOT);
        ShipmentStatus status = switch (code) {
            case "OC" -> ShipmentStatus.LABEL_CREATED;
            case "PU", "DP", "AR", "IT", "IX", "AF" -> ShipmentStatus.IN_TRANSIT;
            case "OD" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "DL" -> ShipmentStatus.DELIVERED;
            case "DE", "SE", "CA", "RS" -> ShipmentStatus.EXCEPTION;
            default -> throw new InvalidPayloadException("unknown FedEx latestStatusDetail.code '" + code + "'");
        };
        return CarrierPayloadMapper.event(CARRIER, correlationId, clock, orderId, trackingNumber, status,
                statusTime(payload), location(payload));
    }

    private static Instant statusTime(JsonNode payload) {
        String value = CarrierPayloadMapper.optionalText(payload, "eventDateTime");
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidPayloadException("unparseable FedEx eventDateTime '" + value + "' (expected ISO-8601 with offset)");
        }
    }

    private static String location(JsonNode payload) {
        String city = CarrierPayloadMapper.optionalText(payload, "scanLocation.city");
        String state = CarrierPayloadMapper.optionalText(payload, "scanLocation.stateOrProvinceCode");
        if (city == null && state == null) {
            return null;
        }
        if (city != null) {
            // FedEx shouts: OAKLAND -> Oakland
            city = city.charAt(0) + city.substring(1).toLowerCase(Locale.ROOT);
        }
        return city == null ? state : (state == null ? city : city + ", " + state);
    }
}
