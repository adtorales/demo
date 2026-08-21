# TAP 7.4 Pure Reconcile Demonstrator

This folder is the **consumer deployment kit** for the TAP 7.4 Pure Reconcile
demonstrator. It deploys the published Registry and EDC control-plane images
to a local Kubernetes cluster; it does not build Java code or Docker images.

The demo proves a GitOps policy flow:

1. A policy catalog is published as OCI artifacts.
2. The TAP 7.4 Registry periodically pulls those artifacts and serves one
   merged xRegistry document over HTTP.
3. Each EDC control plane periodically reconciles its local policy store to
   that desired state.
4. Assets and contract definitions remain imperative and are seeded through
   the Management API.

## 1. Prerequisites

Install and make the following tools available on `PATH`:

- Docker Desktop or another Docker-compatible engine, running and configured
  with at least **4 CPUs and 10 GB RAM** available to it.
- [Minikube](https://minikube.sigs.k8s.io/), using the Docker driver.
- `kubectl`.
- Helm 3.
- PowerShell 7 or later.
- Git, to clone the deployment kit and Tractus-X Umbrella.

The deployment downloads Helm dependencies and published images, so internet
access to GHCR, the Tractus-X chart repository, Bitnami, and HashiCorp is also
required.

The published images and OCI artifacts are:

- `ghcr.io/adtorales/tap74-registry:latest`
- `ghcr.io/adtorales/edc-demo:latest`
- `ghcr.io/adtorales/xregistry-policies:current`
- `ghcr.io/adtorales/xregistry-dataspace-schemas:current`

They must be public. If they are private, create a GitHub PAT with only the
`read:packages` scope and use the registry-secret step below.

## 2. Local folder layout

Clone only the deployment kit and Umbrella. A consumer does **not** need to
clone `tap74`, `tap74-registry`, `tap74-demonstrator`, or the policy catalog.

```powershell
mkdir C:\workspace\tap74-demo
cd C:\workspace\tap74-demo
git clone <demo-kit-repository-url> demo
git clone https://github.com/eclipse-tractusx/tractus-x-umbrella.git
```

Keep both folders as siblings because the deployment script resolves Umbrella
as `../tractus-x-umbrella` by default:

```text
C:\workspace\tap74-demo\
├── demo\
│   ├── 3-create-umbrella-registry-secret.ps1
│   ├── 4-deploy-umbrella-tap74.ps1
│   ├── 5-tap74-seed-demo.ps1
│   ├── 6-run-manual-tests.ps1
│   ├── charts\tap74-registry\
│   └── values\
└── tractus-x-umbrella\
    └── charts\umbrella\
```

If Umbrella is elsewhere, pass its location explicitly with
`-UmbrellaPath 'C:\path\to\tractus-x-umbrella'`.

## 3. Host-name configuration

The Management APIs are exposed through Kubernetes Ingress. Add the following
entries to the host operating system's `hosts` file. On Windows, edit
`C:\Windows\System32\drivers\etc\hosts` in an elevated editor.

```text
127.0.0.1 dataconsumer-1-controlplane.tx.test
127.0.0.1 dataprovider-controlplane.tx.test
127.0.0.1 dataconsumer-1-dataplane.tx.test
127.0.0.1 dataprovider-dataplane.tx.test
127.0.0.1 ssi-dim-wallet-stub.tx.test
127.0.0.1 bdrs-server.tx.test
127.0.0.1 dataprovider-submodelserver.tx.test
```

The two control-plane host names are required by the seed and runtime-test
scripts. The wallet host is required for DID resolution. The deployment script
also maps that wallet host to the in-cluster Ingress service so pods can resolve
it without a port-forward. The other entries are useful when inspecting the demo
services.

## 4. Start Minikube and Ingress

Create a local cluster with the minimum capacity for this demonstrator:

```powershell
minikube start --cpus=4 --memory=8192 --driver=docker
minikube addons enable ingress
```

Start the tunnel in a separate PowerShell terminal and leave it running for
the entire demo session:

```powershell
minikube tunnel
```

The tunnel exposes the Ingress addresses on `127.0.0.1`; it replaces
`kubectl port-forward`. Do not use port-forwards for the normal demo path.

Confirm the cluster and Ingress controller are ready:

```powershell
kubectl get nodes
kubectl get pods -n ingress-nginx
```

## 5. Deploy published images

From the `demo` folder, run:

```powershell
.\4-deploy-umbrella-tap74.ps1 -ResetVaultWebhookConfigs
```

The script:

- builds the required Helm dependencies from `tractus-x-umbrella`;
- deploys the standalone `tap74-registry` Helm chart;
- deploys the Umbrella provider and consumer EDCs with the TAP 7.4 values
  overlay and the `edc-demo` image from GHCR;
- applies the dataplane-selector adjustment required by the current Umbrella
  runtime.

For private images/artifacts, run the following first, or pass
`-GhcrUsername` and `-GhcrPassword` to script 4:

```powershell
.\3-create-umbrella-registry-secret.ps1 `
  -Username <github-user> `
  -Password <PAT-with-read-packages>
```

Wait until the relevant workloads are ready:

```powershell
kubectl get pods -n umbrella -w
kubectl get ingress -n umbrella
```

### Important: allow dependent services to start

On a fresh cluster, do **not** treat an initial `Error`,
`CrashLoopBackOff`, or a non-zero restart count as a final deployment failure.
The PostgreSQL databases, Vault instances, Registry, and SSI DIM wallet start
independently. A control plane or the wallet can start before its database or
dependent service accepts connections; Kubernetes then restarts it and it
becomes healthy automatically once that dependency is ready.

Keep the watch open until the following workloads show `Running` with all
containers ready (for example, `1/1` or `2/2`). This normally takes a few
minutes on the first deployment:

- `tap74-registry`
- `ssi-dim-wallet-stub` and `wallet-postgres`
- `umbrella-dataprovider-edc-controlplane`
- `umbrella-dataconsumer-1-edc-controlplane`
- the provider and consumer PostgreSQL/Vault pods

It is normal for the **RESTARTS** column to remain greater than zero after a
successful recovery. Continue only when the pods are currently `Running` and
ready; do not run the seed or test scripts while a required pod is still
restarting. To check the final state without the live watch:

```powershell
kubectl get pods -n umbrella
kubectl rollout status deployment/tap74-registry -n umbrella --timeout=10m
kubectl rollout status deployment/ssi-dim-wallet-stub -n umbrella --timeout=10m
kubectl rollout status deployment/umbrella-dataprovider-edc-controlplane -n umbrella --timeout=10m
kubectl rollout status deployment/umbrella-dataconsumer-1-edc-controlplane -n umbrella --timeout=10m
```

If a pod does not become ready after the dependencies are ready, inspect its
latest logs with `kubectl logs -n umbrella deployment/<deployment-name> --tail=200`.

## 6. Seed the imperative demo domain

Policies are reconciled from the catalog. The asset and contract definition are
purposefully not reconciled, so seed them once after the control planes are
ready:

```powershell
.\5-tap74-seed-demo.ps1
```

This creates `tap74-asset-001` on the provider, creates a contract definition
referencing `cx/membership-access/1.0` and `cx/usage-framework/1.0`, and asks
the consumer for the provider catalog.

## 7. Tests

### Automated runtime checks

Run the baseline checks and save evidence:

```powershell
.\6-run-manual-tests.ps1 -CollectEvidence
```

The script verifies:

- both Management APIs are reachable;
- provider and consumer have a non-empty, identical policy state;
- the provider has the seeded asset and at least one contract definition.

To also demonstrate the **locked room** (S8b), run:

```powershell
.\6-run-manual-tests.ps1 -CollectEvidence -IncludeLockedRoomTest
```

It creates a rogue policy through the consumer Management API, waits one
reconciliation interval, and verifies that the reconciler removes it.

### Manual GitOps acceptance scenarios

These scenarios deliberately require a policy-catalog pull request, GitHub
Actions, OCI publication, or a Helm change; they are not performed by the
runtime script.

- **S1:** add a new versioned valid policy through a PR; after merge, verify
  that every control plane creates it.
- **S2:** enable `dataconsumerTwo` with Helm; verify that it converges on its
  first reconciliation cycle.
- **S3:** PR a control policy without `UsagePurpose`; CI must reject it.
- **S4:** PR a policy with a raw `BusinessPartnerNumber`; CI must reject it.
- **S5:** publish a major schema update that invalidates an existing policy;
  verify `invalid` and frozen behavior.
- **S6:** add a new version while retaining V1; verify both versions coexist.
- **S7:** remove a policy still bound by a contract definition; verify it is
  reported as `retained`, then remove/retarget the binding and verify deletion.
- **S8a:** keep one connector offline during an S1 change; restart it and
  verify first-cycle convergence.

## 8. Logs and evidence

Use the Registry log to observe OCI polling and catalog reloads:

```powershell
kubectl logs -n umbrella deployment/tap74-registry -f
```

After a catalog publication, expected messages include:

```text
OCI manifest digest changed for at least one configured image. Reloading catalogs.
Pulling OCI artifact: ghcr.io/adtorales/xregistry-policies:current
OCI policy catalog reload completed. Loaded policy groups: 1, policies: <n>.
```

This shows that the Registry detected a changed OCI digest, downloaded the
current catalog artifact, and made the new desired state available to EDCs.

Use the two control-plane logs to observe reconciliation:

```powershell
kubectl logs -n umbrella deployment/umbrella-dataprovider-edc-controlplane -f
kubectl logs -n umbrella deployment/umbrella-dataconsumer-1-edc-controlplane -f
```

The key message is similar to:

```text
Policy reconciliation completed: created=1, updated=0, deleted=0,
retained=0, unchanged=3, invalid=0, failed=0
```

- `created`: policies newly applied from the Registry.
- `updated`: existing policies whose catalog content changed.
- `deleted`: policies removed from the desired catalog and not bound.
- `retained`: deletion blocked because an imperative contract definition still
  references the policy.
- `unchanged`: already converged policies.
- `invalid`: policies rejected by on-load schema validation and frozen.
- `failed`: isolated reconciliation failures; the next cycle retries them.

For a quick operational overview:

```powershell
kubectl get pods -n umbrella
kubectl get ingress -n umbrella
kubectl logs -n umbrella deployment/tap74-registry --tail=200
```

`6-run-manual-tests.ps1 -CollectEvidence` also writes the Management API
responses, pod listing, and Registry log under `demo\evidence-<timestamp>\`.
