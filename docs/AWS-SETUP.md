# Using AWS Resources (Optional)

Everything in this project runs on any OpenShift cluster. If you'd rather
not run OpenShift Local/CRC on your laptop, here's how to get the same
result using AWS.

## Option 1 — Red Hat OpenShift Service on AWS (ROSA)

The closest match to "a real OpenShift cluster" without self-managing
control-plane nodes.

1. From the AWS console or CLI, follow Red Hat's ROSA quickstart to
   provision a cluster (requires an AWS account with the ROSA service
   enabled and a Red Hat account linked).
2. Once ready, `oc login` using the cluster's API URL and your ROSA
   credentials — everything in `STEP-BY-STEP.md` from Step 4 onward is
   identical.
3. The OpenShift Container Registry (OCR) is still the internal
   in-cluster registry; you don't need Amazon ECR unless you want an
   extra credit stretch goal of pushing a mirrored copy there too.

## Option 2 — Self-managed OpenShift (or a lightweight distro) on EC2

If ROSA isn't available to you (cost, account restrictions), you can run
a single-node OpenShift (or CodeReady Containers / `crc`) on a large EC2
instance:
- Instance type: at least `m5.2xlarge` (8 vCPU / 32 GiB) for CRC to be
  usable.
- Open inbound ports for the API server (6443) and the router (443/80)
  in the instance's security group, scoped to your IP.
- Everything downstream (Steps 4–9) is unchanged.

## Option 3 — Use AWS just for the database (RDS)

You can keep the app tier on OpenShift (cluster of your choice) and swap
the three in-cluster Postgres Deployments for three Amazon RDS for
PostgreSQL instances (or one instance with three databases):

1. Create the RDS instance(s) in the same VPC/region as your cluster (or
   with a peered/public endpoint you're comfortable exposing for a demo).
2. Instead of `oc apply -f openshift/postgres/*-deployment.yaml` and
   `*-service.yaml`, skip those two files per service and instead set
   `DB_URL` in each app Deployment to point at the RDS endpoint, e.g.:
   ```yaml
   - name: DB_URL
     value: jdbc:postgresql://<rds-endpoint>:5432/productdb
   ```
3. Store the RDS master password the same way — as an OpenShift Secret,
   referenced by the Deployment — never hardcoded in a manifest you
   commit to GitHub.
4. Everything else (Tekton pipeline, Routes, frontend) is unchanged.

## Option 4 — Amazon ECR alongside OCR (stretch)

Not required — OCR already satisfies the "container registry" capstone
requirement — but if you want to show a multi-registry push, add a second
`buildah` Task step in the Tekton Pipeline targeting an ECR repository
URI, authenticated via an OpenShift Secret of type
`kubernetes.io/dockerconfigjson` built from `aws ecr get-login-password`.

## Cost note

ROSA and RDS both incur ongoing AWS charges the moment they're running.
For a capstone demo, provision right before you need to demo/grade it and
tear down (`rosa delete cluster`, `aws rds delete-db-instance`, terminate
EC2 instances) immediately after.
