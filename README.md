# tb-integration-services

Order-to-Delivery integration services for the Tailored Brands OTD reference platform
(see `tb-platform-infra/docs/ARCHITECTURE.md`). Four deployables, one Maven reactor plus one Python
service:

| Service | Stack | Runs on | What it does |
|---|---|---|---|
| `order-intake-api` | Java 21 / Spring Boot 3.5.6, Spring WS, Flyway, JdbcClient | GKE Autopilot (`otd`) | `POST /v1/orders` (REST) and `POST /ws/orders` (SOAP legacy adapter, XSLT → canonical JSON); persists order + **transactional outbox** in one transaction; relay publishes `OrderEvent` to Pub/Sub `orders-v1` with ordering key = `storeId`; consumes `inventory-v1` feedback to update order status; optional dual-write to legacy EMS in migration phase `DUAL_RUN` |
| `inventory-service` | Java 21 / Spring Boot 3.5.6 | GKE Autopilot (`otd`) | Exactly-once consumer of `orders-inventory-service`; reserves stock at the store, then `DC01`, else backorders; publishes `InventoryEvent` to `inventory-v1`; `GET /v1/inventory/{sku}` |
| `shipment-webhook` | Java 21 / Spring Boot 3.5.6 | Cloud Run | `POST /v1/carriers/{UPS\|FEDEX}/events`, HMAC-SHA256 verified, carrier-native JSON → canonical `ShipmentEvent` → `shipments-v1` |
| `notification-service` | Python 3.12 / FastAPI | Cloud Run | Pub/Sub **push** endpoint (`POST /push/shipments`, OIDC-verified) that renders and "sends" customer notifications |
| `common` | Java library | — | Canonical event records, `EventJson`, Pub/Sub publisher/subscriber factories (emulator-aware, ordering on), `CorrelationIdFilter`, `EventAttributes` |

## How it maps to the job posting

* **Enterprise integration patterns on GCP** – transactional outbox, idempotent consumer (inbox),
  ordered Pub/Sub delivery per store, push vs. pull subscriptions, dead-lettering.
* **SOAP/XML and REST/JSON coexistence** – Spring WS endpoint generated from an XSD (JAXB via
  `jaxb2-maven-plugin`), XSLT 1.0 transformation of legacy OMS XML into the canonical model, same
  service path for both protocols.
* **Messaging migration (TIBCO EMS / IBM MQ → Pub/Sub)** – `MIGRATION_PHASE` driven dual-write to a JMS
  queue (`TB.ORDERS.OUT`, Artemis as EMS stand-in) kept behind `@ConditionalOnProperty`.
* **Cloud SQL, GKE Autopilot, Cloud Run, Workload Identity, Secret Manager, Artifact Registry** – see
  `deploy/` and `.github/workflows/deploy-gcp.yml`.
* **Production hygiene** – RFC 7807 problem details, Jakarta validation, Micrometer/Prometheus, structured
  JSON logs with `correlationId`, graceful shutdown, readiness/liveness probes, non-root images,
  resource limits, Testcontainers integration tests.

## Architecture excerpt

```
 Store POS / Ecom ── REST  /v1/orders ─▶ order-intake-api ── Cloud SQL (orders, order_lines, outbox, inbox)
 Legacy stores ───── SOAP  /ws/orders ─▶   XSLT: legacy XML ▶ canonical JSON        │ outbox relay (500ms)
                                                                                     ▼
                                     Pub/Sub orders-v1  (ordering key = storeId, attributes: eventType, source, ...)
                                                 │
                                                 ▼
                                      inventory-service ── Cloud SQL (inventory, inventory_reservations, inbox)
                                                 │
                                                 ▼
                                     Pub/Sub inventory-v1 ──▶ order-intake-api (subscription inventory-order-intake)
                                                                 orders.status = RESERVED | BACKORDERED

 Carrier (UPS/FedEx) ── HMAC ──▶ shipment-webhook (Cloud Run) ──▶ Pub/Sub shipments-v1 ── push + OIDC ──▶ notification-service
```

### Sequence: order → inventory → status

