## ✨ feat(org): bind the org on every REST and MCP entry point (organizations Phase 1, package 3) (2026-11-23)

**Repo:** EDDI (`feat/org-3-request-binding`, stacked on `feat/org-2-layout-guard`)

### What changed and why

Plan §6.2 and §14.2. Every request now runs with an org binding, so the stores that read the org
in later packages find one.

- **`@OrgBound` on 101 classes:** 82 REST resource implementations and all 19 MCP tool classes.
  `OrgBoundInterceptor` binds the request's org around every business method. In single mode
  that is always `default`, so behaviour does not change.
  - The annotation sits on the implementation class, because an interceptor binding on an
    `IRest*` interface is not inherited.
- **`@PlatformPlane` with a reason on 9 classes:**
  - cluster and coordinator admin;
  - update check;
  - build info;
  - tenant quota administration (it addresses any tenant by id);
  - the docs catalog;
  - the Manager, Welcome and HTML-chat shells, which read no data.

  The Workforce shell reads branding, so it is `@OrgBound`. `RestLogAdmin` also serves the
  database logs, which are org data, so it is `@OrgBound` too.
- **Diagnostics.** `OrgBoundInterceptor` logs each binding at DEBUG
  (`ai.labs.eddi.engine.org.OrgBoundInterceptor`). This costs nothing when DEBUG is off for that category.

**Not in this package:** the 33 filters, exception mappers, augmentors and routes that run
outside resource methods (plan Appendix A.12), and the work that outlives a request
(`AsyncResponse`, streaming, SSE). Both follow in their own packages.

**Known follow-up: `@Scheduled` methods on `@OrgBound` classes.** A runtime check showed that
`RestConversationStore`'s `@Scheduled` cleanup jobs are intercepted too, on scheduler threads. In
single mode they bind `default`, as everything does. In multi mode there is no request to resolve
an org from, so they would fail closed. That leaks nothing, but the jobs would stop working. The
background-binding package moves them to the claim-and-bind-per-item pattern (plan §7.4).
`RestExportService` has a `@Scheduled` method as well.

### Tests

- **`OrgEntryPointBindingTest`.** A build guard that scans `src/main/java`. It fails if:
  - a REST resource or MCP tool class lacks `@OrgBound` or `@PlatformPlane`;
  - a class has both;
  - an `IRest*` interface carries either;
  - the set of platform-plane entry points changes without the pinned list being updated.

  The plan makes the build guards report-only in Phase 1. This one already fails the build,
  because every entry point is classified. A mutation check confirms it fails when `@OrgBound`
  is removed from one resource.
- **Runtime check on the packaged jar** (MongoDB, DEBUG on the interceptor):
  - `GET /agentstore/agents/descriptors` logged `Org default bound for RestAgentStore.readAgentDescriptors`;
  - `GET /administration/version`, which is platform plane, logged no binding;
  - both answered 200.
