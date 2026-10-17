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

### M3.1 — a workflow could reference another space's private configuration

[`RestWorkflowStore`](../../src/main/java/ai/labs/eddi/configs/workflows/rest/RestWorkflowStore.java) now checks every `eddi://` reference a workflow names — each step's `config.uri` and each extension element's `config.uri` (parser dictionaries) — through `ResourceAccessGuard` on create, update (and so `updateResourceUri` and `AgentCascadeService`, which reach it over the loopback REST client with the caller's identity) and duplicate (deep or not). Configuration needs `VIEW`; an `ai.labs.agent`/`ai.labs.group` reference needs `USE`. An update need not re-justify a resource id the stored version already referenced — keeping (or re-versioning) a reference somebody with access made grants nothing new — but a new id must pass, including the prefix trick `updateResourceUri` allowed (a target id that is a prefix of the stored one replaces it with a different resource).

### M3.2 — moderator, facilitator, RECRUIT and duplicate skipped the USE check

- [`RestAgentGroupStore`](../../src/main/java/ai/labs/eddi/configs/groups/rest/RestAgentGroupStore.java)`.requireUseOnMembers` also checks `moderatorAgentId` and `facilitator.agentId`, skips `HUMAN` members (their id is a principal, not an agent), and `duplicateGroup` now runs it. MCP `create_group`, `update_group` and `create_group_from_template`, and `RestGroupTemplates`, all save through this store, so they are covered too.
- [`FacilitatorEngine`](../../src/main/java/ai/labs/eddi/engine/internal/groups/FacilitatorEngine.java) takes the same `(agentId, principal) -> boolean` use check `RecruitAgentTool` has (M-A1) and asks it for the discussion owner before the deployment check, so a refusal does not reveal whether a private agent is deployed. `GroupConversationService` supplies it from a field-injected `ResourceAccessGuard` and the owner's own captured identity (`CallerIdentity.isAdminActingAs`).

### M5 — `platform.*` global variables were editor-writable

[`RestGlobalVariableStore`](../../src/main/java/ai/labs/eddi/configs/variables/rest/RestGlobalVariableStore.java) refuses a non-admin `PUT` or `DELETE` of any key starting with `platform.` (case-insensitive, any tenant) with a JSON `403` — `{"error": …, "reservedPrefix": "platform."}` — and a `[SECURITY]` WARN. `platform.operator` is what the Manager trusts for which agent is the Platform Operator and which prompt an Upgrade re-provisions. Reads are unchanged. No MCP tool writes variables. Every write and delete, refused or not, is already on the audit ledger through `AdminActionAuditFilter` (pinned by a test). Docs: [`global-variables.md`](../global-variables.md#rest-api).
