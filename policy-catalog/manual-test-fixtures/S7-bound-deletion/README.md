# S7 bound deletion

1. Complete S6 first so `cx/usage-framework/1.0` remains bound by
   `tap74-contract-definition-001` while V2 also exists.
2. In a pull request, remove
   `src/main/xregistry/policies/cx.usage-framework.1.0.json` and merge it.
   The reconciler must report `retained=1`; it must not delete a policy still
   referenced by a contract definition.
3. Retarget or delete `tap74-contract-definition-001` through the provider
   Management API. A replacement contract definition can reference
   `cx/usage-framework/2.0`.
4. After the next reconciliation cycle, the log must report `deleted=1` for
   the former V1 policy.
