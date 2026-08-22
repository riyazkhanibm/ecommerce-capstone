# Step-by-Step Procedure

Do the steps in order. Each step has a "Verify" note; full verification
detail (commands, expected output, browser checks) is in
`VERIFICATION.md` — this file just tells you when to check.

## Prerequisites

- JDK 17, Maven 3.9+
- Docker or Podman, and Docker Compose (or `podman-compose`)
- `oc` CLI logged into an OpenShift cluster (OpenShift Local / CRC works
  for a laptop demo; OpenShift on AWS ROSA or a self-managed cluster on
  AWS EC2 both work too — see `AWS-SETUP.md`)
- `tkn` CLI (Tekton CLI) — optional but convenient; you can also use
  `oc apply -f` / `oc create -f` for everything Tekton
- A GitHub account and `git` installed
- Tekton Pipelines and Tekton Hub tasks available on your cluster (most
  OpenShift clusters: install via the "Red Hat OpenShift Pipelines"
  Operator from OperatorHub — one click, no CLI needed)

## Step 1 — Get the code building locally

1. Unzip/copy this project to your machine.
2. For each service, run:
   ```bash
   cd services/product-service && mvn -B clean package -DskipTests && cd ../..
   cd services/order-service   && mvn -B clean package -DskipTests && cd ../..
   cd services/user-service    && mvn -B clean package -DskipTests && cd ../..
   ```
3. **Verify:** each command ends with `BUILD SUCCESS` and a jar appears
   under `target/`. See `VERIFICATION.md` §1.

## Step 2 — Push the code to GitHub

1. Create a new, empty repository on GitHub, e.g. `ecommerce-capstone`
   (do **not** initialize it with a README).
2. From the project root:
   ```bash
   cd ecommerce-capstone
   git init
   git add .
   git commit -m "Initial capstone commit: microservices, OpenShift, Tekton"
   git branch -M main
   git remote add origin https://github.com/<your-username>/ecommerce-capstone.git
   git push -u origin main
   ```
3. Update `GIT_URL` in every `tekton/pipelinerun-*.yaml` to point at your
   new repo URL.
4. **Verify:** refresh the GitHub repo page in a browser and confirm all
   files are present. See `VERIFICATION.md` §2.

## Step 3 — Run the whole stack locally with Docker Compose

1. From the project root:
   ```bash
   docker compose build
   docker compose up -d
   docker compose ps
   ```
2. **Verify:** all containers show `Up (healthy)`/`Up`, then follow
   `VERIFICATION.md` §3 to exercise the APIs with `curl` and the UI at
   `http://localhost:3000` in a browser. Do this before touching
   OpenShift — it's the fastest feedback loop and proves the application
   logic is correct independent of the cluster.
3. Tear down when done: `docker compose down -v`

## Step 4 — Create the OpenShift project and secrets

1. Log in and create a project:
   ```bash
   oc login --token=<your-token> --server=<your-cluster-api-url>
   oc new-project ecommerce-capstone
   ```
2. Create the database secrets (don't apply the checked-in template
   secrets as-is in a shared cluster — use real generated passwords):
   ```bash
   oc create secret generic product-db-credentials \
     --from-literal=POSTGRESQL_USER=capstone \
     --from-literal=POSTGRESQL_PASSWORD=$(openssl rand -base64 18) \
     --from-literal=POSTGRESQL_DATABASE=productdb

   oc create secret generic order-db-credentials \
     --from-literal=POSTGRESQL_USER=capstone \
     --from-literal=POSTGRESQL_PASSWORD=$(openssl rand -base64 18) \
     --from-literal=POSTGRESQL_DATABASE=orderdb

   oc create secret generic user-db-credentials \
     --from-literal=POSTGRESQL_USER=capstone \
     --from-literal=POSTGRESQL_PASSWORD=$(openssl rand -base64 18) \
     --from-literal=POSTGRESQL_DATABASE=userdb

   oc create secret generic user-service-jwt \
     --from-literal=JWT_SECRET=$(openssl rand -base64 32)
   ```
3. **Verify:** `oc get secrets` lists all four. See `VERIFICATION.md` §4.

## Step 5 — Deploy the databases