```mermaid
sequenceDiagram
    autonumber
    participant POS as Store POS
    participant API as order-intake-api
    participant DB as Cloud SQL (otd)
    participant RELAY as OutboxRelay (@Scheduled 500ms)
    participant PS as Pub/Sub orders-v1
    participant INV as inventory-service
    participant PSI as Pub/Sub inventory-v1

    POS->>API: POST /v1/orders (X-Correlation-Id)
    API->>DB: BEGIN; nextval(order_seq); INSERT orders, order_lines, outbox(payload, ordering_key=storeId); COMMIT
    API-->>POS: 201 Created {orderId, status: CREATED} + Location
    RELAY->>DB: SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
    RELAY->>PS: publish(OrderEvent, orderingKey=storeId, attributes)
    RELAY->>DB: UPDATE outbox SET published_at = now()
    PS->>INV: ORDER_CREATED (subscription orders-inventory-service, exactly-once, ordered)
    INV->>DB: INSERT inbox(eventId) ON CONFLICT DO NOTHING (duplicate? ack & stop)
    INV->>DB: UPDATE inventory SET reserved = reserved + qty WHERE sku, location=storeId AND on_hand - reserved >= qty (else DC01, else BACKORDERED)
    INV->>DB: INSERT inventory_reservations
    INV->>PSI: publish(InventoryEvent INVENTORY_RESERVED | INVENTORY_BACKORDERED, orderingKey=storeId)
    INV->>PS: ack
    PSI->>API: InventoryEvent (subscription inventory-order-intake)
    API->>DB: INSERT inbox(eventId) ...; UPDATE orders SET status = RESERVED | BACKORDERED
    POS->>API: GET /v1/orders/{orderId}
    API-->>POS: 200 {status: RESERVED, lines: [...]}
```

## Repository layout

```
pom.xml                         parent (spring-boot-starter-parent 3.5.6, libraries-bom 26.60.0, testcontainers-bom 1.21.3)
common/                         canonical events, JSON, Pub/Sub factories, correlation filter
order-intake-api/               REST + SOAP intake, outbox relay, inventory feedback, Flyway migrations, k8s kustomize
inventory-service/              orders-v1 consumer, reservations, inventory API, k8s kustomize
shipment-webhook/               carrier webhooks -> shipments-v1, Cloud Run manifest
notification-service/           FastAPI push consumer, pytest, Cloud Run manifest
docs/API.md                     endpoints with curl examples
docker-compose.yml              Postgres + Pub/Sub emulator (+ topology init) + the four services
.github/workflows/ci.yml        mvn verify (unit + Testcontainers IT), pytest, images to GHCR on main
.github/workflows/deploy-gcp.yml Jib -> Artifact Registry, kubectl apply -k (GKE), gcloud run deploy
```

## Run locally

### Option A – docker compose (everything)

```bash
docker compose up --build
# order-intake-api  http://localhost:8080  (swagger: /swagger-ui.html, WSDL: /ws/orders.wsdl)
# inventory-service http://localhost:8081
# shipment-webhook  http://localhost:8082
# notification-svc  http://localhost:8083/healthz
# Pub/Sub emulator  localhost:8085, Postgres localhost:5432 (otd/otd)
```

`pubsub-init` creates the ARCHITECTURE §4 topics and subscriptions in the emulator (names only: no
schemas, dead-letter policies or filters there) including the push subscription that targets
`notification-service`.

### Option B – `mvn spring-boot:run` against the emulator

```bash
docker compose up -d postgres pubsub-emulator pubsub-init
export PUBSUB_EMULATOR_HOST=localhost:8085 PUBSUB_PROJECT=tb-otd-local
mvn -q -pl common install
mvn -pl order-intake-api  spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl inventory-service spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl shipment-webhook  spring-boot:run -Dspring-boot.run.profiles=local
cd notification-service && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt \
  && .venv/bin/uvicorn app.main:app --port 8083
```

### Build & test

```bash
mvn -q test                 # unit tests only (fast, no Docker)
mvn verify                  # + Testcontainers ITs: postgres:16-alpine + Pub/Sub emulator (needs Docker)
cd notification-service && .venv/bin/pytest
```

The integration tests (`OrderIntakeIT`, `InventoryServiceIT`) are run by failsafe in `verify`, start the
containers once per class, create the Pub/Sub topology in the emulator and assert the real end-to-end
behaviour (ordering key, attributes, status update, reservation rows, release).

## Verify it works

