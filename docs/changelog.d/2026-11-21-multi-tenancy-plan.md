## 📝 docs(planning): the multi-tenancy plan is rewritten as an implementable org design (2026-11-21)

**Repo:** EDDI (`docs/multi-tenancy-plan`)

### What changed and why

**[`planning/multi-tenancy-plan.md`](../../planning/multi-tenancy-plan.md) is replaced.** The
April draft's shared-tenancy design does not hold against the code:

- **Storage.** It filtered only in the generic storage SPI. Only 31 files use that SPI; 48
  query MongoDB directly and 51 use a `DataSource`.
- **Request scope.** It assumed a `@RequestScoped TenantContext`. Most data access runs
  without a request: executors, `@Scheduled` jobs, NATS consumers, channel inbound, async
  responses.
- **Quotas.** It called quota wiring a one-line change. There are 22 call sites.
- **RLS.** It planned `SET LOCAL` RLS, but the stores run in autocommit.

The new plan is written so that a coding agent can execute it phase by phase without further
context. It opens with an execution brief, marks every stop point, and keeps a deviations log.
Its core decisions:

- **Naming.** The customer boundary is called an **org**. "Tenant" already means three things.
- **Org context.** A `ScopedValue`-based `OrgContext` with a binding transition table and
  explicit hand-offs. It covers resource methods, MCP, filters, exception mappers, Vert.x
  routes and work done after a response returns.
- **Enforcement.** Org isolation is enforced at the data layer through `OrgCollections` /
  `OrgSql` helpers. Caches, in-memory registries (deployed agents, coordinator queues, SSE),
  NATS (signature v2 covers the org header), RAG naming and export archives are all scoped.
- **Single mode stays free.** Before the enablement **marker**, writes stamp `default` and
  reads get no predicate. Backfill, index and unique-key switches, RLS and per-org keys all
  happen in an explicit enablement step that runs while still in single mode. It uses a
  two-step marker with a cluster presence barrier. A **floor release** with
  `DataLayoutGuard` prevents an unsafe rollback.
- **Postgres RLS** is the backstop, set per connection by an Agroal pool interceptor. A
  connection never spans a binding change.
- **Roles.** Platform roles are separate client roles. Org roles and teams come from an
  EDDI-side `OrgMembership`, because Keycloak realm roles and groups are global.
- **Entry points.** Anonymous entry points (chat, A2A, branding, auth config) are
  host-bound. Webhooks route by integration id; `/v1` uses per-org keys.
- **Background jobs** claim work across orgs and bind the org per item, with per-org
  fairness caps.
- **Per-customer settings** move to `OrgSettings`.
- **Tests.** A generated cross-org conformance suite, build guards and mutation checks.
- **Rollout.** Eight phases with exit criteria, file inventories (Appendix A) and a data
  classification with the unique-key inventory (Appendix B).

Three independent reviews (by a second model, read-only, each checked against the code)
shaped this revision. They found the enablement deadlock, the CDI decorator that cannot wrap
Agroal, the unsigned NATS headers, the realm-global roles and groups, the missing floor
release, connections reused across bindings, misclassified archives and tombstones, and
per-customer settings held deployment-wide.

**Cross-references updated** in
[`planning/saas-connectors-plan.md`](../../planning/saas-connectors-plan.md) and
[`planning/rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md).

### Design decisions

These are LOCK items for the Phase 0 ADR:

- org id format and principal shape;
- storage key shape;
- the context contract;
- marker, floor and enablement;
- planes, roles and teams;
- the trust model for author-supplied infrastructure;
- IdP binding;
- key hierarchy and AAD.

The product owner decided three open points:

- **Datastores.** MongoDB and PostgreSQL are both fully supported in the pooled tier. On
  MongoDB, which has no row-level security, the second layer is a guarded collection boundary
  that refuses unscoped commands, plus decode-time read verification.
- **Moving an org** is an explicit platform-admin action, with no automatic criteria. Every
  move must be easy and documented: own instance ↔ shared cluster, pooled ↔ dedicated,
  cluster ↔ cluster, and MongoDB ↔ PostgreSQL. All of them share one versioned org bundle
  and the same flow (preflight, resumable copy, freeze, verify, cutover, rollback). Each
  move has a runbook in `docs/org-migrations.md` that is tested by someone performing it
  from the doc alone.
- **Break-glass** is granted by one platform admin with a reason and a time limit; the org's
  admins are notified, and four-eyes approval can be configured.

### What's next

Phase 0: four spikes (the Agroal interceptor, the MongoDB guarded wrapper and read verifier, Keycloak Organizations claims, the floor
guard), then the ADR and the threat model for approval.
