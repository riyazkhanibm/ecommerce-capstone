# Fixes and Adaptations

Eight issues encountered building and deploying this project, with the diagnosis for
each. Items 1–3 are defects in the supplied source: they occur on any machine, every
time, and the first two prevent the service from starting at all. Anyone else building
this project will hit them identically.

Items 4–8 are adaptations required because the OpenShift account used here is
namespace-scoped rather than cluster-admin — the normal situation on a shared or
managed cluster.

---

## 1. product-service fails to start — `relation "products" does not exist`

**Symptom**

The container exits with code 1 roughly 20 seconds after start. `docker compose ps -a`
shows `Exited (1)`.

```
Caused by: org.springframework.jdbc.datasource.init.ScriptStatementFailedException:
  Failed to execute SQL script statement #1 of URL [...data.sql]:
  INSERT INTO products (name, description, price, stock_quantity, category) ...
Caused by: org.postgresql.util.PSQLException: ERROR: relation "products" does not exist
```

**Diagnosis**

`application.yml` sets `spring.jpa.hibernate.ddl-auto: update` and
`spring.sql.init.mode: always`. Those are two different lifecycle phases:

- `data.sql` runs during **DataSource** initialisation
- Hibernate's schema generation runs during **JPA** initialisation, which is later

So the INSERT executes against a database where the table has not been created yet.

**Root cause**

Spring Boot 2.5 deliberately separated these phases. Before 2.5 the ordering happened
to work; since 2.5 you have to opt in explicitly. The project appears to have been
written against an already-populated database, where `data.sql` succeeded because the
table already existed.

**Fix**

`services/product-service/src/main/resources/application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
    defer-datasource-initialization: true    # added
```

This defers `data.sql` until after Hibernate has built the schema.

---

## 2. order-service fails to start — `BeanDefinitionOverrideException`

**Symptom**

Container exits with code 1 during context initialisation.

```
BeanDefinitionOverrideException: Invalid bean definition with name
'productServiceClient' defined in com.capstone.order.OrderServiceApplication:
Cannot register bean definition [...] since there is already
[Generic bean: class [com.capstone.order.client.ProductServiceClient]] bound.
```

**Diagnosis**

Two different types both claim the bean name `productServiceClient`:

- `OrderServiceApplication` has `@Bean public WebClient productServiceClient(...)` —
  Spring names factory beans after the **method**
- `ProductServiceClient` is annotated `@Component` — Spring names scanned components
  after the **class**, lower-cased

`ProductServiceClient`'s constructor takes `WebClient productServiceClient`, so the
intent was clearly for the class to receive the WebClient bean by name. The naming
scheme is what broke it.

**Root cause**

Spring Boot 2.1 made duplicate bean definitions fatal by default
(`spring.main.allow-bean-definition-overriding=false`). Previously one silently
overwrote the other, which could have produced a much more confusing failure later.

**Fix**

Rename the factory method so the two names no longer collide.

`OrderServiceApplication.java`:

```java
@Bean
public WebClient productWebClient(@Value("${product.service.url}") String baseUrl) {
    return WebClient.builder().baseUrl(baseUrl).build();
}
```

`ProductServiceClient.java` — the constructor parameter must match the new bean name:

```java
public ProductServiceClient(WebClient productWebClient) {
    this.webClient = productWebClient;
}
```

---

## 3. Order responses return unbounded, deeply nested JSON

**Symptom**

`POST /api/orders` succeeds — the order is created, `status: CONFIRMED`, stock is
decremented — but the response body nests infinitely and takes several seconds to
return:

```json
{ "id": 6, "items": [ { "id": 6, "order": { "id": 6, "items": [ { "order": { ... } } ] } } ] }
```

**Diagnosis**

`Order` has `@OneToMany List<OrderItem> items`; `OrderItem` has `@ManyToOne Order
order`. Neither side carries a Jackson annotation, so serialising an Order walks into
its items, then each item's order, then that order's items, and so on. It only
terminates when Jackson hits its nesting limit.

