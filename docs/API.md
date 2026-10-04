# API reference

All JSON errors are RFC 7807 `application/problem+json` documents with `type`, `title`, `status`,
`detail`, `correlationId`, `timestamp` and, for validation failures, an `errors` array. Every response
echoes `X-Correlation-Id` (generated when the caller does not send one).

Ports: order-intake-api `8080`, inventory-service `8081`, shipment-webhook `8080` (compose: `8082`),
notification-service `8080` (compose: `8083`). OpenAPI: `/v3/api-docs`, Swagger UI: `/swagger-ui.html`
(Java), `/docs` (Python).

---

## order-intake-api

### `POST /v1/orders`

Creates an order. Validation: `orderType` ∈ `RETAIL|TAILORED|CUSTOM|RENTAL|ECOM`, `storeId` 4 digits,
`lines` non-empty (≤ 200), `quantity` 1..999, `unitPrice` ≥ 0 with 2 decimals, `fulfillmentType` ∈
`STORE_PICKUP|SHIP_TO_HOME|ALTERATION`; `alteration` only (and required) on `ALTERATION` lines;
`shipTo` required when a line is `SHIP_TO_HOME`; `rental` required for `RENTAL`; `totalAmount`
optional but must equal Σ quantity × unitPrice when given. Defaults: `channel=STORE`, `currency=USD`,
`orderedAt=now`, `lineNumber=position`.

```bash
curl -i -X POST localhost:8080/v1/orders \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: store-0412-txn-889213' -d '{
  "orderType": "TAILORED", "channel": "STORE", "storeId": "0412", "customerId": "C-77812",
  "orderedAt": "2026-10-03T22:14:00Z", "promisedDate": "2026-10-10", "currency": "USD",
  "lines": [
    { "lineNumber": 1, "sku": "MW-SUIT-NAVY-42R", "quantity": 1, "unitPrice": 599.99, "fulfillmentType": "STORE_PICKUP" },
    { "lineNumber": 2, "sku": "ALT-HEM-TROUSER", "quantity": 1, "unitPrice": 50.00, "fulfillmentType": "ALTERATION",
      "alteration": { "type": "HEM", "measurementInches": 31.5, "tailorShopId": "TS-EASTBAY" } }
  ]}'
```

```
HTTP/1.1 201 Created
Location: http://localhost:8080/v1/orders/ORD-2026-000001
X-Correlation-Id: store-0412-txn-889213

{"orderId":"ORD-2026-000001","status":"CREATED"}
```

Validation failure:

```json
{ "type": "https://tailoredbrands.com/otd/problems/validation", "title": "Validation failed", "status": 400,
  "detail": "2 validation error(s)", "errors": ["lines: must not be empty", "storeId: must be a 4-digit store number"],
  "correlationId": "…", "timestamp": "…" }
```

### `GET /v1/orders/{orderId}`

```bash
curl -s localhost:8080/v1/orders/ORD-2026-000001 | jq .
```

```json
{ "orderId": "ORD-2026-000001", "status": "RESERVED", "orderType": "TAILORED", "channel": "STORE",
  "storeId": "0412", "customerId": "C-77812", "orderedAt": "2026-10-03T22:14:00Z", "promisedDate": "2026-10-10",
  "currency": "USD", "totalAmount": 649.99,
  "lines": [ { "lineNumber": 1, "sku": "MW-SUIT-NAVY-42R", "quantity": 1, "unitPrice": 599.99, "fulfillmentType": "STORE_PICKUP" },
             { "lineNumber": 2, "sku": "ALT-HEM-TROUSER", "quantity": 1, "unitPrice": 50.00, "fulfillmentType": "ALTERATION",
               "alteration": { "type": "HEM", "measurementInches": 31.5, "tailorShopId": "TS-EASTBAY" } } ],
  "correlationId": "store-0412-txn-889213", "createdAt": "…", "updatedAt": "…" }
```

`status` ∈ `CREATED` → `RESERVED` | `BACKORDERED` (from `inventory-v1`) | `CANCELLED`. Unknown id → `404`.

### `POST /ws/orders` (SOAP 1.1) — WSDL at `GET /ws/orders.wsdl`

Operation `SubmitOrderRequest` (namespace `http://tailoredbrands.com/legacy/oms/v1`, XSD in
`order-intake-api/src/main/resources/xsd/legacy-order.xsd`). Code tables: `OrderType`
`R`=RETAIL `T`=TAILORED `C`=CUSTOM `X`=RENTAL `E`=ECOM; `FulfillType` `P`=STORE_PICKUP `S`=SHIP_TO_HOME
`A`=ALTERATION.

