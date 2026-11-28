## 📝 docs(adr): organizations Phase 0, with spikes, ADR 0001 and a draft threat model (2026-11-21)

**Repo:** EDDI (`docs/org-phase0-adr`)

### What changed and why

Phase 0 of [`planning/multi-tenancy-plan.md`](../../planning/multi-tenancy-plan.md) is the plan's
first stop point: four spikes, an ADR and a threat model, and no production code. It ends here
and waits for approval. This branch also carries the rewritten plan itself (the
`docs/multi-tenancy-plan` commits), so that the ADR and the plan it amends land together.

- **[`docs/adr/0001-organizations.md`](../adr/0001-organizations.md)** (new; `docs/adr/` is new)
  records the spike evidence. It locks plan decisions 1–8, the org bundle format (decision 9)
  and the MongoDB second-layer design (decision 12), and lists 13 deviations from the plan. The product owner accepted it the same day, choosing the memo for DV-2. Decision 9 (the
  bundle's record rules) stays a proposal for Phase 6.
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

### Critical review after acceptance

A second, independent review checked every claim against the spike logs and the code, and
found real holes in the MongoDB design. The ADR now adds DV-14 to DV-17:

- **DV-14.** Aggregation pipelines are checked against a stage allow-list, recursively into
  `$facet`, `$lookup` and `$unionWith`. `$out`/`$merge` into org collections are refused. Before
  this, a scoped `$match` followed by `$merge` could have written across orgs.
- **DV-15.** `MongoDatabase` methods are classified. `runCommand`, `listCollections` and
  `createCollection` are platform-only.
- **DV-16.** Every returned iterable is wrapped, `DistinctIterable.filter()` included, and
  non-simple collations are refused.
- **DV-17.** The verifier covers every document class. `orgId` must be a string, which the
  validator enforces.

Existing rules were tightened:

- **DV-2 (memo).** The entry is removed on any non-autocommit acquire or failed `set_config`.
- **DV-3 (JTA).** Covers `QuarkusTransaction` and `UserTransaction` as well as
  `@Transactional`.
- **DV-11 (floor guard).** Reads with primary read preference and majority read concern, and
  keeps `Integer.MIN_VALUE` to itself.
- **Decision 9 (bundle).** Gains:
  - a checksum for `sealed.bin`;
  - an expiry and target binding;
  - registered export keys;
  - archive-entry validation;
  - routing records (hostnames, `/v1` keys, integrations, Keycloak orgs) re-created at the
    target, never imported as trusted;
  - attachments stored as `BYTEA` on Postgres, not large objects.

Evidence corrections:

- The Mongo overhead table now gives the range over both runs. The no-copy verifier is noisy
  (up to +33 % on a loopback Jackson scan), so the ADR no longer calls it "within noise", and
  the 3 % budget is left to the Phase 5 load test.
- `countDocuments` has 40 call sites, not about 45.
- Results that were only printed to the console are copied into the spike branch.

The threat model now lists GridFS, the JTA path and bundle import as single-layer spots, and no
longer says the floor guard stops pre-floor binaries.

The plan's §8.4, §8.5a, §14.2 and §16 point at the deviations that supersede them, and their
statuses read *locked*. `planning/saas-connectors-plan.md` no longer describes the plan's
withdrawn draft (an explicit `OrgId` parameter and `OrgJdbc`).

### Third review

A review of the fold-in commit found more gaps, now closed in the ADR and the plan:

- **Collation.** A collection's **default** collation is refused too, not only one set per
  operation.
- **Database methods.** The ones the code calls today (`listCollectionNames`, `getName`,
  `with*`) are classified.
- **GridFS facade.** It mints file ids, and wraps `GridFSFindIterable` and every `with*`.
- **Consistency.** `watch`/`bulkWrite` are refused in every place they are mentioned, and
  `$out`/`$merge` are refused whatever they target. The omitted pipeline stages are explained.
- **Bundle.** Gains archive size and ratio caps, refusal of id collisions, and single import
  per `bundleId`.
- **JTA guard (DV-3).** Covers all of `jakarta.transaction.*`.
- **GUC guard (DV-2).** Also forbids `RESET`, `DISCARD`, `SET ROLE` and
  `SET SESSION AUTHORIZATION`.
- **Validator.** Requires `orgId` as a string everywhere the plan mentions it.

### CodeRabbit review

CodeRabbit's review of the PR led to fixes in the plan, the threat model and the plan's
changelog fragment. One finding added a rule to the accepted ADR, with the product owner's
approval: **imported org memberships are inactive until a platform admin confirms them at the
target** (decision 9). Org roles come from `OrgMembership`, so without the rule a signed bundle
could grant a role the target never approved.

### What's next

ADR 0001 is accepted. Phase 1 (context and stamping, the floor release) is next, one package
per PR.
