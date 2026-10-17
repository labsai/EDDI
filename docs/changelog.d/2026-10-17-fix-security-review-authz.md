## 🔒 fix(security): authorization gaps from the 2026-10-17 review — directory, workflow references, group USE checks, `platform.*` variables, strict group ownership, reserved principals, per-turn USE (2026-10-17)

**Repo:** EDDI (`fix/security-review-authz`)

Findings M2, M3, M5, L2, L3 and L4 of the 2026-10-17 security review. Every change below is a no-op for an administrator, and the workspace ones (M3, L4) are no-ops unless `eddi.workspaces.enabled=true` with authorization on.

### M2 — the user directory could be harvested by any editor

`GET /workspaces/directory` answered a one-character prefix with every matching person's name, username and e-mail, whether or not workspaces were enforced. Now:

- `eddi.workspaces.directory.enabled` has **no default**: unset, it follows workspace enforcement ([`UserDirectory`](../../src/main/java/ai/labs/eddi/engine/security/spaces/directory/UserDirectory.java) reads it as `Optional<Boolean>`, falling back to `WorkspaceSettings.isEnforcing()`). `application.properties` no longer sets it. A deployment without enforced workspaces records no one and suggests no one; shares are taken as typed, which is what an inactive directory always did.
- People are searched from **three** characters (`MIN_USER_QUERY` 1 → 3). Shorter queries return only the caller's own teams, not a 400, so the Manager's share box (which queries from the first keystroke) keeps working unchanged.
- A **non-administrator** is offered only their own teams and the people who share one of them. The restriction runs in the store — new `IUserDirectoryStore.searchWithinTeams` on MongoDB (`teams $in`) and PostgreSQL (`teams && ?`) — so a page is never filled with outsiders and then emptied. `eddi-admin` keeps the full search, known teams included.
- The **e-mail** (`detail`) is returned only to an administrator or to somebody who shares a team with the person — in suggestions and in a resource's grant list (`ResourceSharingService`, via `UserDirectory.detailFor(user, caller, isAdmin)`). Anyone else gets the username.
- Typing an exact username, verified e-mail or `team:<name>` still resolves (`resolveSubject` unchanged): only *suggestion* is narrowed.

Docs: [`workspaces.md`](../workspaces.md#finding-people-the-user-directory), [`configuration-reference.md`](../configuration-reference.md).
