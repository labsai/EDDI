## Manager: one permission map, route and action gating by role (2026-11-18)

**Repo / branch:** EDDI, `fix/manager-role-gating`.

**What was wrong** (code review I3/D6, live findings OI1, OI3, BR5-BR8): with OIDC on, an `eddi-viewer` met "You don't have permission" under nearly every create button, reached the GDPR page by URL and saw "Reconnecting…" forever on Logs; an `eddi-editor` walked through the five-step agent wizard and only then got "Forbidden" (the setup endpoint is admin-only by design); no route was gated, only the sidebar hid entries; the Keycloak realm listed only some dev ports as web origins.

**What changed**
- `ui/packages/app-kit/src/lib/roles.ts`: the single permission map (`CAPABILITY_ROLES`, `capabilityForScreen`, `mayDo`), derived from the `@RolesAllowed` annotations on the REST interfaces (derivation in the file's comment). `useCan(capability)` in `use-auth.ts`. The sidebar, command palette, dashboard and cluster console read it, as does the new route guard.
- `ui/manager/src/components/layout/route-gate.tsx` + `components/shared/no-access.tsx`: a screen the role cannot use renders a translated "Not available for your role" page naming the role held and the role needed, with a way back (the first screen the role may open, Workforce where it applies). Also guards the studio route.
- Guided agent setup (`setup.guided`, admin): the create dialog disables it for an editor with the reason, the dashboard quick action and the command palette send an editor to Agents (Quick Create), and `/manage/agents/wizard` explains it and links to Quick Create.
- Action gating: connection create, duplicate, delete, save (`connections.manage`) and the "purge ended conversations" section (`conversations.purge`) are hidden for roles that may not use them.
- `eddi-viewer` has exactly one Manager screen of its own, the cluster console (`cluster.read`, previously hidden from it because the map listed the screen as admin-only). Every realm account also holds `eddi-user`, so a real viewer token is `[eddi-viewer, eddi-user, default-roles-eddi]`: `homePathForRoles` lets the explicit role win over that default, so a viewer lands on the cluster console and an approver on the approvals inbox (both can still open Workforce as a user); a plain user lands in Workforce. The no-access page offers the same per-role home. Roles are re-read from the token on every refresh, still from `realm_access.roles` only, because that is the only claim the backend reads (`quarkus.oidc.roles.role-claim-path`).
- The group wizard (`/manage/groups/wizard`) and the Workforce team wizard call the admin-only `POST /administration/agents/setup` for every member they create. For a role without `setup.guided` the "create new" option is disabled with the reason and a blocker names the member, so an editor builds teams from existing agents instead of meeting a 403 after earlier members were created.
- Screens open to every role no longer fire admin/editor-only requests: the conversation list and the approvals queue skip the agent and group descriptor stores (rows show ids), and the User Data page shows its admin-only conversations tab to administrators only. A test walks the route table in `app.tsx` and fails when a path is neither gated nor on an explicit open-to-every-role list; path matching is case-insensitive and ignores repeated slashes.
- `BearerEventSource` stops on a 403 (no retries, `forbidden = true`, `onexhausted` fires) so the Logs page leaves "Reconnecting…".
- `keycloak/eddi-realm.json`: the `eddi-frontend` web origins now also list the Workforce (3002) and Chat UI (5174) dev ports. Keycloak cannot take a wildcard port in `webOrigins`, so a custom port still has to be added (or set `EDDI_PORT`).
- Docs: `docs/security.md` (the `viewer` row and the `eddi-viewer` sentence under Role Tiers said "read-only" / "docs, usage and workspace listings" and omitted the cluster, budget, update-check and conversation reads), `docs/docker.md`, `docs/agent-manager-gui.md`.

**Deliberate narrowing:** `eddi-user` cannot open `/manage/chat` or `/manage/operator` although the engine endpoints admit the role, because both screens need the agent list (admin and editor only); Workforce is the user's chat.

**Design decisions**
- A viewer (with the realm's default `eddi-user`) lands on the cluster console rather than Workforce: the viewer role is an explicit grant for operational reading, while `eddi-user` is on every account. Workforce stays reachable.
- D6/OI1 (`diagnose/{conversationId}` readable by a viewer) is left as is: `IRestClusterAdmin`'s Javadoc, `docs/coordinator-admin.md` and `IRestClusterAdminRoleGateTest` all state it deliberately (ids, counts and states, no content), so it is a documentation gap, now closed, not an annotation bug.
- Every capability is granted with auth off and for a token that carries no EDDI role, as before.

**Upgrader notices:** with OIDC on, an editor no longer sees the guided agent wizard offered, and every role now gets the no-access page for screens it cannot use. Nothing changes with auth off. Existing Keycloak volumes keep their old web origins (realm import is one-shot).

**Left:** helm and `k8s/overlays/auth` copies of the realm still carry the old origin list (a chart change needs a version bump); the realm description `"Read-only access to EDDI resources"` of `eddi-viewer` in the three realm files; remaining pages not swept for button-level gating are listed in the PR description.
