# TAP 7.4 Policy Catalog

This repository represents the logical `tap74-policy-catalog` repository described in the TAP 7.4 demonstrator documentation.

## Purpose

The catalog repository is the source of truth for:

- versioned policy definitions;
- company schemas;
- dataspace schemas;
- validation fixtures;
- GitHub Actions workflows for validation and packaging.

## Repository Layout

- `src/main/xregistry/policies/`: versioned policy files.
- `src/main/xregistry/schemas/`: company-managed schemas.
- `dataspace/src/main/xregistry/schemas/`: dataspace-managed schemas for the demonstrator.
- `tests/fixtures/invalid/`: negative fixtures used to keep validation expectations explicit.
- `tools/xr-validator/`: Gradle/JVM validator CLI used by repository and CI validation flows.
- `docs/manual-demo-scenarios.md`: environment-dependent acceptance steps for S1-S8.
- `build.gradle.kts`: Gradle entry points matching the documented task names.
- `settings.gradle.kts`: Gradle project definition.
- `gradlew` and `gradlew.bat`: command entry points used by the workflows.
- `.github/workflows/`: GitHub Actions workflows for validation, packaging, and schema compatibility checks.

## Automated CI gates

This repository enforces the following gates in GitHub Actions:

- policy versioning by file;
- a JVM `xr-validator` that validates the xRegistry envelopes and validates each ODRL `policyDefinition` with the same JSON Schema draft (2020-12) used by the reconciler;
- negative fixtures that must fail validation: missing `UsagePurpose` for a control policy and raw `BusinessPartnerNumber`;
- immutable published policy content while allowing a policy file deletion for lifecycle scenario S7;
- schema compatibility checks for added required members and narrowed enums; a breaking change requires a major schema version bump;
- OCI artifact packaging and publication of both `xregistry-policies` and `xregistry-dataspace-schemas`.

The remaining runtime scenarios require GHCR, a deployed registry, and multiple EDCs. They are intentionally manual acceptance tests and are documented in `docs/manual-demo-scenarios.md`.

For a reproducible local runtime, use the separate
[`demo` deployment kit](../demo/README.md). It deploys the published catalog,
Registry, wallet, and Umbrella EDCs. On a fresh cluster, wait for PostgreSQL,
Vault, Registry, and the SSI DIM wallet to become ready before assessing a
reconciliation result. An initial pod restart while a dependency starts is
expected; only a pod that remains non-ready requires investigation.

## Current Example Policies

- `cx.membership-access.1.0.json`: access policy based on active membership and framework agreement.
- `cx.bpn-access.1.0.json`: access policy based on business partner group allowlisting.
- `cx.usage-framework.1.0.json`: baseline usage policy.

## Current Alignment With The Specification

The current file set now mirrors the example structure shown in the TAP 7.4 technical specification more closely:

- `cx.membership-access.1.0.json`
- `cx.bpn-access.1.0.json`
- `cx.usage-framework.1.0.json`
- `mb.company-policy.1.0.json`
- `cx.dataspace-policy.1.0.json`
- `missing-usage-purpose.json`
- `raw-bpn-constraint.json`

## Conventions

- Published policy versions are immutable.
- A policy change must be introduced as a new versioned file.
- Schemas are versioned per file as well.
- Policy file names must match `group.resource.version.json`.
- `policyDefinitionId` and `policyDefinition.@id` must match the policy coordinates.

## Initial Workflows

- `validate.yml`: runs `validateXRegistry` and `semanticTest` on pull requests.
- `publish.yml`: builds the catalog artifacts, publishes them to GHCR with `current` and `v<run-number>` tags, and uploads the packaged artifacts.
- `schema-compat.yml`: runs `schemaCompat` for schema changes on pull requests.

## Runtime evidence

After the Registry and both control planes are ready, wait for a reconciliation
cycle and inspect the provider and consumer control-plane logs:

```powershell
kubectl logs -n umbrella deployment/umbrella-dataprovider-edc-controlplane -f
kubectl logs -n umbrella deployment/umbrella-dataconsumer-1-edc-controlplane -f
```

The reconciliation summary reports `created`, `updated`, `deleted`,
`retained`, `unchanged`, `invalid`, and `failed`. These values are the primary
runtime evidence for a catalog change. See `docs/manual-demo-scenarios.md` for
the expected results and manual evidence for scenarios S1-S8.
