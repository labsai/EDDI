## 🐛 fix(groups,backup,secrets): duplicate members, workspace create race, scoped merge import, malformed-archive 400, bound sealed data (2026-10-03)

**Repo:** EDDI (`fix/groups-import-secrets`) — review 2026-10-02 scope P9 (top issue #12; §4.5 `McpGroupTools`; §4.7 groups/backup/secrets rows; harness checks 4–6).

### What changed and why

**Duplicate group members (Medium, live on every backend).** A group listing the same agent twice was accepted, and both "independent" seats spoke through one member conversation — the second panelist read the first one's answer as its own history. The engine keys a seat by `agentId` everywhere (member conversation, display names, vote weights, `participants`, facilitator), so a second seat for one agent cannot exist without re-keying all of it. `AgentGroupStore` now refuses a repeated member (same `agentId` and member type) at save time — `400` naming both positions, on REST create/update, MCP and templates. Stored configs that already hold one keep loading: `AgentGroupStore.read` drops each repeat (first seat wins) and logs a warning, so the discussion runs the agent once and a GET → PUT round trip saves cleanly. Docs: [group-conversations.md](../group-conversations.md#member-roles).

**`GroupWorkspaceStore` survivor race (Medium).** Each concurrent creator inserted its own workspace and they converged afterwards on the lexicographically smallest id — which is not the first insert (ObjectIds are minted client-side, UUIDs are random). A caller whose re-query ran before a second insert landed returned its own document, wrote a backlog task into it, and later reads elected the other one: the task reported success and vanished. Workspaces are now stored under the group id itself with an insert-only `createNew`, so the primary key admits exactly one creator on Mongo and Postgres and every other caller adopts that document. `find` reads the canonical id first and falls back to the `groupId` query for workspaces created by earlier releases; a group id the backend cannot use as a storage id keeps the old path. Container tests race 8 creators × 10 groups on real Mongo and Postgres and require one document and zero lost writes.

**Merge import resolved by global `originId` (Low-Medium, workspaces).** With workspaces enforced, a merge into one workspace matched another workspace's copy of the same archive and wrote into it (or failed its access check). `RestImportService.isMergeTarget` now admits a candidate only if the importer may EDIT it and, when the import writes into a definite space, it lives in that space; the preview, the merge and the live-sync "promoted from this source" lookup all use it. Workspaces off: unchanged.

**Malformed archives answered 500.** Zip-slip was rejected correctly but as a server fault. `ZipArchive` now raises `MalformedArchiveException` for an entry escaping the target directory, a name that is not a path, and corrupt or truncated data (`ZipException`/`EOFException`); `RestImportService` maps it to `400 {"error":"Invalid import archive: …"}`. A configuration file inside the archive that is not valid JSON is a `400` as well (it was `500`).

**Secrets.**
- *One bad grant halted DEK rotation:* `ConnectionGrantResealer` ended its sweep at the first grant whose re-seal threw, stranding every grant after it. It now skips that grant, logs it by connection name, counts it in the new `eddi.vault.reseal.failures{participant=connection-grants}` counter and in the rotation's "at least N rows" — and every other grant still moves.
- *One dekId, two seals:* `OAuthTokenService` sealed access and refresh tokens in two `seal()` calls, each picking the active DEK, but the row records one `dekId`; a rotation committing between them left the refresh token under a generation the row did not name, and that grant could never refresh. New `ISecretProvider.sealAll` seals every field of a row under one generation.
- *Generic `seal()` had no AAD:* new `seal/unseal(tenantId, …, context)` bind the ciphertext with `eddi-sealed-data|v1|tenant|dekId|<len>:context`. Grant tokens are bound to connection, principal and field. Backward compatible: the bound form is tried first, then the unbound one, so every existing grant still opens; new writes are bound, and the next DEK rotation re-seals the rest bound (the `SealedDataRotationParticipant` resealer now takes the context). Migration needs nothing; documented in [secrets-vault.md](../secrets-vault.md#envelope-encryption).
- *Remote sync bodies uncapped:* every JSON response live sync reads from the source went through `BodyHandlers.ofString()`. New `CappedStringBodyHandler` refuses a declared `Content-Length` over `eddi.backup.sync.max-response-bytes` (default 16 MiB) before reading and cancels an undeclared body at the cap; the sync fails with 502.

**`McpGroupTools` returned raw `e.getMessage()`.** New `McpToolUtils.toolFailure`: a failure the caller can fix (4xx, validation `IllegalArgumentException`, not-found/modified, an engine-authored `GroupDiscussionException`) is described; anything else is logged with its stack under an 8-hex reference and the client gets `{"errorCode":"INTERNAL_ERROR","details":{"reference":…}}`.

### Design decisions
- Reject duplicates rather than give each seat its own conversation: every group structure is keyed by `agentId`, and a second seat for one agent would need a seat id threaded through votes, participants, the facilitator, stances and the transcript. A second agent is the supported way to get a second seat.
- Legacy duplicate configs are de-duplicated on read rather than rewritten by a migration: nothing in the database changes until the group is next saved.
- Workspace atomicity uses the primary key both backends already have, rather than a new unique index on a JSON field.
- AAD for sealed data keeps a legacy fallback (same rule as named secrets) instead of a forced re-encryption: no downtime, and a DEK rotation closes the window on demand.

### Not changed
- Manager create-group UI (owned by P13): it should surface the new 400 for a repeated member (and could stop offering an already-picked agent). The Platform Operator prompt was not changed — the 400 message names both positions and says what to do; no endpoint or allow-list change.

```decision-log
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
```
