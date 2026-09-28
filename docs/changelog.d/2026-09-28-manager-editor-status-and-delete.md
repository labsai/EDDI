## 🐛 fix(manager): "Offline" pill and owner-only Delete on detail pages for non-admins (2026-09-28)

**Repo:** EDDI (`fix/manager-editor-status-and-delete`)

Two defects found by running `main` with workspaces enforced
(`eddi.workspaces.enabled=true`, Keycloak) and signing in as a user holding only
`eddi-editor`.

### 1. The header status pill always read "Offline"

`usePlatformStatus` polls `GET /administration/logs/instance-id`, which is
`@RolesAllowed("eddi-admin")`. For everyone else it answers 403, and the hook
treated every non-2xx as unreachable. An editor therefore saw a red "Offline"
pill for the whole session while using a working backend.

**Decision:** 401/403 now count as online, with no instance ID. We kept the
admin probe and did not switch to `/q/health` because:

- a 401 or 403 is still a response from EDDI, and that is the question the pill
  answers ("can I reach the platform?"). A network error, a timeout or any other
  non-2xx still reads as offline.
- `/q/*` is not in the Vite dev-server proxy, so under `npm run dev` it would
  get the SPA's `index.html` with a 200 and report online even with the backend
  down. `/q/*` is also often blocked at the ingress.
- `/q/health/ready` goes DOWN (503) when a dependency fails. That would turn a
  degraded database into "Offline", which is a different statement.
- admins keep the instance ID in the popover. Nobody else gets it, and the
  popover already hides that row when it is null.

### 2. The detail pages offered Delete to EDIT grantees

The agents, workflows and resources **lists** already hide Delete and Share
unless `callerLevel` includes `OWN` (`accessFor` in `lib/access.ts`). The
matching **detail** pages (`/manage/agentview/:id`, `/manage/workflowview/:id`,
`/manage/resources/:type/:id`) did not check the level at all. Someone with
only an EDIT grant saw an enabled Delete button, and clicking it returned a 403.
None of the three pages has a Share entry point.

- New `accessForDetail(descriptors, id, workspacesEnforced)` in `lib/access.ts` reads the level
  from the version descriptors each page already loads. It only uses the
  descriptor whose URI resolves to this `id`, because `filter=` is a text match
  and can also return a resource whose id merely contains this one.
- **A missing descriptor is not a missing level.** If no descriptor for this
  `id` comes back, the lookup is empty or partial. That counts as unrestricted
  only when `/workspaces` reports enforcement off, because unmigrated data there
  can legitimately return no descriptors and owners must keep Delete. With
  enforcement on, before `/workspaces` has answered, or when it **failed**,
  nothing is offered. The pages read the new `useSpaces().enforcement`, which
  stays `undefined` on a failure. `enabled` folds a failure into `false`, so
  reading it would turn a 502 into "enforcement off". An older backend's 404
  still counts as off. (Both points were raised by CodeRabbit on PR #879.)
- **Nothing is offered while the descriptors load.** Treating "not loaded yet"
  as unrestricted would show Delete briefly to an EDIT grantee, long enough to
  click it. Once the descriptors arrive the normal rule applies: a missing
  level means unrestricted, so deployments without workspaces behave as before.
- `useAgentVersions` now also returns `resource` and `callerLevel` in each
  entry, so the agent page can use the same helper.

### Tests

- `use-platform-status.test.tsx`: a 401 or 403 reads online with a null
  instance ID. A network error reads offline.
- `access.test.ts`: `accessForDetail` offers nothing while loading, ignores a
  descriptor for a lookalike id, and treats a missing level as unrestricted. A
  missing descriptor offers nothing under enforcement or before `/workspaces`
  answers, and stays unrestricted with workspaces off.
- New `detail-page-owner-actions.test.tsx`: all three detail pages hide Delete
  for EDIT and show it for OWN. The agent page also shows it when the backend
  sends no level. With an empty descriptor lookup, it hides Delete under
  enforcement and when `/workspaces` fails, and shows it when workspaces are off
  or `/workspaces` returns 404. The negative cases wait until both the
  descriptor and `/workspaces` queries have settled, not a fixed delay. Each test waits for content that renders at both levels
  before checking that Delete is absent.
- `config-editor.test.tsx` and `resources.test.tsx` had checked for Delete
  synchronously on the first render. They now wait for it, since it appears
  once the level is known.
- Mutation-checked: with the gates and the 401/403 branch removed, all five new
  behavioural tests fail. With the empty-lookup case reading as unrestricted
  again, the tests covering it fail. With the pages reading `enabled` again, the
  `/workspaces`-failure test fails.

**Files:** [`use-platform-status.ts`](../../ui/manager/src/hooks/use-platform-status.ts),
[`access.ts`](../../ui/manager/src/lib/access.ts),
[`use-agents.ts`](../../ui/manager/src/hooks/use-agents.ts),
[`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx),
[`workflow-detail.tsx`](../../ui/manager/src/pages/workflow-detail.tsx),
[`resource-detail.tsx`](../../ui/manager/src/pages/resource-detail.tsx)
