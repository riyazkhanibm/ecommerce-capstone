# Capstone Project: Cloud-Native E-Commerce Platform

## What this project is

A small but complete e-commerce backend built as three independent Java
microservices, containerized, built and deployed through a Tekton CI/CD
pipeline onto an OpenShift cluster, with images stored in the OpenShift
Container Registry (OCR). A static HTML/JS frontend lets you exercise the
whole system from a browser.

This satisfies a capstone that must demonstrate:
- **Maven** — every service is a standard Maven-built Spring Boot app.
- **Microservices** — three independently deployable, independently
  data-owning services (product, order, user).
- **Database** — PostgreSQL, one database per service (database-per-service
  pattern).
- **Linux** — all containers run on Linux base images; all CLI steps assume
  a Linux/macOS/WSL shell.
- **OpenShift cluster** — Deployments, Services, Routes, PVCs, Secrets.
- **OpenShift Container Registry (OCR)** — the internal registry is the
  target of the image build/push step.
- **Tekton** — Pipelines, Tasks, and PipelineRuns drive build → test →
  image → deploy.
- **GitHub** — the canonical source of truth; Tekton clones from it.

## What "done" looks like (expected deliverables)

1. A GitHub repository containing this project layout, pushed under your
   own account.
2. A running OpenShift project (namespace) with:
   - 3 PostgreSQL instances (or equivalent managed databases — see
     `AWS-SETUP.md` for an RDS alternative)
   - product-service, order-service, user-service, and frontend all
     running as Pods behind Services, exposed via Routes
3. A Tekton Pipeline that, given a git commit, rebuilds the changed
   service's image, pushes it to OCR, and rolls out the new Deployment —
   triggered manually via `tkn` / `oc create -f pipelinerun-*.yaml` for
   this capstone (a webhook Trigger is listed as a stretch goal).
4. Screenshots or terminal transcripts (see `VERIFICATION.md`) showing:
   - `mvn clean package` succeeding locally for all 3 services
   - `docker compose up` serving the full app locally, with working
     product listing, registration/login, and order placement
   - A successful `PipelineRun` in the OpenShift console or `tkn` CLI
   - The frontend Route URL open in a browser, placing a real order
     end-to-end through the cluster

## High-level flow

```
Developer -> git push -> GitHub
                              |
                              v
                    Tekton PipelineRun (manual trigger)
               fetch-source -> maven-build -> buildah build/push -> oc rollout
                                                     |
                                                     v
                                   OpenShift Container Registry (OCR)
                                                     |
                                                     v
                        OpenShift Deployment pulls new image, rolls out
                                                     |
                                                     v
                     product-service / order-service / user-service
                                (each with its own Postgres)
                                                     |
                                                     v
                                   Route (HTTPS) -> Browser / curl
```

## Rubric-style checklist

| Requirement | Where it's satisfied |
|---|---|
| Maven build | `services/*/pom.xml`, `tekton/tasks/maven-build-task.yaml` |
| Microservices | `services/product-service`, `order-service`, `user-service` |
| Database | PostgreSQL, one DB per service, `openshift/postgres/*` |
| Linux | All Dockerfiles use Linux base images |
| OpenShift cluster | `openshift/**/*.yaml` |
| OCR | `IMAGE` params in `tekton/pipeline.yaml` target the internal registry |
| Tekton pipeline | `tekton/pipeline.yaml`, `tekton/tasks/`, `tekton/pipelinerun-*.yaml` |
| Source in GitHub | See "Step 1: Push to GitHub" below |
| Local + browser verification | `docs/VERIFICATION.md` |