```bash
curl -s localhost:8080/ws/orders -H 'Content-Type: text/xml' -H 'SOAPAction: ""' --data-binary @- <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <SubmitOrderRequest xmlns="http://tailoredbrands.com/legacy/oms/v1">
      <Order>
        <OrderNbr>MW0412-889213</OrderNbr><OrderType>T</OrderType><StoreNbr>0412</StoreNbr><CustNbr>C-77812</CustNbr>
        <OrderDate>2026-10-03T22:14:00Z</OrderDate><PromiseDate>2026-10-10</PromiseDate>
        <Lines>
          <Line><LineNbr>1</LineNbr><SKU>MW-SUIT-NAVY-42R</SKU><Qty>1</Qty><Price>599.99</Price><FulfillType>P</FulfillType></Line>
          <Line><LineNbr>2</LineNbr><SKU>ALT-HEM-TROUSER</SKU><Qty>1</Qty><Price>50.00</Price><FulfillType>A</FulfillType>
            <Alteration><AltType>HEM</AltType><Measurement>31.5</Measurement><TailorShop>TS-EASTBAY</TailorShop></Alteration></Line>
        </Lines>
      </Order>
    </SubmitOrderRequest>
  </soapenv:Body>
</soapenv:Envelope>
EOF
```

Response body (inside the SOAP envelope):

```xml
<SubmitOrderResponse xmlns="http://tailoredbrands.com/legacy/oms/v1">
  <OrderNbr>MW0412-889213</OrderNbr><OrderId>ORD-2026-000002</OrderId><Status>ACCEPTED</Status>
</SubmitOrderResponse>
```

Business rejections answer `Status=REJECTED` with a `Message` (legacy OMS style); malformed envelopes
produce SOAP faults.

### `POST /v1/legacy/transform` (developer helper)

Runs the XSLT only and returns the canonical `Order` JSON (no order is created). Accepts a bare
`<Order>` or a full `<SubmitOrderRequest>`, with or without namespace. Unknown codes appear in `_errors`.

```bash
curl -s -X POST localhost:8080/v1/legacy/transform -H 'Content-Type: application/xml' \
  --data-binary @order-intake-api/src/test/resources/legacy-order-sample.xml | jq .
```

### Operations

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` | probes (readiness includes the DB) |
| `GET /actuator/prometheus` | metrics: `otd_orders_created_total{source,orderType}`, `otd_outbox_published_total`, `otd_outbox_failed_total`, `otd_outbox_backlog`, `otd_outbox_batch_seconds`, `otd_inventory_feedback_total{result,detail}`, `otd_soap_orders_total{outcome}`, `otd_legacy_dualwrite_total{result}` |
| `GET /v3/api-docs`, `GET /swagger-ui.html` | OpenAPI |

Env: `DB_URL DB_USER DB_PASSWORD CLOUD_SQL_INSTANCE PUBSUB_PROJECT ORDERS_TOPIC INVENTORY_SUBSCRIPTION PUBSUB_EMULATOR_HOST MIGRATION_PHASE LEGACY_JMS_URL LEGACY_JMS_USER LEGACY_JMS_PASSWORD LEGACY_JMS_QUEUE LEGACY_OMS_SOAP_URL`.

---

## inventory-service

### `GET /v1/inventory/{sku}`

```bash
curl -s localhost:8081/v1/inventory/MW-SUIT-NAVY-42R | jq .
```

```json
{ "sku": "MW-SUIT-NAVY-42R", "totalAvailable": 86,
  "locations": [ { "locationId": "0412", "onHand": 12, "reserved": 1, "available": 11 },
                 { "locationId": "0875", "onHand": 25, "reserved": 0, "available": 25 },
                 { "locationId": "DC01", "onHand": 50, "reserved": 0, "available": 50 } ] }
```

Unknown SKU → `404` problem detail (`sku` property). Invalid SKU pattern → `400`.

Events consumed from `orders-inventory-service`: `ORDER_CREATED` (reserve: store → `DC01` → backorder;
`ALTERATION` lines are skipped), `ORDER_CANCELLED` (release). Events produced on `inventory-v1`:
`INVENTORY_RESERVED`, `INVENTORY_BACKORDERED`, `INVENTORY_RELEASED` with ordering key = `storeId`.

Operations: `/actuator/health/readiness` includes `db` and `schema` (Flyway V1 applied by
order-intake-api), `/actuator/prometheus` (`otd_inventory_orders_total{outcome}`,
`otd_inventory_messages_total{result}`).

Env: `DB_* PUBSUB_PROJECT ORDERS_SUBSCRIPTION INVENTORY_TOPIC FALLBACK_LOCATION PUBSUB_EMULATOR_HOST`.

---

## shipment-webhook

### `POST /v1/carriers/{carrier}/events` — `carrier` ∈ `UPS`, `FEDEX` (case-insensitive)

Header `X-Carrier-Signature`: lower-case hex HMAC-SHA256 of the **raw body** with
`WEBHOOK_SHARED_SECRET` (optional `sha256=` prefix). Missing/wrong → `401`. Unknown carrier → `404`.
Unmappable payload → `400`. Success → `202` and a `ShipmentEvent` on `shipments-v1` (ordering key =
`orderId`).

UPS shape:

```bash
SECRET=local-dev-secret
BODY='{"trackingNumber":"1Z999AA10123456784","localActivityDate":"20261004","localActivityTime":"081500","gmtOffset":"-07:00",
 "activityStatus":{"type":"I","code":"OT","description":"Out For Delivery Today"},
 "activityLocation":{"city":"Oakland","stateProvince":"CA","countryCode":"US"},
 "referenceNumbers":[{"code":"PO","value":"ORD-2026-000001"}]}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -s -X POST localhost:8082/v1/carriers/UPS/events -H 'Content-Type: application/json' \
  -H "X-Carrier-Signature: $SIG" -d "$BODY" | jq .