1. ```bash
   oc apply -f openshift/postgres/product-db-pvc.yaml
   oc apply -f openshift/postgres/product-db-deployment.yaml
   oc apply -f openshift/postgres/product-db-service.yaml

   oc apply -f openshift/postgres/order-db-pvc.yaml
   oc apply -f openshift/postgres/order-db-deployment.yaml
   oc apply -f openshift/postgres/order-db-service.yaml

   oc apply -f openshift/postgres/user-db-pvc.yaml
   oc apply -f openshift/postgres/user-db-deployment.yaml
   oc apply -f openshift/postgres/user-db-service.yaml
   ```
2. **Verify:** `oc get pods -l app=product-db` (and order-db, user-db)
   show `Running` / `1/1`. See `VERIFICATION.md` §5.

## Step 6 — Install Tekton Pipelines and the Hub tasks

1. Install the **Red Hat OpenShift Pipelines** Operator from OperatorHub
   in the OpenShift web console (Administrator view → Operators →
   OperatorHub → search "Pipelines" → Install).
2. Install the reusable Tasks from Tekton Hub (these are official,
   maintained Task definitions — no need to hand-write them):
   ```bash
   tkn hub install task git-clone
   tkn hub install task buildah
   tkn hub install task openshift-client
   ```
   (If `tkn hub` isn't available, apply the Task YAML from
   https://hub.tekton.dev directly with `oc apply -f <raw-url>`.)
3. Apply this project's pipeline objects:
   ```bash
   oc apply -f tekton/01-pvc-workspace.yaml
   oc apply -f tekton/02-serviceaccount.yaml
   oc apply -f tekton/03-rolebinding.yaml
   oc apply -f tekton/tasks/maven-build-task.yaml
   oc apply -f tekton/pipeline.yaml
   oc apply -f tekton/pipeline-frontend.yaml
   ```
4. **Verify:** `tkn task list` shows `git-clone`, `buildah`,
   `openshift-client`, `maven-build`; `tkn pipeline list` shows
   `capstone-build-deploy` and `capstone-build-deploy-frontend`. See
   `VERIFICATION.md` §6.

## Step 7 — Pre-create the Deployments, Services, and Routes

Tekton's `deploy` step does an `oc rollout restart`, which requires the
Deployment to already exist. Create the app objects first (they'll fail
to pull an image until Step 8 runs, which is expected):

```bash
for svc in product-service order-service user-service frontend; do
  oc apply -f openshift/$svc/deployment.yaml
  oc apply -f openshift/$svc/service.yaml
  oc apply -f openshift/$svc/route.yaml
done
```

**Verify:** `oc get deploy,svc,route` lists all four services. Pods will
show `ImagePullBackOff` — that's expected until the pipeline pushes a real
image. See `VERIFICATION.md` §7.

## Step 8 — Run the Tekton pipeline for each service

1. Edit `GIT_URL` in each `tekton/pipelinerun-*.yaml` to your GitHub repo
   URL if you haven't already.
2. Trigger each build:
   ```bash
   oc create -f tekton/pipelinerun-product-service.yaml
   oc create -f tekton/pipelinerun-order-service.yaml
   oc create -f tekton/pipelinerun-user-service.yaml
   oc create -f tekton/pipelinerun-frontend.yaml
   ```
3. Watch progress:
   ```bash
   tkn pipelinerun logs --last -f
   ```
   or watch in the OpenShift console under Pipelines → PipelineRuns.
4. **Verify:** each PipelineRun reaches `Succeeded`, and
   `oc get pods` shows the four app Deployments now `Running` with
   `1/1` (or `2/2`) ready. See `VERIFICATION.md` §8.

## Step 9 — Verify the whole system through the browser

1. Get the frontend's public URL:
   ```bash
   oc get route frontend -o jsonpath='{.spec.host}'
   ```
2. Open `https://<that-host>` in a browser, fill in the service URL
   fields at the top with the other three Routes' hostnames (get them
   with `oc get routes`), and walk through register → login → view
   products → place an order.
3. **Verify:** full walkthrough with expected results in
   `VERIFICATION.md` §9.

## Step 10 (stretch) — Automate triggering

For a fully "push to GitHub triggers a build" pipeline, add a Tekton
`EventListener` + `TriggerBinding` + `TriggerTemplate` wired to a GitHub
webhook pointed at your cluster's exposed EventListener Route. This is
intentionally left as a stretch goal — the manual `oc create -f
pipelinerun-*.yaml` trigger used above is enough to demonstrate the full
CI/CD path for grading, and skips the extra complexity of exposing a
public webhook endpoint securely.
