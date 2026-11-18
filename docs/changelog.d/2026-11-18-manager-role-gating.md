## Manager: one permission map, route and action gating by role (2026-11-18)

**Repo / branch:** EDDI, `fix/manager-role-gating`.

**What was wrong** (code review I3/D6, live findings OI1, OI3, BR5-BR8): with OIDC on, an `eddi-viewer` met "You don't have permission" under nearly every create button, reached the GDPR page by URL and saw "Reconnecting…" forever on Logs; an `eddi-editor` walked through the five-step agent wizard and only then got "Forbidden" (the setup endpoint is admin-only by design); no route was gated, only the sidebar hid entries; the Keycloak realm listed only some dev ports as web origins.

**What changed**
- `ui/packages/app-kit/src/lib/roles.ts`: the single permission map (`CAPABILITY_ROLES`, `capabilityForScreen`, `mayDo`), derived from the `@RolesAllowed` annotations on the REST interfaces (derivation in the file's comment). `useCan(capability)` in `use-auth.ts`. The sidebar, command palette, dashboard and cluster console read it, as does the new route guard.
- `ui/manager/src/components/layout/route-gate.tsx` + `components/shared/no-access.tsx`: a screen the role cannot use renders a translated "Not available for your role" page naming the role held and the role needed, with a way back (the first screen the role may open, Workforce where it applies). Also guards the studio route.
- Guided agent setup (`setup.guided`, admin): the create dialog disables it for an editor with the reason, the dashboard quick action and the command palette send an editor to Agents (Quick Create), and `/manage/agents/wizard` explains it and links to Quick Create.
- Action gating: connection create, duplicate, delete, save (`connections.manage`) and the "purge ended conversations" section (`conversations.purge`) are hidden for roles that may not use them.
- `eddi-viewer` opens exactly one Manager screen, the cluster console (`cluster.read`, previously hidden from it because the map listed the screen as admin-only); the landing page sends a viewer there. Roles are re-read from the token on every refresh, still from `realm_access.roles` only, because that is the only claim the backend reads (`quarkus.oidc.roles.role-claim-path`).
- `BearerEventSource` stops on a 403 (no retries, `forbidden = true`, `onexhausted` fires) so the Logs page leaves "Reconnecting…".
- `keycloak/eddi-realm.json`: the `eddi-frontend` web origins now also list the Workforce (3002) and Chat UI (5174) dev ports. Keycloak cannot take a wildcard port in `webOrigins`, so a custom port still has to be added (or set `EDDI_PORT`).
- Docs: `docs/security.md` (the `viewer` row and the `eddi-viewer` sentence under Role Tiers said "read-only" / "docs, usage and workspace listings" and omitted the cluster, budget, update-check and conversation reads), `docs/docker.md`, `docs/agent-manager-gui.md`.

**Design decisions**
- A viewer stays in the Manager (cluster console) instead of going to Workforce: Workforce reads `/catalog/*`, which refuses the viewer role.
- D6/OI1 (`diagnose/{conversationId}` readable by a viewer) is left as is: `IRestClusterAdmin`'s Javadoc, `docs/coordinator-admin.md` and `IRestClusterAdminRoleGateTest` all state it deliberately (ids, counts and states, no content), so it is a documentation gap, now closed, not an annotation bug.
- Every capability is granted with auth off and for a token that carries no EDDI role, as before.

**Upgrader notices:** with OIDC on, an editor no longer sees the guided agent wizard offered, and every role now gets the no-access page for screens it cannot use. Nothing changes with auth off. Existing Keycloak volumes keep their old web origins (realm import is one-shot).

**Left:** helm and `k8s/overlays/auth` copies of the realm still carry the old origin list (a chart change needs a version bump); the realm description `"Read-only access to EDDI resources"` of `eddi-viewer` in the three realm files; remaining pages not swept for button-level gating are listed in the PR description.
