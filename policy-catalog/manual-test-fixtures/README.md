# TAP 7.4 GitOps manual-test fixtures

These files are deliberately outside `src/main/xregistry`; therefore they are
not validated or packaged automatically. For one scenario at a time, copy only
the stated fixture into the live catalog path, create a pull request, and run
the catalog workflow. Do not merge the negative fixtures.

All scenarios assume the baseline catalog is published first. After a merged
catalog change, wait at least one Registry polling interval (60 seconds) and
one reconciliation cycle (30 seconds) before checking the EDC logs.

| Scenario | Fixture | Action | Expected result |
|---|---|---|---|
| S1 | `S1-add-policy/cx.pcf-use.1.1.json` | Copy to `src/main/xregistry/policies/` and merge. | Every control plane reports `created=1`. |
| S2 | `S2-new-edc/enable-dataconsumer-two.yaml` | Add as an extra Helm values file on the umbrella upgrade. | The new consumer converges all policies on its first cycle. |
| S3 | `S3-tier-1-rejection/cx.invalid-missing-usage-purpose.1.0.json` | Copy to the policies directory in a PR only. | Validation fails because a control policy lacks `UsagePurpose`. |
| S4 | `S4-tier-2-rejection/cx.invalid-raw-bpn.1.0.json` | Copy to the policies directory in a PR only. | Validation fails because `BusinessPartnerNumber` is forbidden. |
| S5 | `S5-schema-evolution/mb.company-policy.1.1.json` then `mb.company-policy.2.0.json` | Use the 1.1 file in a PR to prove rejection; discard it. Use the 2.0 file in a new PR and merge. | Minor change fails schema compatibility; major change publishes and makes policies using `UsagePurpose=analysis` invalid/frozen. |
| S6 | `S6-parallel-policy-version/cx.usage-framework.2.0.json` | Copy to the policies directory and merge; then create a second contract definition that refers to V2. | V1 and V2 coexist. |
| S7 | `S7-bound-deletion/README.md` | Remove the V1 file only while its contract definition still references it; later retarget/delete that definition. | First `retained`, then `deleted`. |

The S3 and S4 fixtures intentionally duplicate the validator's negative test
fixtures so they can be copied directly into a pull request without modifying
the test suite.
