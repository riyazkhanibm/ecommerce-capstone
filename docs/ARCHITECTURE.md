# Architecture

## Services

| Service | Port | Responsibility | Datastore |
|---|---|---|---|
| product-service | 8081 | Product catalog CRUD, stock levels | productdb (Postgres) |
| order-service | 8082 | Order placement, calls product-service to check/decrement stock | orderdb (Postgres) |
| user-service | 8083 | Registration, login, JWT issuance | userdb (Postgres) |
| frontend | 8080 | Static HTML/JS UI calling the three APIs directly | — |

Each service owns its own database — no service reaches into another
service's tables directly. Cross-service reads/writes go through HTTP APIs
only. This is what makes them independently deployable: you can redeploy
product-service without touching order-service's schema or code.

## The riskiest piece: placing an order

Placing an order needs to (a) confirm the product exists and has enough
stock, and (b) decrement that stock, and (c) persist the order — across
*two* services and *two* databases. That's the classic distributed-write
problem, and it's the part of this design most worth thinking hard about.

### Option A — Synchronous call, chosen for this capstone

`order-service` calls `GET /api/products/{id}` on `product-service` to
validate stock, saves the order, then calls
`PATCH /api/products/{id}/stock` to decrement it.

**Pros**
- Simple to build, reason about, and grade — the whole flow is readable in
  one method (`OrderController.createOrder`).
- Immediate consistency from the caller's point of view: if the order
  request returns 201, stock has already been decremented.
- No extra infrastructure (no message broker to install, configure, or
  explain in a capstone demo).

**Cons**
- If `order-service` saves the order but then fails to reach
  `product-service` for the stock decrement (network blip, pod restart),
  you get an order with stock never decremented — an inconsistent state.
  This implementation is a partial mitigation (decrement happens *after*
  save, and only for items that were already validated) but it is not a
  distributed transaction and can still fail between the save and the
  decrement.
- `order-service` is now coupled to `product-service`'s uptime: if
  product-service is down, no orders can be placed at all, even for
  products whose stock hasn't changed.
- Doesn't scale well to more services (e.g., a future payment-service or
  shipping-service) — every new dependency is another synchronous hop and
  another way for the whole chain to fail.

### Option B — Event-driven / saga (not built here, worth knowing)

`order-service` saves the order in a `PENDING` state and publishes an
`OrderCreated` event (e.g., to Kafka or an OpenShift-hosted AMQ instance).
`product-service` subscribes, attempts the stock decrement, and publishes
either `StockReserved` or `StockRejected`. `order-service` subscribes to
that and flips the order to `CONFIRMED` or `CANCELLED`.

**Pros**
- Services are decoupled in time: product-service being briefly down just
  delays processing, it doesn't fail the request.
- Scales cleanly to more participants (add a payment-service that also
  listens for `OrderCreated`).
- Natural place to add retries, dead-letter queues, and audit trails.

**Cons**
- Real infrastructure to run and operate (a broker), which is a lot of
  extra surface area for a capstone.
- Eventual consistency: the customer's order starts as `PENDING` and the
  UI has to poll or subscribe for the final status instead of getting an
  immediate yes/no.
- Debugging is harder — a failure is now spread across two services' logs
  and a broker's message history instead of one stack trace.

### Why Option A for this project

For a capstone whose primary grading surface is "does the pipeline build,
push, and deploy three services correctly," the synchronous call keeps the
business logic small enough to read in one sitting and to demo live. The
code is written so the swap to Option B later is localized: the
`ProductServiceClient` interface is the only thing that would change from
an HTTP client to an event publisher, and `OrderController` would change
from "decrement then return 201" to "publish event, return 202 Accepted."

## Data flow for a single order

```
Browser -> POST /api/orders (order-service)
              |
              +--> GET /api/products/{id} (product-service)  [check stock]
              |
              +--> save Order + OrderItems (orderdb)
              |
              +--> PATCH /api/products/{id}/stock (product-service) [decrement]
              |
              <-- 201 Created, order JSON
```

## Why database-per-service instead of one shared database

A shared database is the fastest way to accidentally couple two
"independent" services — one team changes a column, the other team's
service breaks at runtime with no compile-time warning. Giving each
service its own schema (here, its own Postgres database) forces all
cross-service communication through versioned HTTP APIs, which is the
whole point of doing microservices in the first place.
