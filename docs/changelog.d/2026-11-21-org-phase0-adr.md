## 📝 docs(adr): organizations Phase 0, with spikes, ADR 0001 and a draft threat model (2026-11-21)

**Repo:** EDDI (`docs/org-phase0-adr`)

### What changed and why

Phase 0 of [`planning/multi-tenancy-plan.md`](../../planning/multi-tenancy-plan.md) is the plan's
first stop point: four spikes, an ADR and a threat model, and no production code. It ends here
and waits for approval. This branch also carries the rewritten plan itself (the
`docs/multi-tenancy-plan` commits), so that the ADR and the plan it amends land together.

- **[`docs/adr/0001-organizations.md`](../adr/0001-organizations.md)** (new; `docs/adr/` is new)
  records the spike evidence. It locks plan decisions 1–8, the org bundle format (decision 9)
  and the MongoDB second-layer design (decision 12), and lists 11 deviations from the plan.
- **[`docs/multi-org-security.md`](../multi-org-security.md)** (new) is the draft STRIDE threat
  model per entry point.
- Both pages are added to `docs/SUMMARY.md`, and the plan's §16 Deviations log is filled in.

### What the spikes found

The spike code is throwaway and lives on the local branch `test/org-phase0-spikes`, which is
never pushed.

- **A (Agroal/RLS).** Quarkus registers `AgroalPoolInterceptor` beans for the default
  datasource. The interceptor fires on every acquire, on the caller's thread, and sees a
  `ScopedValue` on virtual threads too. An exception aborts the borrow without leaking the
  connection.
  - **Superusers bypass even `FORCE`d RLS, and the stock compose/Helm Postgres user is one.** The
    role split is therefore required in multi mode.
  - A naive `set_config` per acquire doubles the cost of a one-statement call. A per-connection
    memo brings it back to noise.
- **D (Mongo guard).** A deny-by-default proxy over `MongoCollection` refused every unscoped
  probe, including `ClientSession` and `with*` paths. With the guard off, the decode verifier
  still refused foreign documents.
  - The verifier must be installed per collection, not in the central registry.
  - It must not copy the document: with a copy, scans cost 25–65 % more.
  - Aggregation output carries no `orgId`, so the verifier cannot check it.
  - GridFS cannot sit on the guarded database.
- **B (Keycloak 26.8).**
  - Plain `scope=organization` issued no claim in the password grant; `organization:*` does.
  - Ids appear only with the mapper's `addOrganizationId=true`.
  - A non-member's `organization:<alias>` is dropped silently.
  - Aliases cannot be renamed.
- **C (floor guard).** A `StartupEvent` observer at `Integer.MIN_VALUE` refused a too-new
  layout with exit 1 before anything wrote, on MongoDB and on PostgreSQL.

### What's next

The user reviews ADR 0001. Phase 1 (context and stamping, the floor release) starts only after
approval.
