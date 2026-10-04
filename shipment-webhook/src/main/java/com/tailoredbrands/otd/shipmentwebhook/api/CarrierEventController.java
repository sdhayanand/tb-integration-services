package com.tailoredbrands.otd.shipmentwebhook.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventAttributes;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import com.tailoredbrands.otd.shipmentwebhook.carrier.CarrierPayloadMapper;
import com.tailoredbrands.otd.shipmentwebhook.carrier.CarrierPayloadMappers;
import com.tailoredbrands.otd.shipmentwebhook.config.WebhookProperties;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidPayloadException;
import com.tailoredbrands.otd.shipmentwebhook.error.InvalidSignatureException;
import com.tailoredbrands.otd.shipmentwebhook.security.HmacSignatureVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;

/**
 * {@code POST /v1/carriers/{carrier}/events}: raw body is kept as bytes so the HMAC is computed over
 * exactly what the carrier signed; then parse, map, publish (ordering key = orderId so all events of
 * one order stay in order), 202.
 */
@RestController
@Tag(name = "Carrier webhooks")
public class CarrierEventController {

    private static final Logger log = LoggerFactory.getLogger(CarrierEventController.class);
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final CarrierPayloadMappers mappers;
    private final HmacSignatureVerifier verifier;
    private final EventPublisher publisher;
    private final String topic;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CarrierEventController(CarrierPayloadMappers mappers, HmacSignatureVerifier verifier, EventPublisher publisher,
                                  WebhookProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.mappers = mappers;
        this.verifier = verifier;
        this.publisher = publisher;
        this.topic = properties.pubsub().shipmentsTopic();
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @PostMapping(path = "/v1/carriers/{carrier}/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive a carrier tracking event",
            description = "Carrier-native JSON (UPS or FEDEX shape), HMAC-SHA256 signed in X-Carrier-Signature over the raw body.")
    @ApiResponse(responseCode = "202", description = "Accepted and published to shipments-v1")
    @ApiResponse(responseCode = "400", description = "Payload cannot be mapped")
    @ApiResponse(responseCode = "401", description = "Signature missing or invalid")
    @ApiResponse(responseCode = "404", description = "Unknown carrier")
    public ResponseEntity<WebhookAck> receive(
            @Parameter(description = "UPS or FEDEX") @PathVariable String carrier,
            @RequestHeader(value = HmacSignatureVerifier.HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        CarrierPayloadMapper mapper = mappers.forCarrier(carrier);
        if (body == null || body.length == 0) {
            throw new InvalidPayloadException("empty body");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new InvalidPayloadException("body larger than " + MAX_BODY_BYTES + " bytes");
        }
        if (!verifier.verify(body, signature)) {
            count(mapper.carrier(), "unauthorized");
            throw new InvalidSignatureException(signature == null || signature.isBlank()
                    ? "missing " + HmacSignatureVerifier.HEADER + " header"
                    : HmacSignatureVerifier.HEADER + " does not match the request body");
        }

        JsonNode payload;
        try {
            payload = EventJson.MAPPER.readTree(body);
        } catch (IOException e) {
            count(mapper.carrier(), "bad-json");
            throw new InvalidPayloadException("body is not valid JSON: " + e.getMessage());
        }
        if (payload == null || !payload.isObject()) {
            count(mapper.carrier(), "bad-json");
            throw new InvalidPayloadException("body must be a JSON object");
        }

        ShipmentEvent event = mapper.map(payload, CorrelationIdFilter.current(), clock);
        MDC.put("orderId", event.orderId());
        MDC.put("eventId", event.eventId());
        try {
            String messageId = publisher.publish(topic, event.orderId(), EventJson.toJson(event),
                    EventAttributes.forShipmentEvent(event));
            count(mapper.carrier(), "published");
            log.info("Shipment event published: carrier={} orderId={} tracking={} status={} messageId={}",
                    event.carrier(), event.orderId(), event.trackingNumber(), event.status(), messageId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new WebhookAck(event.eventId(), event.orderId(),
                    event.trackingNumber(), event.carrier(), event.status(), messageId));
        } finally {
            MDC.remove("orderId");
            MDC.remove("eventId");
        }
    }

    private void count(String carrier, String result) {
        Counter.builder("otd.shipments.webhook")
                .description("Carrier webhook events received")
                .tag("carrier", carrier)
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
