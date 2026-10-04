package com.tailoredbrands.otd.shipmentwebhook.carrier;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.event.ShipmentStatus;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidPayloadException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * UPS Track Alert style payload:
 * <pre>
 * { "trackingNumber": "1Z999AA10123456784",
 *   "localActivityDate": "20261003", "localActivityTime": "081500", "gmtOffset": "-07:00",
 *   "activityStatus": { "type": "I", "code": "OT", "description": "Out For Delivery Today" },
 *   "activityLocation": { "city": "Oakland", "stateProvince": "CA", "countryCode": "US" },
 *   "referenceNumbers": [ { "code": "PO", "value": "ORD-2026-000123" } ] }
 * </pre>
 * Status type: M = manifest/label, P = pickup, I = in transit (code OT = out for delivery),
 * D = delivered, X = exception.
 */
@Component
public class UpsPayloadMapper implements CarrierPayloadMapper {

    public static final String CARRIER = "UPS";
    private static final DateTimeFormatter UPS_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    public String carrier() {
        return CARRIER;
    }

    @Override
    public ShipmentEvent map(JsonNode payload, String correlationId, Clock clock) {
        String trackingNumber = CarrierPayloadMapper.requiredText(payload, "trackingNumber");
        String orderId = orderReference(payload);
        String type = CarrierPayloadMapper.requiredText(payload, "activityStatus.type").toUpperCase();
        String code = CarrierPayloadMapper.optionalText(payload, "activityStatus.code");
        ShipmentStatus status = switch (type) {
            case "M" -> ShipmentStatus.LABEL_CREATED;
            case "P" -> ShipmentStatus.IN_TRANSIT;
            case "I" -> "OT".equalsIgnoreCase(code) ? ShipmentStatus.OUT_FOR_DELIVERY : ShipmentStatus.IN_TRANSIT;
            case "D" -> ShipmentStatus.DELIVERED;
            case "X" -> ShipmentStatus.EXCEPTION;
            default -> throw new InvalidPayloadException("unknown UPS activityStatus.type '" + type + "'");
        };
        return CarrierPayloadMapper.event(CARRIER, correlationId, clock, orderId, trackingNumber, status,
                statusTime(payload), location(payload));
    }

    /** The order id travels as a reference number (code PO = purchase order / our orderId). */
    private static String orderReference(JsonNode payload) {
        JsonNode references = payload.get("referenceNumbers");
        if (references != null && references.isArray()) {
            String fallback = null;
            for (JsonNode ref : references) {
                String value = CarrierPayloadMapper.optionalText(ref, "value");
                if (value == null) {
                    continue;
                }
                if ("PO".equalsIgnoreCase(CarrierPayloadMapper.optionalText(ref, "code"))) {
                    return value;
                }
                if (fallback == null && value.startsWith("ORD-")) {
                    fallback = value;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        String direct = CarrierPayloadMapper.optionalText(payload, "orderId");
        if (direct != null) {
            return direct;
        }
        throw new InvalidPayloadException("missing order reference (referenceNumbers[code=PO].value or orderId)");
    }

    private static Instant statusTime(JsonNode payload) {
        String date = CarrierPayloadMapper.optionalText(payload, "localActivityDate");
        String time = CarrierPayloadMapper.optionalText(payload, "localActivityTime");
        if (date == null) {
            return null;
        }
        String offset = CarrierPayloadMapper.optionalText(payload, "gmtOffset");
        try {
            LocalDateTime local = LocalDateTime.parse(date + (time == null ? "000000" : time), UPS_TIMESTAMP);
            ZoneOffset zone = offset == null ? ZoneOffset.UTC : ZoneOffset.of(offset);
            return local.toInstant(zone);
        } catch (DateTimeParseException e) {
            throw new InvalidPayloadException("unparseable UPS localActivityDate/Time '" + date + " " + time + "'");
        }
    }

    private static String location(JsonNode payload) {
        String city = CarrierPayloadMapper.optionalText(payload, "activityLocation.city");
        String state = CarrierPayloadMapper.optionalText(payload, "activityLocation.stateProvince");
        if (city == null && state == null) {
            return null;
        }
        return city == null ? state : (state == null ? city : city + ", " + state);
    }
}
