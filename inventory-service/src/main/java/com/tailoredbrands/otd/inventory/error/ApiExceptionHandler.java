package com.tailoredbrands.otd.inventory.error;

import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/** RFC 7807 problem details for the inventory API. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://tailoredbrands.com/otd/problems/";

    @ExceptionHandler(SkuNotFoundException.class)
    public ProblemDetail skuNotFound(SkuNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "SKU not found", ex.getMessage(), "sku-not-found");
        problem.setProperty("sku", ex.sku());
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail dataAccess(DataAccessException ex) {
        log.error("Database error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable", "Inventory store temporarily unavailable",
                "database-unavailable");
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
