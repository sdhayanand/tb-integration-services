package com.tailoredbrands.otd.orderintake.repository;

import com.tailoredbrands.otd.common.event.Address;
import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Channel;
import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.OrderType;
import com.tailoredbrands.otd.common.event.Rental;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.orderintake.domain.OrderStatus;
import com.tailoredbrands.otd.orderintake.domain.StoredOrder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC access to {@code orders} / {@code order_lines} via {@link JdbcClient} (no JPA).
 * Participates in the caller's Spring-managed transaction.
 */
@Repository
public class OrderRepository {

    private static final String SELECT_ORDER = """
            SELECT order_id, order_type, channel, store_id, customer_id, ordered_at, promised_date, currency,
                   total_amount, status, correlation_id, rental_json::text AS rental_json,
                   ship_to_json::text AS ship_to_json, created_at, updated_at
              FROM orders
             WHERE order_id = :orderId
            """;

    private static final String SELECT_LINES = """
            SELECT line_number, sku, quantity, unit_price, fulfillment_type, alteration_json::text AS alteration_json
              FROM order_lines
             WHERE order_id = :orderId
             ORDER BY line_number
            """;

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Order order, OrderStatus status, String correlationId, Instant now) {
        OffsetDateTime nowTs = now.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO orders (order_id, order_type, channel, store_id, customer_id, ordered_at, promised_date,
                                    currency, total_amount, status, correlation_id, rental_json, ship_to_json,
                                    created_at, updated_at)
                VALUES (:orderId, :orderType, :channel, :storeId, :customerId, :orderedAt, :promisedDate,
                        :currency, :totalAmount, :status, :correlationId, CAST(:rental AS jsonb), CAST(:shipTo AS jsonb),
                        :createdAt, :updatedAt)
                """)
                .param("orderId", order.orderId())
                .param("orderType", order.orderType().name())
                .param("channel", order.channel().name())
                .param("storeId", order.storeId())
                .param("customerId", order.customerId(), Types.VARCHAR)
                .param("orderedAt", order.orderedAt().atOffset(ZoneOffset.UTC))
                .param("promisedDate", order.promisedDate(), Types.DATE)
                .param("currency", order.currency())
                .param("totalAmount", order.totalAmount())
                .param("status", status.name())
                .param("correlationId", correlationId, Types.VARCHAR)
                .param("rental", order.rental() == null ? null : EventJson.toJson(order.rental()), Types.VARCHAR)
                .param("shipTo", order.shipTo() == null ? null : EventJson.toJson(order.shipTo()), Types.VARCHAR)
                .param("createdAt", nowTs)
                .param("updatedAt", nowTs)
                .update();

        for (OrderLine line : order.lines()) {
            jdbc.sql("""
                    INSERT INTO order_lines (order_id, line_number, sku, quantity, unit_price, fulfillment_type, alteration_json)
                    VALUES (:orderId, :lineNumber, :sku, :quantity, :unitPrice, :fulfillmentType, CAST(:alteration AS jsonb))
                    """)
                    .param("orderId", order.orderId())
                    .param("lineNumber", line.lineNumber())
                    .param("sku", line.sku())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("fulfillmentType", line.fulfillmentType().name())
                    .param("alteration", line.alteration() == null ? null : EventJson.toJson(line.alteration()), Types.VARCHAR)
                    .update();
        }
    }

    public Optional<StoredOrder> find(String orderId) {
        Optional<OrderRow> header = jdbc.sql(SELECT_ORDER).param("orderId", orderId).query(ORDER_ROW).optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<OrderLine> lines = jdbc.sql(SELECT_LINES).param("orderId", orderId).query(LINE_ROW).list();
        OrderRow row = header.get();
        Order order = new Order(row.orderId, row.orderType, row.channel, row.storeId, row.customerId, row.orderedAt,
                row.promisedDate, row.currency, row.totalAmount, lines, row.rental, row.shipTo);
        return Optional.of(new StoredOrder(order, row.status, row.correlationId, row.createdAt, row.updatedAt));
    }

    public Optional<OrderStatus> findStatus(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .map(OrderStatus::valueOf);
    }

    /** Updates the status; returns the number of rows changed (0 when the order does not exist). */
    public int updateStatus(String orderId, OrderStatus status, Instant now) {
        return jdbc.sql("UPDATE orders SET status = :status, updated_at = :now WHERE order_id = :orderId")
                .param("status", status.name())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();
    }

    // ---- row mapping -------------------------------------------------------------------------

    private record OrderRow(String orderId, OrderType orderType, Channel channel, String storeId, String customerId,
                            Instant orderedAt, LocalDate promisedDate, String currency,
                            java.math.BigDecimal totalAmount, OrderStatus status, String correlationId,
                            Rental rental, Address shipTo, Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<OrderRow> ORDER_ROW = (rs, rowNum) -> new OrderRow(
            rs.getString("order_id"),
            OrderType.valueOf(rs.getString("order_type")),
            Channel.valueOf(rs.getString("channel")),
            rs.getString("store_id"),
            rs.getString("customer_id"),
            instant(rs, "ordered_at"),
            rs.getObject("promised_date", LocalDate.class),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getString("correlation_id"),
            json(rs.getString("rental_json"), Rental.class),
            json(rs.getString("ship_to_json"), Address.class),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<OrderLine> LINE_ROW = (rs, rowNum) -> new OrderLine(
            rs.getInt("line_number"),
            rs.getString("sku"),
            rs.getInt("quantity"),
            rs.getBigDecimal("unit_price"),
            FulfillmentType.valueOf(rs.getString("fulfillment_type")),
            json(rs.getString("alteration_json"), Alteration.class));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static <T> T json(String value, Class<T> type) {
        return value == null || value.isBlank() ? null : EventJson.fromJson(value, type);
    }
}