```

```json
{ "eventId": "…", "orderId": "ORD-2026-000001", "trackingNumber": "1Z999AA10123456784", "carrier": "UPS",
  "status": "OUT_FOR_DELIVERY", "messageId": "…" }
```

UPS `activityStatus.type`: `M`→LABEL_CREATED, `P`/`I`→IN_TRANSIT (`I`+code `OT`→OUT_FOR_DELIVERY),
`D`→DELIVERED, `X`→EXCEPTION. Order id from `referenceNumbers[code=PO].value` (or `orderId`).

FedEx shape:

```bash
BODY='{"trackingNumber":"794644790138","shipperReference":"ORD-2026-000001",
 "latestStatusDetail":{"code":"DL","description":"Delivered"},"eventDateTime":"2026-10-05T17:02:00-07:00",
 "scanLocation":{"city":"OAKLAND","stateOrProvinceCode":"CA","countryCode":"US"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')
curl -s -X POST localhost:8082/v1/carriers/FEDEX/events -H 'Content-Type: application/json' \
  -H "X-Carrier-Signature: $SIG" -d "$BODY" | jq .
```

FedEx `latestStatusDetail.code`: `OC`→LABEL_CREATED, `PU DP AR IT IX AF`→IN_TRANSIT, `OD`→OUT_FOR_DELIVERY,
`DL`→DELIVERED, `DE SE CA RS`→EXCEPTION. Order id from `shipperReference` (or `customerReference`).

Env: `PUBSUB_PROJECT SHIPMENTS_TOPIC WEBHOOK_SHARED_SECRET PUBSUB_EMULATOR_HOST PORT`. With the `local`
profile and an empty secret, verification is skipped (logged loudly).

---

## notification-service (Python)

### `POST /push/shipments` — Pub/Sub push envelope

```bash
DATA=$(printf '%s' '{"eventId":"e1","eventType":"SHIPMENT_UPDATED","schemaVersion":"1","source":"SHIPMENT_WEBHOOK",
 "correlationId":"c1","orderId":"ORD-2026-000001","trackingNumber":"1Z999AA10123456784","carrier":"UPS",
 "status":"OUT_FOR_DELIVERY","statusTime":"2026-10-04T15:15:00Z","location":"Oakland, CA"}' | base64 -w0)
curl -i -X POST localhost:8083/push/shipments -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <OIDC token from Pub/Sub>' \
  -d "{\"message\":{\"data\":\"$DATA\",\"attributes\":{\"eventType\":\"SHIPMENT_UPDATED\"},\"messageId\":\"1\"},\"subscription\":\"projects/p/subscriptions/shipments-notification\"}"
# -> HTTP/1.1 204 No Content ; log: NOTIFY order=ORD-2026-000001 subject="Your Men's Wearhouse order ORD-2026-000001 is out for delivery" ...
```

* With `PUSH_AUDIENCE` set, the bearer token is verified with `google.oauth2.id_token.verify_oauth2_token`
  (signature, expiry, issuer, audience) and, when `PUSH_SERVICE_ACCOUNT` is set, the `email` claim must
  match → otherwise `401` problem detail. Unset `PUSH_AUDIENCE` (local only) skips verification.
* Invalid envelope / event → `400` (Pub/Sub retries, then dead-letters after 5 attempts).
* Duplicates (same `eventId`) are acknowledged with `204` without re-sending.
* `NOTIFY_MODE`: `log` (default), `sendgrid` (stub, needs `SENDGRID_API_KEY`), `twilio` (stub, needs
  `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN`).

### `GET /healthz`

```bash
curl -s localhost:8083/healthz     # {"status":"UP","notifyMode":"log","pushAuth":"disabled"}
```