**Root cause**

A bidirectional JPA relationship with no serialization boundary. Correct as a
persistence model, incorrect as a wire format.

**Fix**

`services/order-service/src/main/java/com/capstone/order/model/OrderItem.java`:

```java
import com.fasterxml.jackson.annotation.JsonIgnore;

@JsonIgnore
@ManyToOne
private Order order;
```

The Order still serialises its items; items no longer serialise their parent.
`@JsonManagedReference` / `@JsonBackReference` achieves the same result more verbosely.

**Related observation (not fixed)**

The `@OneToMany` also specifies `fetch = FetchType.EAGER`, so every order query loads
all its items whether needed or not. Acceptable for a demo; in production this is the
classic N+1 problem and would be changed to `LAZY` with an explicit fetch join.

---

## 4. The `buildah` Tekton Task cannot run — privileged SCC denied

**Symptom**

The pipeline's `build-and-push` stage cannot be scheduled. Confirmed directly:

```
$ oc auth can-i use scc/privileged
no
```

**Diagnosis**

`buildah` builds the container image **inside the pipeline pod**, which requires the
`privileged` SCC. Kubernetes RBAC forbids privilege escalation — you cannot create a
binding granting rights you do not hold — so this is not fixable by adding a Role.

**Root cause**

A privileged pod can escape to the node and observe other tenants' containers.
Multi-tenant clusters forbid it deliberately.

**Fix**

Replace the buildah stage with a custom `oc-build` Task that starts an OpenShift
**BuildConfig** (`tekton/tasks/oc-build-task.yaml`,
`openshift/builds/buildconfigs.yaml`).

A BuildConfig does not build anything itself. It declares a source, a Dockerfile and an
output ImageStream. When triggered, OpenShift's **build controller** — a platform
component that already holds the necessary privileges — creates a separate build pod
and runs the build there.

The privileged work still happens; it happens under the platform's identity rather than
yours. The pipeline stays unprivileged and the image still reaches the internal
registry.

This is the OpenShift-native pattern rather than a workaround. BuildConfigs predate
Tekton and exist precisely so developers can produce images without cluster-admin. The
trade-off is portability: a buildah pipeline runs on any Kubernetes cluster, this one
only on OpenShift.

Note this also removes the need for the `system:image-builder` RoleBinding the original
project supplies — the platform's `builder` ServiceAccount pushes, not the pipeline's.

---

## 5. `contextDir` conflicts with `--from-dir` binary builds

**Symptom**

The `oc-build` stage fails, having apparently found and then lost the same directory:

```
Uploading directory "services/product-service" as binary input for the build ...
error: provided context directory does not exist: services/product-service
```

**Diagnosis**

`oc start-build --from-dir=services/product-service` uploads the **contents** of that
directory as the build root, so inside the build the Dockerfile is at `./Dockerfile`.
The BuildConfig still specified `contextDir: services/product-service`, so it then
looked for that path *inside* the uploaded archive — where it does not exist, because
the archive already is that directory.

**Root cause**

`contextDir` applies to Git-sourced builds. With a binary source the uploaded directory
is already the context. One BuildConfig, two trigger modes, incompatible on that field.

**Fix**

Remove `contextDir` from all four BuildConfigs. The standalone `oc start-build`
(Git-sourced) path is given up in exchange; the pipeline is the intended trigger.

`--from-dir` is retained deliberately: it makes the build use the exact commit that
`git-clone` fetched and `maven-build` tested, rather than re-cloning and possibly
picking up a newer commit that landed mid-pipeline.

---

## 6. EventListener crashes — cannot list `clusterinterceptors`

**Symptom**

`el-capstone-listener` in `CrashLoopBackOff`.

```
failed to list *v1alpha1.ClusterInterceptor: clusterinterceptors.triggers.tekton.dev
is forbidden: User "system:serviceaccount:riyazkhan03-dev:tekton-triggers-sa"
cannot list resource "clusterinterceptors" at the cluster scope
2026/08/22 17:42:10 failed to start informers: failed to wait for cache at index 0 to sync
```

