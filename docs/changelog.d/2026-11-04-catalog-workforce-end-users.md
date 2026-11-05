## End-user catalog and Workforce for `eddi-user` (frontend unification, phase 2) (2026-11-04)

**Repo:** EDDI · **Branch:** `feat/catalog-workforce-end-users`

Phase 2 of the frontend-unification plan: a signed-in `eddi-user` can open Workforce, see what they
may use, and chat, without any page answering 403.

**Backend**

- **B1 Catalog.** `GET /catalog/agents` and `GET /catalog/groups` (`IRestCatalog`, `CatalogService`,
  `RestCatalog` in `engine/catalog`), for `eddi-user`, `eddi-editor` and `eddi-admin`. An agent is
  returned when it is READY in production (read from `IAgentFactory`, as `AgentModelResolver` does),
  the caller holds `USE` on it (the existing `ResourceAccessGuard` check, with no new access
  control), and it is **listed**. A group is returned under the same rules, using the group's own
  `USE` check, the one `POST /groups/{id}/conversations` applies. Agent items carry the profile
  fields (name, description, review notice, rating controls), `callerLevel` and `agentId`. Group
  items carry `groupId`, the current version, name, description, style, members and `callerLevel`.
  An entry that cannot be read is skipped without failing the list. See
  [End-User Catalog](../catalog.md).
- **`listed` flag** (`Boolean`, default `false`) on `AgentConfiguration` and
  `AgentGroupConfiguration`. With workspaces enforced, `published` and `internal` visibility count
  as listed too. The Platform Operator (its agent id is read from the `platform.operator` global
  variable) is listed only by its explicit flag. `OperatorRevisionCheck.OPERATOR_VARIABLE` became
  public for that. The flag is part of the JSON document, so ZIP export/import and agent sync carry
  it. Over MCP only a group's flag can be set, with `update_group`; `update_resource` does not
  write the agent document, so an agent is listed from the Manager or over REST.
  `ListedFlagSerializationTest` pins the round trip and the default.
- **B5.** `RestUserMemoryStoreCrossUserTest` runs the real `OwnershipValidator` and pins that
  `eddi-user` A gets 403 on every read and write of user B's memories, before the store is touched.
- `landing-redirect.js` now shortcuts only a remembered `workforce` preference. Everything else
  goes to `/welcome`, which knows the roles (D6).
- Docs: new `docs/catalog.md` (plus SUMMARY), a pointer from `workspaces.md`, and a section in the
  `eddi-admin` Claude Code skill. `openapi-operations.json` gains the two operations.

**Manager (`ui/manager`)**

- Workforce is role-aware (`useWorkforceAccess`). For a user without `eddi-editor` or `eddi-admin`:
  - the dashboard is the catalog (`EndUserCatalog`), and the sidebar, the chat picker
    (`useDeployedAgents`) and the board, thread and history pages (`useWorkforceGroup`) read
    `/catalog/*` instead of `/groupstore` and `/agentstore`;
  - the wizard, team settings and analytics routes redirect (`AuthorRoute`);
  - the authoring actions on team cards, the board's settings link, the agent details panels, the
    Insights links and the mode switcher are hidden.
- **D5:** the Workforce top bar no longer mounts the Operator drawer. Admins get a link to
  `/manage/operator`.
- **D6:** `/welcome` sends an end user straight to `/workforce`, including with `?choose` and with
  a stale `manage` preference.
- The catalog's empty state explains how to list an agent to authors (shown on their dashboard as
  **What end users see**). A plain user gets a neutral sentence.
- A **Listed in the catalog** switch on the agent page, in the Manager's group panel (saves at once),
  and in Workforce team settings.
- i18n: 18 new keys (`Workforce.operatorLink`, `Workforce.catalog.*`, `catalogListing.*`) in all 11
  locales.
- MSW handlers for both endpoints, plus `e2e/workforce-end-user.spec.ts`. It runs as `eddi-user`
  through a new dev-only seam (`lib/mock-roles.ts`, which reads `localStorage["eddi-mock-roles"]`
  only with the mock API active) and fails if any Workforce page requests a store the backend
  refuses that role.
- Platform Operator: the prompt explains `listed` and the catalog, the allow-list gains
  `GET /catalog/agents` and `GET /catalog/groups`, and `operator-revision.json` moves to 53.

**Not done here / next:** phase 3 of the plan (`@eddi/ui` and the agent kind). The catalog has no
paging; it is bounded at 2,000 group descriptors per request.

```decision-log
| 2026-11-04 | D4: catalog listing is opt-in per agent and per group (`listed`, default false); with workspaces enforced, `published`/`internal` visibility also count; the Platform Operator only ever by its explicit flag | Without opt-in, an upgrade would offer the operator, test agents and internal helpers to every signed-in user | Listing every usable deployed agent (R6); visibility only (useless with workspaces off) |
| 2026-11-04 | D5: Workforce links admins to `/manage/operator` instead of mounting the Operator drawer | The operator's allow-list, gate guard and revision are a Manager feature; an end-user app has no business carrying them | Keeping the drawer for admins only (still ships the operator into the end-user app) |
| 2026-11-04 | D6: `/` and `/welcome` send an `eddi-user` without editor/admin to `/workforce`; `landing-redirect.js` no longer jumps straight to `/manage` | The pre-auth redirect script cannot read roles, so only the SPA can decide; a stored `manage` preference now passes through `/welcome` (same bundle, no extra cost) | Reading roles in the redirect script (no token before sign-in) |
| 2026-11-04 | Authors keep reading the stores in Workforce; only users without editor/admin read the catalog | Moving editors onto the catalog would hide every team they built until it is listed | All Workforce reads through the catalog |
```
