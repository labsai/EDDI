## ✨ feat(org): the org context primitives (organizations Phase 1, package 1) (2026-11-21)

**Repo:** EDDI (`feat/org-1-context`)

### What changed and why

This is the first package of Phase 1 of the organizations plan (`planning/multi-tenancy-plan.md`,
ADR 0001, accepted 2026-11-21). It adds the context the rest of Phase 1 binds and propagates.
**Nothing calls it yet, so behaviour does not change.** Single mode is the only mode, and
`eddi.org.mode=multi` refuses to start.

New package `ai.labs.eddi.engine.org`:

- **`OrgId`.** A validated id (`^[a-z0-9][a-z0-9-]{1,62}$`). `default` is a constant, and the
  reserved ids can never be created.
- **`OrgContext`.** The binding is a `ScopedValue` with a unique epoch per binding, and it
  enforces the transition table in the plan's §6.1: org X → org Y throws, platform → org is
  allowed, and single mode binds only `default`.
  - `require()` fails closed in multi mode.
  - In single mode, an unbound `require()` falls back to `default`, with a WARN once per call
    site and the counter `eddi_org_context_fallback_total{site}`, which is expected to stay 0.
- **`OrgScope.platform(reason, …)`.** The one way to cross orgs, counted per reason in
  `eddi_org_platform_scope_total`.
- **`OrgPropagation`.** Wraps `Runnable`, `Callable`, `Supplier` (as `wrapSupplier`, because
  `Callable`/`Supplier` overloads are ambiguous for lambdas) and `Executor`. A wrapper captures
  the binding when it is created and restores it, including "unbound". It never moves an
  org-bound thread out of its org.
- **Resolution.**
  - `OrgResolver` is the SPI, with `SingleOrgResolver`.
  - `OrgResolution` returns `default` in single mode; in multi mode it consults the resolver,
    with lazy request facts and a per-request memo.
  - `OrgRequest.callBound` covers filters, mappers and routes.
  - `@OrgBound` with `OrgBoundInterceptor` (at `PLATFORM_BEFORE`) binds resource and MCP
    classes. `@PlatformPlane` and `@OrgNeutral` (reason mandatory) mark code that needs no
    binding. None of these is applied yet; that is the next package.
- **`OrgQuotaKeys`.** In single mode the quota key is still `eddi.tenant.default-id`, so no
  counter is re-keyed.
- **`OrgConfig`.** Applies `eddi.org.mode` and `eddi.org.resolver` at startup and wires the
  counters.

Also in this package:

- The new keys are added to `application.properties` and `docs/configuration-reference.md`.
- Both counters are added to `docs/metrics.md` and to a new "Organizations" row on the Full
  Metrics dashboard.

### Review fixes

Two local reviews ran before any caller exists. Everything they found is fixed:

- **Request identity.** The request identity is a request-scoped proxy. Outside a request
  context it throws on first use, so the resolver now gets an anonymous identity without
  touching the proxy.
- **MCP errors.** An `OrgResolutionException` on an MCP `@Tool` becomes the FORBIDDEN tool
  error. Its client message is fixed, and the reason goes only to the server log.
- **Hand-off rules.** A same-org hand-off keeps the thread's epoch. Platform work is refused on
  an org-bound thread. Unbound work keeps an org-bound thread's org but sheds the platform
  plane. A refused hand-off is logged at ERROR.
- **Request facts.** A repeated `X-EDDI-Org` header is refused, and a blank authority reads as
  empty.
- **Configuration.**
  - `eddi.org.resolver` is normalised like `eddi.org.mode`.
  - The mode is read when `OrgContext` loads, so code that runs before startup fails closed in
    multi mode.
  - `OrgConfig` observes at `Integer.MIN_VALUE + 1`, which leaves `MIN_VALUE` to the floor
    guard.
- **Fallback counter.** It names the caller of `OrgQuotaKeys`, not the helper. Capped tags
  overflow into `(overflow)`.

### Tests

77 unit tests in `src/test/java/ai/labs/eddi/engine/org/`. Two mutation checks pass:

- allowing an org-to-org rebinding fails `crossingOrgsThrowsAndTheMessageNamesNoOrg`;
- dropping the rebind in `OrgPropagation` fails 5 propagation tests.

The full unit suite ran 37,519 tests before the review fixes, with one failure: the split
Kubernetes dashboard was stale. It was regenerated with `scripts/sync-monitoring-assets.py`, and
that test now passes.

### What's next

The rest of Phase 1, one package per PR:

- `DataLayoutGuard` and the marker (the floor release);
- applying `@OrgBound` and binding the background entry points;
- `OrgQuotaKeys` at the 22 quota call sites;
- `OrgCollections`/`OrgSql` with stamping and the Postgres `org_id` column;
- the NATS and loopback headers;
- the catalog, the settings classification and the report-mode guards.
