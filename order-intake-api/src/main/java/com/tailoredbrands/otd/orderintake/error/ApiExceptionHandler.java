package com.tailoredbrands.otd.orderintake.error;

import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * RFC 7807 {@link ProblemDetail} responses for every error. Spring's standard MVC exceptions
 * (bad JSON, unsupported media type, missing params, ...) are handled by the superclass and also
 * rendered as problem details. Each problem carries {@code correlationId} and {@code timestamp}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://tailoredbrands.com/otd/problems/";

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail orderNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage(), "order-not-found");
        problem.setProperty("orderId", ex.orderId());
        return problem;
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ProblemDetail invalidOrder(InvalidOrderException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid order", ex.getMessage(), "invalid-order");
        problem.setProperty("errors", ex.violations());
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail dataAccess(DataAccessException ex) {
        log.error("Database error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable",
                "The order store is temporarily unavailable; retry with the same X-Correlation-Id.", "database-unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled error: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error", "internal");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fe
                        ? fe.getField() + ": " + fe.getDefaultMessage()
                        : error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                errors.size() + " validation error(s)", "validation");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problem);
    }

    @Override
    @Nullable
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            decorate(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + type));
        decorate(problem);
        return problem;
    }

    private static void decorate(ProblemDetail problem) {
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        problem.setProperty("timestamp", Instant.now().toString());
    }
}
