package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.EventSource;
import com.tailoredbrands.otd.common.event.OrderEvent;
import com.tailoredbrands.otd.common.web.CorrelationIdFilter;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Clock;

@RestController
@RequestMapping(path = "/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Orders", description = "Order intake (store POS / e-commerce)")
public class OrderController {

    private final OrderService orderService;
    private final Clock clock;

    public OrderController(OrderService orderService, Clock clock) {
        this.orderService = orderService;
        this.clock = clock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create an order",
            description = "Persists the order and an outbox row in one transaction; the ORDER_CREATED event is "
                    + "relayed to Pub/Sub orders-v1 (ordering key = storeId) within ~500ms.")
    @ApiResponse(responseCode = "201", description = "Created; Location header points at the order")
    @ApiResponse(responseCode = "400", description = "Validation failed (RFC 7807 problem with `errors`)")
    public ResponseEntity<OrderCreatedResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        String correlationId = CorrelationIdFilter.current();
        OrderEvent event = orderService.create(request.toOrder(clock), EventSource.ORDER_INTAKE_API, correlationId);
        String orderId = event.order().orderId();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(orderId).toUri();
        return ResponseEntity.created(location).body(new OrderCreatedResponse(orderId, OrderStatus.CREATED));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order with its lines and current status")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "Unknown order id")
    public OrderResponse get(@PathVariable String orderId) {
        return OrderResponse.from(orderService.get(orderId));
    }
}
