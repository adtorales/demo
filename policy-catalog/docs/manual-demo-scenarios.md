# TAP 7.4 manual acceptance scenarios

GitHub Actions validates catalog content and publishes OCI artifacts. The following scenarios require a live GHCR package, the TAP 7.4 registry, and the umbrella deployment; they cannot be meaningfully executed in a repository-only CI job.

## Prerequisites

1. `validate.yml` and `publish.yml` are green on `main`.
2. The registry is configured with `xregistry-policies:current` and `xregistry-dataspace-schemas:current`.
3. At least provider and consumer control planes are running with the reconciler enabled.
4. Wait for the registry poll plus the reconciler interval before checking a result.

Ready-to-copy files for S1-S7 are in
[`../manual-test-fixtures/`](../manual-test-fixtures/README.md). They are kept
outside the published catalog so each scenario can be applied independently.

## Acceptance matrix

| Scenario | Manual action | Acceptance evidence |
|---|---|---|
| S1 policy add/change | Add a new, versioned policy file in a PR and merge it. A content change uses a new version file, never an edit to an existing version. | `publish.yml` is green, GHCR `current` digest changes, and all control planes list the new policy with the same content. |
| S2 new EDC | Enable `dataconsumerTwo` through the umbrella Helm values. | On its first cycle it has the full policy set without a manual Management API import. |
| S3 tier-1 rejection | Open a PR with a control policy without `UsagePurpose`. | `validate.yml` fails with `UsagePurpose`; nothing is published. |
| S4 tier-2 rejection | Open a PR containing `BusinessPartnerNumber` as a constraint. | `validate.yml` fails against `mb.company-policy`; nothing is published. |
| S5 schema evolution | Add `mb.company-policy.2.0.json` with a deliberately narrower enum. | `schema-compat.yml` rejects a non-major bump. An approved major schema change may publish even if it invalidates an unchanged legacy policy; the reconciler reports it as `invalid` and leaves it frozen. New or modified policies remain CI-blocking if invalid. |
| S6 parallel policy versions | Negotiate an agreement with V1, then add V2 in a PR and seed a contract definition referencing V2. | V1 and V2 exist in every EDC; the prior agreement is unchanged and new offers use V2. |
| S7 bound deletion | Remove the V1 file in a PR only after it is referenced by a contract definition. Later remove or retarget that contract definition. | The first reconcile reports `retained`; after unbinding, the next reconcile deletes the policy while the agreement snapshot remains readable. |
| S8 heterogeneous fleet | Stop one connector during S1, start it again, then create a rogue policy via the Management API. | The restarted connector converges. The rogue policy disappears on the following reconcile cycle. |

The reconciler summary in the control-plane logs is the primary evidence: `created`, `updated`, `deleted`, `retained`, `unchanged`, `invalid`, and `failed`.
