# TAP 7.4 Pure Reconcile Demonstrator

This repository is the consumer deployment kit and policy catalog for the TAP
7.4 GitOps policy flow. It deploys published images and released Helm charts;
it does not build Java code or vendor Umbrella charts.

## Repository split

| Repository | Responsibility |
|---|---|
| `adtorales/Fleet` | Fleet fork: Registry, reconciler, xRegistry/OCI code, EDC launcher, Registry chart, and published Registry/EDC images |
| `adtorales/demo` | This repository: policy catalog, catalog validation/publishing workflows, deployment values, scripts, and acceptance evidence |

The flow is: catalog OCI artifacts → Registry → control-plane reconciliation.
Assets and contract definitions remain imperative and are seeded through the
Management API.

## Prerequisites

Install these tools on `PATH`:

- Docker Desktop (or compatible engine), with at least 4 CPUs and 10 GB RAM;
- Minikube with the Docker driver;
- `kubectl`, Helm 3, PowerShell 7+, and Git.

Internet access to GHCR and `https://eclipse-tractusx.github.io/charts/dev` is
required. The following published artifacts must be public:

- `ghcr.io/adtorales/tap74-registry:latest`
- `ghcr.io/adtorales/edc-demo:latest`
- `ghcr.io/adtorales/xregistry-policies:current`
- `ghcr.io/adtorales/xregistry-dataspace-schemas:current`
- `oci://ghcr.io/adtorales/charts/tap74-registry:0.1.0`

If the images or artifacts are private, create a GitHub PAT with only
`read:packages` and use `3-create-umbrella-registry-secret.ps1` before deploy.

## Clone and host names

Only clone this repository. The script installs the released
`tractusx-dev/umbrella` chart at version `26.03.00`; neither a Fleet checkout
nor an Umbrella source checkout is required by a demo user.

```powershell
git clone https://github.com/adtorales/demo.git
cd demo
```

Add these entries to the host operating system's `hosts` file (on Windows:
`C:\Windows\System32\drivers\etc\hosts`, edited as administrator):

```text
127.0.0.1 dataconsumer-1-controlplane.tx.test
127.0.0.1 dataprovider-controlplane.tx.test
127.0.0.1 dataconsumer-1-dataplane.tx.test
127.0.0.1 dataprovider-dataplane.tx.test
127.0.0.1 ssi-dim-wallet-stub.tx.test
127.0.0.1 bdrs-server.tx.test
127.0.0.1 dataprovider-submodelserver.tx.test
```

The wallet host is mapped by the deploy script inside CoreDNS as well, so pods
can resolve DID documents without a port-forward.

## Start Minikube and deploy

```powershell
minikube start --cpus=4 --memory=8192 --driver=docker
minikube addons enable ingress
```

In a separate terminal, leave this running for the full demo session:

```powershell
minikube tunnel
```

Then deploy from this repository:

```powershell
.\4-deploy-umbrella-tap74.ps1 -ResetVaultWebhookConfigs
```

The script installs the released Umbrella chart, the Registry Helm OCI chart,
and the published TAP 7.4 images. It also configures the in-cluster wallet DNS
mapping and the dataplane-selector adjustment.

## Wait for dependencies

On a fresh cluster, do **not** treat an initial `Error`, `CrashLoopBackOff`, or
a non-zero restart count as a final failure. PostgreSQL, Vault, Registry, SSI
DIM wallet, and EDC control planes start independently. A component can restart
while it waits for its database or an upstream service, then become healthy.

Wait until the following pods are currently `Running` and ready before seeding
or testing. A historical value in the `RESTARTS` column is normal after a
successful recovery.

```powershell
kubectl get pods -n umbrella -w
kubectl rollout status deployment/tap74-registry -n umbrella --timeout=10m
kubectl rollout status deployment/ssi-dim-wallet-stub -n umbrella --timeout=10m
kubectl rollout status deployment/umbrella-dataprovider-edc-controlplane -n umbrella --timeout=10m
kubectl rollout status deployment/umbrella-dataconsumer-1-edc-controlplane -n umbrella --timeout=10m
```

The essential workloads are `tap74-registry`, `ssi-dim-wallet-stub`,
`wallet-postgres`, provider/consumer Vault and PostgreSQL pods, and both EDC
control planes. For a pod that remains non-ready, inspect:

```powershell
kubectl logs -n umbrella deployment/<deployment-name> --tail=200
```

## Seed and test

After the control planes are ready, seed the imperative asset and contract
definition:

```powershell
.\5-tap74-seed-demo.ps1
```

Run the baseline runtime checks and collect evidence:

```powershell
.\6-run-manual-tests.ps1 -CollectEvidence
```

The script verifies reachable Management APIs, identical reconciled policy
state on provider and consumer, and the seeded provider asset/contract. Add
`-IncludeLockedRoomTest` to create a rogue consumer policy and verify that the
reconciler removes it on the next cycle.

## Manual acceptance scenarios

Catalog source and its GitHub Actions workflows live in `policy-catalog/`.
The manual scenarios require a catalog pull request, OCI publication, or a
Helm change:

- **S1:** add a valid new policy version and verify `created` in every control plane.
- **S2:** enable `dataconsumerTwo` and verify first-cycle convergence.
- **S3/S4:** submit an invalid policy; validation must reject it.
- **S5:** publish a major schema update and verify `invalid`/frozen handling.
- **S6:** add a version while retaining V1; verify coexistence.
- **S7:** remove a bound policy; verify `retained`, then unbind and verify deletion.
- **S8a:** restart an offline connector and verify it converges.

Detailed steps are in `policy-catalog/docs/manual-demo-scenarios.md`.

## Logs and evidence

Observe Registry OCI reloads:

```powershell
kubectl logs -n umbrella deployment/tap74-registry -f
```

The Registry reports policy-group/policy and schema-group/schema counts
separately. After a catalog update it reloads the OCI artifacts and serves the
new desired state atomically.

Observe reconciliation on both control planes:

```powershell
kubectl logs -n umbrella deployment/umbrella-dataprovider-edc-controlplane -f
kubectl logs -n umbrella deployment/umbrella-dataconsumer-1-edc-controlplane -f
```

The key record is:

```text
Policy reconciliation completed: created=1, updated=0, deleted=0,
retained=0, unchanged=3, invalid=0, failed=0
```

`created`, `updated`, `deleted`, `retained`, `unchanged`, `invalid`, and
`failed` are the runtime evidence for the reconciliation scenarios.
`6-run-manual-tests.ps1 -CollectEvidence` writes API responses, pod state, and
Registry logs under `evidence-<timestamp>/`.
