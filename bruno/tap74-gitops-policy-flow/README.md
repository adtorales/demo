# TAP 7.4 manual requests (Bruno)

Open this directory as a Bruno collection after the local demonstrator is
deployed. The default values match `5-tap74-seed-demo.ps1` and
`6-run-manual-tests.ps1`. Change the collection variables when testing another
environment; do not commit real API keys.

## Suggested order

1. Run `01 - Baseline/01 - List provider policies` and `02 - List consumer
   policies` to confirm that reconciliation has completed.
2. Run `02 - Seed demo/01 - Create provider asset`, then `02 - Create provider
   contract definition`. A `409 Conflict` means that the resource already
   exists and is safe to keep.
3. Run `03 - Verify/01 - Provider asset`, `02 - Provider contract definitions`,
   and `03 - Request provider catalog`.
4. For S8b, create the rogue consumer policy, wait for the configured
   reconciler interval plus about 15 seconds, then list consumer policies. The
   rogue policy must be gone.

## GitOps scenarios (S1-S7)

The files to edit and publish are already in
`../../policy-catalog/src/main/xregistry/policies/` and the schema directories
listed in `../../policy-catalog/README.md`. Bruno verifies their runtime
effect, but cannot create a pull request, merge it, or publish the OCI catalog.
Follow `../../policy-catalog/docs/manual-demo-scenarios.md` for those steps.

Expected reconciliation evidence is in the control-plane logs: `created`,
`updated`, `deleted`, `retained`, `unchanged`, `invalid`, and `failed`.
