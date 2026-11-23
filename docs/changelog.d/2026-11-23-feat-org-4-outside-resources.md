## ✨ feat(org): bind the org in filters, mappers and routes outside resource methods (organizations Phase 1, package 4) (2026-11-23)

**Repo:** EDDI (`feat/org-4-outside-resources`, stacked on `feat/org-3-request-binding`)

### What changed and why

Plan §6.2 and Appendix A.12. Some code runs outside any resource method, so outside the
`@OrgBound` binding that package 3 added:
- JAX-RS filters, reader and writer interceptors, body readers and writers, and exception
  mappers;
- `SecurityIdentityAugmentor`s and HTTP authentication mechanisms;
- raw Vert.x routes and `@Observes Filters` filters.

Each one is now classified.

**Six classes bind the request's org** with `OrgRequest.runBound` around the work that touches
org data. In single mode that is always `default`, so behaviour is unchanged.

- `AdminActionAuditFilter`: a response filter that writes administrative actions to the audit
  ledger.
- `DocumentDescriptorFilter`: a response filter that writes resource descriptors. It resolves an
  org only for a successful write request, the only case that writes. If the caller has no org
  (a platform-plane endpoint), it writes nothing and leaves the response alone. It does not
  turn a committed success into a 403.
- `UserDirectoryRecorder`: a request filter that records the caller in the user directory. It
  resolves nothing while the directory is off. A caller without an org is not recorded, and
  the filter does not refuse it: the resource method decides about the request.
  `UserDirectory` itself does not bind. It wraps its write executor task with
  `OrgPropagation.wrap`, so the binding reaches the thread that writes (plan §6.3).
- `BrandedSwaggerUiRoute`, `BrandedOpenApiRoute` and `BrandedShellRoute`: raw Vert.x routes that
  read branding. They already pick the brand by request host, and the host binding of plan §7.1
  will plug in through the same `OrgRequest`.

**31 classes are `@OrgNeutral`**, each with a reason:

- the 18 exception mappers, `OpenAiExceptionMapper` included, which map an exception to a
  response and read no data;
- `OpenAiAuthFilter`, `SpaceHeaderFilter`, `NamelessPrincipalAugmentor`,
  `LegacyPathRewriteFilter`, `RequestArrivalStamp` and `AgentOriginatedRequestFilter`;
- seven that read only deployment config:
  - `HstsHeaderFilter`, `HttpMethodGuard`, `RequestBodyLimitGuard` and
    `UnauthenticatedRequestGuard` (Vert.x filters);
  - `OpenAiPermitModeAuthMechanism`;
  - `StrictConfigurationBodyInterceptor`;
  - `URIMessageBodyProvider`.

**`MetricsScrapeRoute` is `@PlatformPlane`**: metrics are platform plane (plan §9.3, §11.5).

**Correction to the plan's inventory.** `WebhookEventHandler` and `RestWebhookChannel` appear in
Appendix A.12 only because a field `ChannelTargetRouter router` matches the search for
`Router router`. The handler is a service called from the `@OrgBound` webhook resource, and needs
nothing of its own.

**Not in this package:**

- Work that outlives a request: `AsyncResponse`, streaming, SSE.
- The audit ledger's own asynchronous write. `AdminActionAuditFilter` hands entries to it bound.
  Carrying that binding to the ledger's writer is part of the hand-off package.
- The `UserDirectory` fingerprint cache and `BrandedSwaggerUiRoute`'s rendered-page cache. Plan
  §8.6 classifies the caches in its own package.
- **Audit of platform-plane admin actions.** In multi mode, a platform operator without an org
  calling a mutating `RestClusterAdmin`, `RestTenantQuota` or `RestCoordinatorAdmin` method
  fails `AdminActionAuditFilter`'s resolution. The filter logs a WARN and the entry is lost.
  Those actions need a platform-plane audit write; the audit package adds it before multi mode
  can be enabled. Multi mode is refused at startup in this release, so nothing is lost today.

### Tests

- **`OrgEntryPointBindingTest`** gains a guard. Each class of the kinds listed above must do one
  of these:
  - bind with `OrgRequest.runBound`/`callBound`;
  - carry `@OrgNeutral("…")` with a real reason;
  - carry `@PlatformPlane("…")` with a real reason.

  The guard only sees that the call is in the file, not that it wraps the right work; review
  covers that.
- **`DocumentDescriptorFilterTest`.** New tests show:
  - the descriptor is written bound to `default`, and the binding ends with the filter;
  - a read or a failed request resolves no org;
  - a caller without an org keeps its response and gets no descriptor.
- **`UserDirectoryRecorderTest`** (new). The caller is recorded bound to the request org. Nothing
  is resolved while the directory is off. A caller without an org is neither recorded nor
  refused.
- **`UserDirectoryTest`.** A new test runs the directory write on a real other thread and shows
  it is bound to the caller's org.
- **Mutation checks.** Removing the hand-off wrap in `UserDirectory`, or the binding in
  `DocumentDescriptorFilter`, fails the matching test.
- `AdminActionAuditFilterTest` and `DocumentDescriptorFilterTest` construct the filters with
  `OrgTestRequests.singleMode()`.
