package com.tailoredbrands.otd.orderintake.soap;

import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import com.tailoredbrands.otd.orderintake.error.InvalidOrderException;
import com.tailoredbrands.otd.orderintake.legacy.xml.SubmitOrderRequest;
import com.tailoredbrands.otd.orderintake.legacy.xml.SubmitOrderResponse;
import com.tailoredbrands.otd.orderintake.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import java.io.StringWriter;

/**
 * SOAP adapter for legacy stores still speaking the OMS contract: {@code SubmitOrderRequest} is
 * marshalled back to XML, transformed by XSLT into the canonical order, and pushed through the very
 * same {@link OrderService#create} path as REST, with {@code source = LEGACY_SOAP_ADAPTER}.
 *
 * <p>Business rejections are answered OMS-style ({@code Status=REJECTED} + message) rather than as a
 * SOAP fault, because that is what the legacy BW process expects; unexpected errors become faults.
 */
@Endpoint
public class LegacyOrderEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LegacyOrderEndpoint.class);
    static final String STATUS_ACCEPTED = "ACCEPTED";
    static final String STATUS_REJECTED = "REJECTED";

    private final LegacyOrderTransformer transformer;
    private final OrderService orderService;
    private final JAXBContext jaxbContext;
    private final Counter accepted;
    private final Counter rejected;

    public LegacyOrderEndpoint(LegacyOrderTransformer transformer, OrderService orderService, MeterRegistry meterRegistry) {
        this.transformer = transformer;
        this.orderService = orderService;
        try {
            this.jaxbContext = JAXBContext.newInstance(SubmitOrderRequest.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot initialise JAXB context for legacy order XML", e);
        }
        this.accepted = Counter.builder("otd.soap.orders").tag("outcome", "accepted").register(meterRegistry);
        this.rejected = Counter.builder("otd.soap.orders").tag("outcome", "rejected").register(meterRegistry);
    }

    @PayloadRoot(namespace = WebServiceConfig.NAMESPACE, localPart = "SubmitOrderRequest")
    @ResponsePayload
    public SubmitOrderResponse submitOrder(@RequestPayload SubmitOrderRequest request) {
        String legacyOrderNbr = request.getOrder() == null ? null : request.getOrder().getOrderNbr();
        String correlationId = CorrelationIdFilter.current();

        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setOrderNbr(legacyOrderNbr == null ? "" : legacyOrderNbr);
        try {
            String legacyXml = marshal(request);
            Order draft = transformer.toCanonicalOrder(legacyXml);
            OrderEvent event = orderService.create(draft, EventSource.LEGACY_SOAP_ADAPTER, correlationId);
            response.setOrderId(event.order().orderId());
            response.setStatus(STATUS_ACCEPTED);
            accepted.increment();
            log.info("SOAP order accepted: legacyOrderNbr={} orderId={}", legacyOrderNbr, event.order().orderId());
        } catch (InvalidOrderException e) {
            response.setStatus(STATUS_REJECTED);
            response.setMessage(e.getMessage());
            rejected.increment();
            log.warn("SOAP order rejected: legacyOrderNbr={} reason={}", legacyOrderNbr, e.getMessage());
        }
        return response;
    }

    private String marshal(SubmitOrderRequest request) {
        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            StringWriter out = new StringWriter(2048);
            marshaller.marshal(request, out);
            return out.toString();
        } catch (JAXBException e) {
            throw new InvalidOrderException("cannot marshal SubmitOrderRequest: " + e.getMessage());
        }
    }
}
