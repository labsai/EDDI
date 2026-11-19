## 📝 docs(planning): the multi-tenancy plan is rewritten as an implementable org design (2026-11-19)

**Repo:** EDDI (`docs/multi-tenancy-plan`)

### What changed and why

**[`planning/multi-tenancy-plan.md`](../../planning/multi-tenancy-plan.md) is replaced.** The
April draft's shared-tenancy design was checked against the code and does not hold:

- It filtered only in `MongoResourceStorage`/`PostgresResourceStorage`. Only 31 files use that
  SPI. 48 files query MongoDB directly and about 52 use a `DataSource`.
- It assumed a `@RequestScoped TenantContext`. Most data access runs without a request:
  executors, `@Scheduled` jobs, NATS consumers, channel inbound.
- It called quota wiring a one-line change. There are 22 `getDefaultTenantId()` call sites.
- It planned `SET LOCAL` for Postgres RLS. The stores run in autocommit, with no `@Transactional`.

The new plan is written so a coding agent can implement it without further context:

- The customer boundary is named **org**, because "tenant" already means three things:
  the vault/variables tenant, the quota tenant and the Quarkus OIDC tenant.
- A `ScopedValue`-based `OrgContext` with explicit hand-offs.
- Enforcement at the persistence boundary: the SPI, `OrgCollections`/`OrgJdbc` for direct
  stores, caches, NATS and RAG naming.
- Postgres RLS as a backstop, with `FORCE ROW LEVEL SECURITY` and separate roles.
- Separate platform and org planes, principal namespacing, per-org KEK and audit key.
- Forced SSRF protection in multi mode.
- A single-tenant mode contract: a constant `default` org, no configuration, the same code path.
- A three-release rolling-upgrade protocol.
- A generated cross-org conformance suite and build guards.
- Eight phases with exit criteria, file inventories (Appendix A) and a data classification
  (Appendix B).

An independent review (a second model, read-only) corrected four points of the first draft:

- Loopback calls do forward the caller's bearer token.
- RLS needs explicit transactions.
- Caffeine caches are built directly in 39 files.
- A filter-rewriting `MongoCollection` view misses aggregations and GridFS.

It also added these leak paths: SSRF off by default, author-chosen vector store names, the
shared NATS keys, the Operator allow-list, one audit key, `originId` import matching, shared
LLM keys, and user memories keyed on `user_id` alone.

**Cross-references updated** in [`planning/saas-connectors-plan.md`](../../planning/saas-connectors-plan.md)
(C9 and three line-number references) and
[`planning/rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md).

### Design decisions

Five decisions are marked LOCK for the Phase 0 ADR:

- org id and principal shape
- storage key shape
- the context contract
- the platform/org plane split
- the trust model for author-supplied infrastructure

### What's next

Phase 0 (ADR and threat model), then Phase 1: the org context everywhere with a constant
`default`, stamped writes, no read changes.
