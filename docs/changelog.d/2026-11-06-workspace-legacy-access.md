## 🔒 fix(workspaces): legacy resources grant at most EDIT; settings load once and revalidate (2026-11-06)

**Repo:** EDDI (`fix/workspace-legacy-access`)

### What changed and why

- **A resource that predates ownership no longer makes every caller its owner.**
  `DescriptorAccess.effectiveLevel` gave `OWN` to any caller for an unowned descriptor
  under `legacy-visibility=shared`. `OWN` gates delete, share, revoke, visibility and
  move, so any user could publish a colleague's pre-upgrade agent to anonymous callers,
  or move it into `user:<them>` — after which it was owned, and everyone else lost
  access. With enforcement off, the same answer let any user read the grant list of a
  legacy resource. The legacy contribution is now `EDIT`: everyone can still read,
  use, edit and deploy pre-existing resources, so an upgrade hides nothing, but the
  `OWN` acts are admin-only until an owner is assigned with
  `PUT /descriptorstore/descriptors/{id}/shares/owner`. Explicit grants on a legacy
  resource still count in full. This matches what a resource with no descriptor at all
  already got (`ResourceAccessGuard.requireLegacyFallback`) and what
  [Workspaces](../workspaces.md#resources-that-predate-ownership) promised.
- **The workspace settings cache no longer makes access checks wait for the store.**
  `WorkspaceSettings` re-read the stored settings under a lock when its 5-second cache
  expired, so every request whose access check asked `admitsLegacy()` queued behind a
  database read with no timeout — at every expiry, and for the whole of an outage. It is
  now stale-while-revalidate: one caller re-reads, everyone else is served the last
  values, and a write adopted meanwhile is not overwritten by the older read. Only the
  very first read, with nothing to serve, is still waited for. No executor is involved.
- **A corrupt stored `legacy-visibility` is validated once, at load.** It used to be
  re-validated, and logged at `WARN`, on every access check. It is now treated as unset
  when read, logged once per distinct bad value, and `GET /workspaces/settings` reports
  it as `DEFAULT` — the default that actually applies.
- **`PUT /workspaces/settings` stamps `updatedAt` to the millisecond.** Mongo keeps
  milliseconds and Postgres microseconds, so the value this instance adopted differed
  from what a replica read back.
- **New `WorkspaceSettingsStoreParityTest`** runs the Mongo and Postgres settings stores
  through the same cases: unknown tenant, full round trip, unset fields read back as
  null, writing null clears a stored value (how `PUT` unsets a setting), tenant
  isolation, timestamp precision. Neither store had a test before.

### Behaviour change for administrators of upgraded deployments

With `eddi.workspaces.enabled=true`, a non-admin can no longer delete, share, publish,
move or read the grants of a resource created before ownership was recorded; the
Manager stops offering those actions on it (it reports `callerLevel: EDIT`). Assign an
owner to each such resource you want someone other than an administrator to manage.
Nothing changes for administrators, or while enforcement is off apart from the grant
list.

**Files:** [`DescriptorAccess.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/DescriptorAccess.java),
[`WorkspaceSettings.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/WorkspaceSettings.java),
[`RestWorkspaces.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/rest/RestWorkspaces.java),
[`workspaces.md`](../workspaces.md); tests in `DescriptorAccessTest`, `ResourceAccessGuardTest`,
`RuntimeWorkspaceSettingsTest` and the new `WorkspaceSettingsStoreParityTest`.