**Diagnosis**

The listener watches `ClusterInterceptor`, which is **cluster-scoped**. A namespaced
`Role` cannot grant cluster-scoped access regardless of what its rules say — only a
`ClusterRoleBinding` can, and that requires cluster-admin.

**Fix**

Use the `pipeline` ServiceAccount created by the OpenShift Pipelines operator, which
already has the required ClusterRoleBinding:

```yaml
spec:
  serviceAccountName: pipeline
```

Broader than least-privilege would prefer. On a cluster where you can create
ClusterRoleBindings, the correct fix is a dedicated ServiceAccount bound to a
ClusterRole granting only `get`/`list`/`watch` on `clusterinterceptors`.

---

## 7. GitHub webhook returns 503 — Route created without TLS

**Symptom**

Every delivery fails in ~20 ms with 503, while the listener pod is `1/1 Running`,
`Ready: True`, and endpoints are populated.

**Diagnosis**

Isolated by bypassing the router entirely:

```bash
oc port-forward svc/el-capstone-listener 8080:8080
curl -i -X POST http://localhost:8080 -H 'X-GitHub-Event: push' -d '{}'
# HTTP/1.1 202 Accepted
```

The pod serves correctly. The failure is between the router and the Service.

Comparing Routes showed the difference: the four application Routes have
`edge/Redirect` TLS termination; the listener Route, created with plain `oc expose`,
had none — so it only accepted HTTP on port 80, while GitHub posts over HTTPS.

**Fix**

```bash
oc delete route el-capstone-listener
oc create route edge el-capstone-listener \
  --service=el-capstone-listener --port=8080 --insecure-policy=Redirect
```

**Method note.** Pod healthy, endpoints populated, Service correct, Route targeting the
right port — and still 503. The port-forward is what split the problem: proving the pod
returned 202 while the Route returned 503 localised the fault to the router in one step.
Verifying one layer out-of-band before changing configuration is faster than adjusting
settings and retrying.

---

## 8. An unfiltered webhook rebuilds every component on every push

**Symptom**

Not a failure — a design problem. This repository holds four independently deployable
components. A single trigger fires all of them, so a one-line README change costs three
Maven builds and a container build.

**Fix**

Four triggers on one EventListener, each with a CEL interceptor that inspects the
changed paths in the push payload
(`tekton/triggers/github-trigger.yaml`):

```yaml
- ref:
    name: cel
  params:
    - name: filter
      value: >-
        body.ref == 'refs/heads/main' &&
        body.commits.exists(c,
          c.added.exists(f, f.startsWith('services/order-service/')) ||
          c.modified.exists(f, f.startsWith('services/order-service/')) ||
          c.removed.exists(f, f.startsWith('services/order-service/')))
```

The expression walks `body.commits[]` because one push can carry several commits, and
checks `added`, `modified` and `removed` — a push that only deletes a file still
changes the image.

A second pipeline, `capstone-build-nomaven`, serves the frontend, which has no
`pom.xml` and would fail the `maven-build` stage.

Verified: a push touching only `services/order-service/` produces exactly one
PipelineRun. A push touching only `README.md` produces none.

---

## Security note on validation error responses

Not a defect, but worth recording. `POST /api/auth/register` returns a bare 400 with no
indication of which field failed:

```json
{"timestamp":"...","status":400,"error":"Bad Request","path":"/api/auth/register"}
```

`RegisterRequest` carries `@NotBlank`, `@Email` and `@Size(min = 8)`, but no handler for
`MethodArgumentNotValidException` exists, so the caller cannot tell whether the email
was malformed or the password too short. Adding one to each service's
`GlobalExceptionHandler` would return:

```json
{"password":"Password must be at least 8 characters",
 "email":"must be a well-formed email address"}
```

Left unimplemented; recorded as a recommended improvement.
