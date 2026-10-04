package com.tailoredbrands.otd.shipmentwebhook.api;

import com.tailoredbrands.otd.common.event.ShipmentEvent;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.common.pubsub.EventPublisher;
import com.tailoredbrands.otd.shipmentwebhook.security.HmacSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "otd.webhook.shared-secret=test-secret",
        "otd.pubsub.shipments-topic=shipments-v1"
})
@AutoConfigureMockMvc
class CarrierEventControllerTest {

    private static final String UPS_BODY = """
            {"trackingNumber":"1Z999AA10123456784","localActivityDate":"20261003","localActivityTime":"081500",
             "activityStatus":{"type":"I","code":"OT"},"activityLocation":{"city":"Oakland","stateProvince":"CA"},
             "referenceNumbers":[{"code":"PO","value":"ORD-2026-000123"}]}""";

    private static final String FEDEX_BODY = """
            {"trackingNumber":"794644790138","shipperReference":"ORD-2026-000124",
             "latestStatusDetail":{"code":"DL"},"eventDateTime":"2026-10-04T17:02:00Z",
             "scanLocation":{"city":"SAN JOSE","stateOrProvinceCode":"CA"}}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    HmacSignatureVerifier verifier;

    @MockitoBean
    EventPublisher publisher;

    @BeforeEach
    void stubPublisher() {
        when(publisher.publish(anyString(), anyString(), anyString(), anyMap())).thenReturn("msg-42");
    }

    private String sign(String body) {
        return verifier.signHex(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upsEventIsVerifiedMappedAndPublishedWith202() throws Exception {
        mvc.perform(post("/v1/carriers/UPS/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", sign(UPS_BODY))
                        .header("X-Correlation-Id", "corr-ups-1")
                        .content(UPS_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-Id", "corr-ups-1"))
                .andExpect(jsonPath("$.orderId").value("ORD-2026-000123"))
                .andExpect(jsonPath("$.carrier").value("UPS"))
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.messageId").value("msg-42"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq("shipments-v1"), eq("ORD-2026-000123"), payload.capture(), attributes.capture());
        ShipmentEvent event = EventJson.fromJson(payload.getValue(), ShipmentEvent.class);
        assertThat(event.trackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(event.correlationId()).isEqualTo("corr-ups-1");
        assertThat(attributes.getValue())
                .containsEntry("eventType", "SHIPMENT_UPDATED")
                .containsEntry("source", "SHIPMENT_WEBHOOK")
                .containsEntry("carrier", "UPS")
                .containsEntry("orderId", "ORD-2026-000123")
                .containsEntry("correlationId", "corr-ups-1");
    }

    @Test
    void fedexEventIsAcceptedCaseInsensitively() throws Exception {
        mvc.perform(post("/v1/carriers/fedex/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", "sha256=" + sign(FEDEX_BODY))
                        .content(FEDEX_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.carrier").value("FEDEX"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void badSignatureIs401ProblemDetail() throws Exception {
        mvc.perform(post("/v1/carriers/UPS/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", sign(UPS_BODY + " "))
                        .content(UPS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid signature"));

        mvc.perform(post("/v1/carriers/UPS/events").contentType(MediaType.APPLICATION_JSON).content(UPS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("missing X-Carrier-Signature header"));

        verify(publisher, never()).publish(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unknownCarrierIs404() throws Exception {
        mvc.perform(post("/v1/carriers/DHL/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", sign(UPS_BODY))
                        .content(UPS_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.carrier").value("DHL"));
    }

    @Test
    void unmappablePayloadIs400() throws Exception {
        String body = "{\"trackingNumber\":\"1Z1\",\"activityStatus\":{\"type\":\"D\"}}";
        mvc.perform(post("/v1/carriers/UPS/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", sign(body))
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid carrier payload"));

        String notJson = "<xml/>";
        mvc.perform(post("/v1/carriers/UPS/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Carrier-Signature", sign(notJson))
                        .content(notJson))
                .andExpect(status().isBadRequest());
    }
}
