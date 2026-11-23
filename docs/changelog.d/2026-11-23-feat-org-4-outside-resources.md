## ✨ feat(org): bind the org in filters, mappers and routes outside resource methods (organizations Phase 1, package 4) (2026-11-23)

**Repo:** EDDI (`feat/org-4-outside-resources`, stacked on `feat/org-3-request-binding`)

### What changed and why

Plan §6.2 and Appendix A.12. JAX-RS filters, exception mappers, `SecurityIdentityAugmentor`s and
raw Vert.x routes run outside any resource method. So they run outside the `@OrgBound` binding
that package 3 added. Each one is now classified.

**Seven classes bind the request's org** with `OrgRequest.runBound` around the work that touches
org data. In single mode that is always `default`, so behaviour is unchanged.

- `AdminActionAuditFilter`: a response filter that writes administrative actions to the audit
  ledger.
- `DocumentDescriptorFilter`: a response filter that writes resource descriptors.
- `UserDirectoryRecorder`: a request filter that records the caller in the user directory.
  `UserDirectory` now wraps its write executor task with `OrgPropagation.wrap`, so the binding
  reaches the thread that writes (plan §6.3).
- `BrandedSwaggerUiRoute`, `BrandedOpenApiRoute` and `BrandedShellRoute`: raw Vert.x routes that
  read branding. They already pick the brand by request host, and the host binding of plan §7.1
  will plug in through the same `OrgRequest`.

**23 classes are `@OrgNeutral`**, each with a reason:

- the 18 exception mappers (19 in total, counting `OpenAiExceptionMapper`), which map an
  exception to a response and read no data;
- `OpenAiAuthFilter`;
- `SpaceHeaderFilter`;
- `NamelessPrincipalAugmentor`;
- `LegacyPathRewriteFilter`;
- `RequestArrivalStamp`;
- `AgentOriginatedRequestFilter`.

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

### Tests

- **`OrgEntryPointBindingTest`** gains a fourth guard. Every filter, mapper, augmentor and route
  must either bind with `OrgRequest.runBound`/`callBound`, or carry `@OrgNeutral("…")` or
  `@PlatformPlane("…")` with a real reason.
- **`DocumentDescriptorFilterTest`.** A new test shows the descriptor is written bound to
  `default`, and that the binding ends with the filter.
- **`UserDirectoryTest`.** A new test runs the directory write on a real other thread and shows
  it is bound to the caller's org.
- **Mutation checks.** Removing the hand-off wrap in `UserDirectory`, or the binding in
  `DocumentDescriptorFilter`, fails the matching test.
- `AdminActionAuditFilterTest` and `DocumentDescriptorFilterTest` construct the filters with
  `OrgTestRequests.singleMode()`.