```bash
# 1. create a tailored order (REST)
curl -s -X POST localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: store-0412-txn-889213' -d '{
  "orderType":"TAILORED","storeId":"0412","customerId":"C-77812","promisedDate":"2026-10-10",
  "lines":[{"sku":"MW-SUIT-NAVY-42R","quantity":1,"unitPrice":599.99,"fulfillmentType":"STORE_PICKUP"},
           {"sku":"ALT-HEM-TROUSER","quantity":1,"unitPrice":50.00,"fulfillmentType":"ALTERATION",
            "alteration":{"type":"HEM","measurementInches":31.5,"tailorShopId":"TS-EASTBAY"}}]}'
# -> 201 {"orderId":"ORD-2026-000001","status":"CREATED"}   Location: /v1/orders/ORD-2026-000001

# 2. a second later the outbox relay has published and inventory-service has reserved the suit at 0412
curl -s localhost:8080/v1/orders/ORD-2026-000001 | jq .status        # "RESERVED"
curl -s localhost:8081/v1/inventory/MW-SUIT-NAVY-42R | jq .           # 0412.reserved == 1

# 3. legacy store via SOAP (same OrderService path, source=LEGACY_SOAP_ADAPTER)
curl -s localhost:8080/ws/orders -H 'Content-Type: text/xml' --data-binary @- <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
<SubmitOrderRequest xmlns="http://tailoredbrands.com/legacy/oms/v1"><Order>
 <OrderNbr>MW0412-889214</OrderNbr><OrderType>R</OrderType><StoreNbr>0412</StoreNbr><CustNbr>C-1</CustNbr>
 <OrderDate>2026-10-03T22:14:00Z</OrderDate>
 <Lines><Line><LineNbr>1</LineNbr><SKU>JAB-SHIRT-WHITE-16</SKU><Qty>2</Qty><Price>79.50</Price><FulfillType>P</FulfillType></Line></Lines>
</Order></SubmitOrderRequest></soapenv:Body></soapenv:Envelope>
EOF
# -> <SubmitOrderResponse><OrderNbr>MW0412-889214</OrderNbr><OrderId>ORD-2026-000002</OrderId><Status>ACCEPTED</Status>...

# 4. carrier webhook (HMAC over the raw body with WEBHOOK_SHARED_SECRET=local-dev-secret in compose)
BODY='{"trackingNumber":"1Z999AA10123456784","localActivityDate":"20261004","localActivityTime":"081500","gmtOffset":"-07:00","activityStatus":{"type":"I","code":"OT"},"activityLocation":{"city":"Oakland","stateProvince":"CA"},"referenceNumbers":[{"code":"PO","value":"ORD-2026-000001"}]}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac local-dev-secret | sed 's/^.* //')
curl -s -X POST localhost:8082/v1/carriers/UPS/events -H 'Content-Type: application/json' -H "X-Carrier-Signature: $SIG" -d "$BODY"
# -> 202 {"eventId":"...","orderId":"ORD-2026-000001","carrier":"UPS","status":"OUT_FOR_DELIVERY",...}
docker compose logs notification-service | grep NOTIFY
# -> NOTIFY order=ORD-2026-000001 subject="Your Men's Wearhouse order ORD-2026-000001 is out for delivery" ...

# 5. metrics / health
curl -s localhost:8080/actuator/prometheus | grep otd_outbox
curl -s localhost:8081/actuator/health/readiness
```

More endpoints and the SOAP/legacy helper (`POST /v1/legacy/transform`) are in [docs/API.md](docs/API.md).

## How CI/CD deploys it

* `ci.yml` (every push/PR): `mvn -B -ntp verify` (unit + integration tests with Testcontainers),
  `pytest` for the Python service, test reports uploaded. On `main`: Jib pushes
  `ghcr.io/sdhayanand/tb-{order-intake-api,inventory-service,shipment-webhook}` and Docker pushes
  `ghcr.io/sdhayanand/tb-notification-service`.
* `deploy-gcp.yml` (`workflow_dispatch` + push to `main`, every job guarded by
  `if: vars.GCP_PROJECT_ID != ''`): authenticates with Workload Identity Federation
  (`google-github-actions/auth@v2`), Jib pushes the three Java images and Docker the Python image to
  `us-central1-docker.pkg.dev/$PROJECT/tb-otd/<service>:<sha>`, then
  `kubectl kustomize <module>/deploy/k8s/overlays/gcp | envsubst | kubectl apply -n otd -f -` for the
  two GKE services and `gcloud run deploy` for the two Cloud Run services.
* Kubernetes: kustomize base (deployment, service, hpa, pdb, serviceaccount, configmap) + overlays
  `gcp` (Workload Identity annotation `iam.gke.io/gcp-service-account`, Cloud SQL socket factory via
  `CLOUD_SQL_INSTANCE`, Service `LoadBalancer` for order-intake-api) and `local` (NodePort, emulator).
  DB credentials come from Secret `otd-db` (`url`, `username`, `password`).
