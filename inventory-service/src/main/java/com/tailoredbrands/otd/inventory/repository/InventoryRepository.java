package com.tailoredbrands.otd.inventory.repository;

import com.tailoredbrands.otd.common.event.InventoryLineStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

/**
 * JDBC access to {@code inventory}, {@code inventory_reservations} and the {@code inbox}
 * (ARCHITECTURE §5.1). Stock is reserved with a single conditional UPDATE, which makes the check
 * and the reservation atomic under concurrent consumers.
 */
@Repository
public class InventoryRepository {

    public record StockLevel(String sku, String locationId, int onHand, int reserved) {
        public int available() {
            return onHand - reserved;
        }
    }

    public record Reservation(String orderId, int lineNumber, String sku, String locationId, int quantity,
                              InventoryLineStatus status) {
    }

    private static final RowMapper<StockLevel> STOCK_ROW = (rs, rowNum) -> new StockLevel(
            rs.getString("sku"), rs.getString("location_id"), rs.getInt("on_hand"), rs.getInt("reserved"));

    private static final RowMapper<Reservation> RESERVATION_ROW = (rs, rowNum) -> new Reservation(
            rs.getString("order_id"), rs.getInt("line_number"), rs.getString("sku"), rs.getString("location_id"),
            rs.getInt("quantity"), InventoryLineStatus.valueOf(rs.getString("status")));

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} if the stock was reserved at that location (on_hand - reserved >= qty). */
    public boolean tryReserve(String sku, String locationId, int quantity) {
        int updated = jdbc.sql("""
                UPDATE inventory
                   SET reserved = reserved + :qty
                 WHERE sku = :sku AND location_id = :locationId AND on_hand - reserved >= :qty
                """)
                .param("qty", quantity)
                .param("sku", sku)
                .param("locationId", locationId)
                .update();
        return updated == 1;
    }

    public void release(String sku, String locationId, int quantity) {
        jdbc.sql("""
                UPDATE inventory
                   SET reserved = GREATEST(reserved - :qty, 0)
                 WHERE sku = :sku AND location_id = :locationId
                """)
                .param("qty", quantity)
                .param("sku", sku)
                .param("locationId", locationId)
                .update();
    }

    public void insertReservation(Reservation reservation) {
        jdbc.sql("""
                INSERT INTO inventory_reservations (order_id, line_number, sku, location_id, quantity, status, created_at)
                VALUES (:orderId, :lineNumber, :sku, :locationId, :quantity, :status, now())
                ON CONFLICT (order_id, line_number) DO UPDATE
                   SET sku = EXCLUDED.sku, location_id = EXCLUDED.location_id,
                       quantity = EXCLUDED.quantity, status = EXCLUDED.status
                """)
                .param("orderId", reservation.orderId())
                .param("lineNumber", reservation.lineNumber())
                .param("sku", reservation.sku())
                .param("locationId", reservation.locationId(), Types.VARCHAR)
                .param("quantity", reservation.quantity())
                .param("status", reservation.status().name())
                .update();
    }

    public List<Reservation> findReservations(String orderId) {
        return jdbc.sql("""
                SELECT order_id, line_number, sku, location_id, quantity, status
                  FROM inventory_reservations
                 WHERE order_id = :orderId
                 ORDER BY line_number
                """)
                .param("orderId", orderId)
                .query(RESERVATION_ROW)
                .list();
    }

    public void updateReservationStatus(String orderId, int lineNumber, InventoryLineStatus status) {
        jdbc.sql("""
                UPDATE inventory_reservations SET status = :status
                 WHERE order_id = :orderId AND line_number = :lineNumber
                """)
                .param("status", status.name())
                .param("orderId", orderId)
                .param("lineNumber", lineNumber)
                .update();
    }

    public List<StockLevel> findBySku(String sku) {
        return jdbc.sql("""
                SELECT sku, location_id, on_hand, reserved
                  FROM inventory
                 WHERE sku = :sku
                 ORDER BY location_id
                """)
                .param("sku", sku)
                .query(STOCK_ROW)
                .list();
    }

    /** Inbox: {@code true} when first seen (recorded now), {@code false} for a duplicate. */
    public boolean markProcessed(String messageId, String consumer) {
        int inserted = jdbc.sql("""
                INSERT INTO inbox (message_id, consumer, processed_at)
                VALUES (:messageId, :consumer, now())
                ON CONFLICT (message_id) DO NOTHING
                """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .update();
        return inserted == 1;
    }
}
