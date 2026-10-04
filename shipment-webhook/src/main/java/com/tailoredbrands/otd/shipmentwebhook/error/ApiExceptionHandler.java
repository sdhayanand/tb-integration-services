package com.tailoredbrands.otd.shipmentwebhook.error;

import com.tailoredbrands.otd.common.pubsub.PublishException;
import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/** RFC 7807 problem details. 401 for bad signatures, 404 unknown carrier, 400 bad payload, 503 publish failure. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://tailoredbrands.com/otd/problems/";

    @ExceptionHandler(InvalidSignatureException.class)
    public ProblemDetail invalidSignature(InvalidSignatureException ex) {
        log.warn("Rejected carrier event: {}", ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Invalid signature", ex.getMessage(), "invalid-signature");
    }

    @ExceptionHandler(UnknownCarrierException.class)
    public ProblemDetail unknownCarrier(UnknownCarrierException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Unknown carrier", ex.getMessage(), "unknown-carrier");
        problem.setProperty("carrier", ex.carrier());
        return problem;
    }

    @ExceptionHandler(InvalidPayloadException.class)
    public ProblemDetail invalidPayload(InvalidPayloadException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid carrier payload", ex.getMessage(), "invalid-payload");
    }

    @ExceptionHandler(PublishException.class)
    public ProblemDetail publishFailed(PublishException ex) {
        log.error("Publish to shipments-v1 failed: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Event bus unavailable",
                "Could not publish the shipment event; the carrier should retry.", "publish-failed");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error", "internal");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
