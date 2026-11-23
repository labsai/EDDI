## ✨ feat(org): bind the org on every REST and MCP entry point (organizations Phase 1, package 3) (2026-11-23)

**Repo:** EDDI (`feat/org-3-request-binding`, stacked on `feat/org-2-layout-guard`)

### What changed and why

Plan §6.2 and §14.2. REST resource methods and MCP tool calls now run with an org binding, so
the stores that read the org in later packages find one there. Code that runs outside a resource
method is not bound yet (see "Not in this package").

- **`@OrgBound` on 102 classes:**
  - 85 REST resource implementations, including the Manager, Welcome and HTML-chat shells.
    They render stored branding through `BrandedShellRenderer`, which is org data;
  - 17 MCP tool classes.

  `OrgBoundInterceptor` binds the request's org around every business method. In single mode
  that is always `default`, so behaviour does not change.
  - The annotation sits on the implementation class, because an interceptor binding on an
    `IRest*` interface is not inherited.
- **`@PlatformPlane` with a reason on 9 classes:**
  - cluster and coordinator admin;
  - update check;
  - build info;
  - tenant quota administration (it addresses any tenant by id);
  - the docs catalog, over REST and as MCP tools and resources (`RestDocs`, `McpDocTools`,
    `McpDocResources`);
  - the MCP version tool, which returns build metadata only.

  `RestLogAdmin` also serves the database logs, which are org data, so it is `@OrgBound`.
- **An org that is already bound is kept.** `OrgRequest.callBound`/`runBound` (and with them
  the interceptor) resolve the request's org only when nothing is bound. In-process callers
  that bound their own org — deployment, upgrades, a background job working on one org's item,
  a resource calling another — keep that org. Resolving again would fail on a thread without a
  request, or in multi mode name a different org. Inside `OrgScope.platform` the request's
  org is still resolved and bound, as platform-to-org is allowed.
- **`@PlatformPlane` on a method opts that method out.** `@Scheduled` methods of `@OrgBound`
  classes run on scheduler threads with no request to resolve from. These now carry
  method-level `@PlatformPlane`, and the interceptor runs exactly that method unbound:
  - `RestConversationStore`: the two cleanup jobs;
  - `RestExportService`: the archive retention sweep.

  The guard allows a method-level `@PlatformPlane` on `@Scheduled` methods only.

  This is not yet enough for multi mode, which this release refuses at startup. ArC also
  intercepts self-invocation, so non-private helpers the jobs call on their own class bind
  again: `retentionHeldForV5Migration`, `countEndedConversationsOlderThan` and
  `permanentlyDeleteEndedConversationLogs`, the last one on a `submitCallable` worker thread.
  Those helpers would try to resolve an org from a request that does not exist. The
  background-binding package moves the jobs off the `@OrgBound` classes and binds them per item
  (plan §7.4).
- **Diagnostics.** `OrgBoundInterceptor` logs each binding at DEBUG
  (`ai.labs.eddi.engine.org.OrgBoundInterceptor`). This costs nothing when DEBUG is off for
  that category.

**Not in this package:**
- the filters, exception mappers, augmentors and routes that run outside resource methods
  (plan Appendix A.12);
- work that outlives a request (`AsyncResponse`, streaming, SSE) and executor hand-offs such as
  `submitCallable`;
- `RestCoordinatorAdmin.replayDeadLetter`: it is platform plane, but replaying an entry must
  bind that entry's org, which the NATS-header package adds;
- anonymous callers of the shells. Binding the Manager, Welcome and HTML-chat shells also
  binds their unauthenticated methods: the Manager's `__auth_config__.js` and static bundles,
  which read no brand. So a multi-mode resolver must resolve an anonymous caller by host
  (plan §7.1). An identity-only resolver would refuse the login page itself. The branded login
  page needs that host resolution anyway; the multi-mode resolver package records it as a
  requirement.

Each follows in its own package.

### Tests

- **`OrgEntryPointBindingTest`.** A build guard that scans `src/main/java`. It fails if:
  - a REST resource, or an MCP class with a tool, resource, resource template or prompt,
    lacks `@OrgBound` or `@PlatformPlane`;
  - a class has both;
  - an `IRest*` interface carries either;
  - the set of platform-plane entry points changes without the pinned list being updated;
  - a `@Scheduled` method of an `@OrgBound` class lacks `@PlatformPlane`;
  - a method-level `@PlatformPlane` sits on anything but a `@Scheduled` method.

  The scan matches generic and package-private resource classes too.

  The plan makes the build guards report-only in Phase 1. This one already fails the build,
  because every entry point is classified. Mutation checks confirm it fails when `@OrgBound` is
  removed from one resource, or `@PlatformPlane` from one scheduled method.
- **`OrgResolutionTest`:**
  - an already bound org is kept without resolving;
  - inside `OrgScope.platform` the org is still resolved;
  - a `@PlatformPlane` method runs unbound.
- **Runtime check on the packaged jar** (MongoDB, DEBUG on the interceptor):
  - `GET /agentstore/agents/descriptors` logged `Org default bound for RestAgentStore.readAgentDescriptors`;
  - `GET /administration/version`, which is platform plane, logged no binding;
  - both answered 200.
