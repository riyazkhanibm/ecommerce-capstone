# E-Commerce Capstone Project

Cloud-native e-commerce backend: three Java/Spring Boot microservices,
Maven builds, PostgreSQL (database-per-service), containerized on Linux,
built and deployed by a Tekton pipeline onto OpenShift, images pushed to
the OpenShift Container Registry. A static frontend gives you a real
browser to click through the whole flow.

## Read these in order

1. **[docs/PROJECT-OVERVIEW.md](docs/PROJECT-OVERVIEW.md)** — what this
   project is, what "done" looks like, rubric checklist.
2. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — service map, and a
   deep comparison of the two ways to handle the riskiest part of the
   system (placing an order across two services).
3. **[docs/STEP-BY-STEP.md](docs/STEP-BY-STEP.md)** — the full build →
   push → deploy procedure, start to finish.
4. **[docs/VERIFICATION.md](docs/VERIFICATION.md)** — exact commands and
   expected output for verifying every single stage, locally and in the
   browser.
5. **[docs/AWS-SETUP.md](docs/AWS-SETUP.md)** — optional: how to run this
   on ROSA, self-managed OpenShift on EC2, or with RDS instead of
   in-cluster Postgres.

## Project layout

```
ecommerce-capstone/
├── services/
│   ├── product-service/   # Spring Boot, Maven, port 8081
│   ├── order-service/     # Spring Boot, Maven, port 8082
│   └── user-service/      # Spring Boot, Maven, port 8083 (JWT auth)
├── frontend/               # Static HTML/JS + nginx, port 8080
├── db/                     # local docker-compose DB init
├── docker-compose.yml      # full local stack for fast iteration
├── openshift/               # Deployment / Service / Route / PVC / Secret YAML
│   ├── postgres/            # one Postgres per service
│   ├── product-service/
│   ├── order-service/
│   ├── user-service/
│   └── frontend/
├── tekton/                  # Pipeline, custom Task, PipelineRuns
└── docs/
```

## Quickest path to "I see it working"

```bash
docker compose up -d --build
open http://localhost:3000   # or just visit it in a browser
```
Then follow `docs/STEP-BY-STEP.md` for the OpenShift + Tekton path.


