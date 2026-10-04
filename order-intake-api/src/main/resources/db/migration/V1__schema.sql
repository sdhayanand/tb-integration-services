-- V1: OTD schema (ARCHITECTURE §5.1). Owned by order-intake-api; inventory-service shares the DB.
-- Extensions over the doc: orders.rental_json / orders.ship_to_json keep the optional canonical
-- sub-objects so GET /v1/orders/{id} can return the full order.

CREATE SEQUENCE IF NOT EXISTS order_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE orders (
    order_id        VARCHAR(32)     NOT NULL,
    order_type      VARCHAR(16)     NOT NULL,
    channel         VARCHAR(16)     NOT NULL,
    store_id        VARCHAR(8)      NOT NULL,
    customer_id     VARCHAR(64),
    ordered_at      TIMESTAMPTZ     NOT NULL,
    promised_date   DATE,
    currency        CHAR(3)         NOT NULL DEFAULT 'USD',
    total_amount    NUMERIC(12,2)   NOT NULL,
    status          VARCHAR(16)     NOT NULL,
    correlation_id  VARCHAR(128),
    rental_json     JSONB,
    ship_to_json    JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT orders_pk PRIMARY KEY (order_id),
    CONSTRAINT orders_type_chk   CHECK (order_type IN ('RETAIL','TAILORED','CUSTOM','RENTAL','ECOM')),
    CONSTRAINT orders_status_chk CHECK (status IN ('CREATED','RESERVED','BACKORDERED','CANCELLED')),
    CONSTRAINT orders_store_chk  CHECK (store_id ~ '^[0-9]{4}$')
);
CREATE INDEX orders_store_id_idx ON orders (store_id, ordered_at DESC);
CREATE INDEX orders_status_idx   ON orders (status);

CREATE TABLE order_lines (
    order_id         VARCHAR(32)    NOT NULL,
    line_number      INTEGER        NOT NULL,
    sku              VARCHAR(64)    NOT NULL,
    quantity         INTEGER        NOT NULL,
    unit_price       NUMERIC(12,2)  NOT NULL,
    fulfillment_type VARCHAR(16)    NOT NULL,
    alteration_json  JSONB,
    CONSTRAINT order_lines_pk PRIMARY KEY (order_id, line_number),
    CONSTRAINT order_lines_order_fk FOREIGN KEY (order_id) REFERENCES orders (order_id) ON DELETE CASCADE,
    CONSTRAINT order_lines_qty_chk CHECK (quantity > 0),
    CONSTRAINT order_lines_fulfill_chk CHECK (fulfillment_type IN ('STORE_PICKUP','SHIP_TO_HOME','ALTERATION'))
);
CREATE INDEX order_lines_sku_idx ON order_lines (sku);

-- transactional outbox
CREATE TABLE outbox (
    id            BIGSERIAL     NOT NULL,
    aggregate_id  VARCHAR(64)   NOT NULL,
    topic         VARCHAR(128)  NOT NULL,
    ordering_key  VARCHAR(64),
    payload       JSONB         NOT NULL,
    attributes    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    CONSTRAINT outbox_pk PRIMARY KEY (id)
);
-- partial index: the relay only ever scans unpublished rows in id order
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- idempotent consumer / inbox
CREATE TABLE inbox (
    message_id    VARCHAR(128)  NOT NULL,
    consumer      VARCHAR(64)   NOT NULL,
    processed_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT inbox_pk PRIMARY KEY (message_id)
);
CREATE INDEX inbox_processed_at_idx ON inbox (processed_at);

-- inventory (shared with inventory-service)
CREATE TABLE inventory (
    sku          VARCHAR(64)  NOT NULL,
    location_id  VARCHAR(8)   NOT NULL,
    on_hand      INTEGER      NOT NULL DEFAULT 0,
    reserved     INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT inventory_pk PRIMARY KEY (sku, location_id),
    CONSTRAINT inventory_on_hand_chk  CHECK (on_hand >= 0),
    CONSTRAINT inventory_reserved_chk CHECK (reserved >= 0 AND reserved <= on_hand)
);

CREATE TABLE inventory_reservations (
    order_id     VARCHAR(32)  NOT NULL,
    line_number  INTEGER      NOT NULL,
    sku          VARCHAR(64)  NOT NULL,
    location_id  VARCHAR(8),                 -- NULL when BACKORDERED
    quantity     INTEGER      NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT inventory_reservations_pk PRIMARY KEY (order_id, line_number),
    CONSTRAINT inventory_reservations_status_chk CHECK (status IN ('RESERVED','BACKORDERED','RELEASED'))
);
CREATE INDEX inventory_reservations_sku_idx ON inventory_reservations (sku, location_id);