* Cloud Run: `deploy/cloudrun/service.yaml` (Knative manifests, min 0 / max 3 instances, dedicated
  service accounts, `WEBHOOK_SHARED_SECRET` from Secret Manager, OIDC audience for push).

## Design notes & deliberate trade-offs

* **Outbox over direct publish** – the order and its event are committed atomically; the relay runs
  every 500ms and marks rows `published_at` in the same transaction as the publish. Per-key ordering is
  preserved within one relay instance (it stops at the first failure). With several replicas, `FOR UPDATE
  SKIP LOCKED` lets them share the work but can interleave keys across replicas: run one replica of the
  relay (or shard by `ordering_key`) when strict cross-replica ordering matters.
* **Inventory publishes before commit** – `inventory-service` publishes the `InventoryEvent` inside the
  transaction; a failed publish rolls the reservation back and the message is nacked. The inventory event
  id is derived deterministically from the order event id so the rare "published, then commit failed" case
  produces a duplicate, not a loss.
* **Inbox keyed by eventId** – consumers dedupe on the business event id (survives replays and bridges),
  not on the Pub/Sub message id.
* **Schema ownership** – `order-intake-api` owns Flyway; `inventory-service` runs with Flyway disabled and
  a `schema` health indicator (part of readiness) that checks V1 is applied. Columns `rental_json` /
  `ship_to_json` were added to `orders` beyond ARCHITECTURE §5.1 so GET returns the complete order.
* **SOAP rejections are business responses** (`Status=REJECTED`) rather than SOAP faults, matching how
  the legacy OMS answered BusinessWorks; unexpected errors still produce faults.
* **Unknown legacy codes** are reported by the stylesheet in a `_errors` array (visible through
  `POST /v1/legacy/transform`) and rejected by the typed path.

## What to say in the interview

1. *"I separated the contract from the transport"* – one canonical `OrderEvent` (JSON validated by a
   Pub/Sub proto schema) is produced by REST, SOAP (via XSLT) and the EMS bridge; consumers never know
   which door the order came in through. `source` and `legacyMessageId` attributes make it traceable.
2. *"Atomicity across DB and broker is solved by the transactional outbox"* – no dual-write race; the
   relay is idempotent and metrics (`otd.outbox.backlog`, `otd.outbox.failed`) tell you when it stalls.
3. *"Ordering is per store, not global"* – ordering key = `storeId`, `setEnableMessageOrdering(true)`,
   `setParallelPullCount(1)` on consumers; I can explain why global ordering would kill throughput and
   what happens on a publish failure (key is paused, `resumePublish`).
4. *"Exactly-once is a contract between the subscription and the consumer"* – the subscription gives
   exactly-once delivery semantics, the inbox table makes the side effect idempotent; both are needed.
5. *"The migration is a dial, not a switch"* – `MIGRATION_PHASE` toggles dual-write to EMS behind
   `@ConditionalOnProperty`; the reconciler compares both sides before each phase exit.
6. *"Legacy XML is a first-class citizen"* – XSD-generated JAXB on the wire, XSLT for mapping, the same
   `OrderService.create` path, round-trip tested against the reverse writer used for dual-write.
7. *"Cloud Run endpoints are locked down by identity, not obscurity"* – Pub/Sub push with OIDC
   (`verify_oauth2_token` + expected service-account email), carrier webhooks with HMAC over the raw body
   and constant-time comparison, secrets from Secret Manager.
8. *"Observability is designed in"* – `X-Correlation-Id` flows from the POS request into MDC, the event
   envelope, Pub/Sub attributes and every downstream log line; Micrometer counters per outcome; RFC 7807
   responses carry the correlation id back to the caller.

## Unverified coordinates (no Maven Central access while authoring)

The build was written against documented APIs but could not be compiled here; the first CI run will
tell. Things to double-check if it fails:

* `com.google.cloud.sql:postgres-socket-factory` is pinned to `1.25.0` in the parent POM (not managed
  by `libraries-bom`); any recent 1.2x release works.
* `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9` and `net.logstash.logback:logstash-logback-encoder:8.1`
  are pinned as given in CONVENTIONS.
* `wsdl4j:wsdl4j` relies on the Spring Boot BOM for its version.
* Testcontainers `PubSubEmulatorContainer` (module `org.testcontainers:gcloud`) is used with image
  `gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators`.
