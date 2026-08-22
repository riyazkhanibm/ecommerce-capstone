# Verification Guide

Matches the step numbers in `STEP-BY-STEP.md`. Each section says exactly
what to run and what "correct" looks like.

## §1 — Local Maven build

```bash
cd services/product-service && mvn -B clean package -DskipTests
```
**Expect:** `BUILD SUCCESS` and `target/product-service.jar` exists.
Repeat for `order-service` and `user-service` (jar names match).

## §2 — GitHub

Open `https://github.com/<your-username>/ecommerce-capstone` in a
browser. **Expect:** `services/`, `openshift/`, `tekton/`, `frontend/`,
`docs/`, `docker-compose.yml` all visible, with your commit message shown.

## §3 — Local Docker Compose (do this before OpenShift)

```bash
docker compose up -d --build
docker compose ps
```
**Expect:** `product-service`, `order-service`, `user-service`,
`frontend`, `postgres` all `Up`.

Product listing:
```bash
curl -s http://localhost:8081/api/products | python3 -m json.tool
```
**Expect:** a JSON array with 3 seeded products (Wireless Mouse,
Mechanical Keyboard, Cotton T-Shirt).

Register a user:
```bash
curl -s -X POST http://localhost:8083/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Jane Doe","email":"jane@example.com","password":"password123"}' \
  | python3 -m json.tool
```
**Expect:** HTTP 201 with a JSON body containing a `token` field.

Place an order (use a real product id from the products call above,
e.g. `1`):
```bash
curl -s -X POST http://localhost:8082/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"Jane Doe","items":[{"productId":1,"quantity":2}]}' \
  | python3 -m json.tool
```
**Expect:** HTTP 201, `"status": "CONFIRMED"`, a non-zero `totalAmount`.

Confirm stock decremented:
```bash
curl -s http://localhost:8081/api/products/1 | python3 -m json.tool
```
**Expect:** `stockQuantity` is 2 lower than before the order.

**Browser check:** open `http://localhost:3000`. Click "Refresh
Products" — table populates. Register/login — token JSON appears. Enter
a product id and quantity, click "Place Order" — order JSON appears and
the product table's stock count drops on refresh.

## §4 — OpenShift secrets

```bash
oc get secrets
```
**Expect:** `product-db-credentials`, `order-db-credentials`,
`user-db-credentials`, `user-service-jwt` listed.

## §5 — Database pods

```bash
oc get pods -l 'app in (product-db,order-db,user-db)'
```
**Expect:** all three `Running`, `READY 1/1`.

```bash
oc rsh deploy/product-db psql -U capstone -d productdb -c '\dt'
```
**Expect:** connects without error (table list will be empty until the
app has connected once and Hibernate creates the `products` table).

## §6 — Tekton installed

```bash
tkn task list
tkn pipeline list
```
**Expect:** Tasks include `git-clone`, `buildah`, `openshift-client`,
`maven-build`. Pipelines include `capstone-build-deploy` and
`capstone-build-deploy-frontend`.

## §7 — App objects pre-created

```bash
oc get deploy,svc,route
```
**Expect:** `product-service`, `order-service`, `user-service`,
`frontend` all listed as Deployments, Services, and Routes. Pods will be
in `ImagePullBackOff` — expected at this stage, not an error.

## §8 — Pipeline run

```bash
tkn pipelinerun logs --last -f
```
**Expect:** all four steps (`fetch-source`, `maven-build`,
`build-and-push`, `deploy`) show green checkmarks and the run status is
`Succeeded`. (`maven-build` prints `BUILD SUCCESS`; `build-and-push`
prints the pushed image digest; `deploy` prints `deployment "..."
successfully rolled out`.)

```bash
oc get pods
```
**Expect:** app pods now `Running`, `READY 1/1` or `2/2`, no more
`ImagePullBackOff`.

## §9 — Full browser walkthrough on the cluster

```bash
oc get routes
```
Note the four hostnames. Open the `frontend` route's URL in a browser.
Paste the other three routes' full `https://` URLs into the endpoint
fields at the top of the page.

1. Register a new user → token JSON appears.
2. Click "Refresh Products" → table populates from `product-service`'s
   Route.
3. Place an order with a valid product id and quantity → order JSON
   appears with `status: CONFIRMED`.
4. Click "Refresh Products" again → stock count for that product has
   gone down.
5. Click "Refresh Orders" → your new order appears in the table.

**Expect:** all five steps succeed with no CORS or connection errors in
the browser console (each Spring Boot service has `@CrossOrigin(origins =
"*")` enabled for this reason).

## Troubleshooting quick reference

| Symptom | Likely cause |
|---|---|
| `ImagePullBackOff` after Step 7 | Expected until Step 8's pipeline runs |
| Pipeline fails at `maven-build` | A real compile/test failure — check `tkn pipelinerun logs --last` for the Maven stack trace |
| Pipeline fails at `build-and-push` | Check the `pipeline-sa` RoleBinding from Step 6 was applied; without `system:image-builder` the push to OCR is denied |
| Order returns 409 Conflict | Insufficient stock — check the product's current `stockQuantity` first |
| Frontend shows CORS error | You're hitting `http://` from an `https://` frontend Route — use `https://` for every service URL field |
