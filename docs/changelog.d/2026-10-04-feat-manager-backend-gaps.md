## ✨ feat(manager): UI for the 6.6 backend fixes — tool-call caps, typed property values, schedule disable reasons, one seat per agent, admin-action log, honest GDPR result, RAG store isolation (2026-10-04)

**Repo:** EDDI (`feat/manager-backend-gaps`)

### What changed and why

The open backend PRs of the 6.5 review (#944, #945, #946, #947, #950, #952) added fields, refusals and endpoints that the Manager could not show or set. Every new field is optional here and absent unless the author sets it — an older backend's strict configuration parser refuses unknown fields, so the Manager never writes one on its own — and every new response field degrades to "not reported" when an older EDDI does not send it.

- **LLM editor — tool-call caps (#946).** `maxToolCallsPerIteration` (default 20) and `maxToolCallsPerTurn` (default 100) in the Execution section, with the defaults as placeholders, what happens past a cap (`NOT_EXECUTED`, never reaches the approval gate, approval pauses counted), and a warning when `-1`/`0` switches a cap off. [`task-tool-call-limits.tsx`](../../ui/manager/src/components/editors/llm/task-tool-call-limits.tsx)
- **Typed property values (#944).** Property-setter rows and every httpcall/MCP/LLM `preRequest`/`postResponse` instruction get a value-type select: text/template, integer, long integer (`valueLong`), decimal (`valueDouble`), float, boolean; objects and lists are shown and left to the JSON tab. Before, a typed row rendered an **empty** value field, and typing into it wrote `valueString` next to the typed slot — which the engine lets win. Choosing a type now leaves exactly one value slot; a row carrying several says which one runs. Out-of-range integers and longs past 2^53 are flagged. [`property-value-field.tsx`](../../ui/manager/src/components/editors/property-value-field.tsx), [`property-value-utils.ts`](../../ui/manager/src/components/editors/property-value-utils.ts)
- **MCP calls editor (#944).** `maxResponseSizeInBytes` (characters stored in memory, default 2,000,000), shown when the response is saved.
- **Schedules (#947).** A disabled schedule says why: *Agent undeployed* (comes back by itself at the next successful deploy) or *Access revoked* (names the creator and the fix). No reason means a person disabled it — including a schedule a 6.5 undeploy switched off, which needs one manual Enable; the row says so. The `400 {"error":"invalid_schedule","message"}` reason is shown inside the create/edit dialog instead of a generic toast.
- **Groups (#950).** The create dialog, the group wizard and the Workforce wizard no longer offer an agent another member already holds (keyed by `agentId` across member types, as the server keys seats); a repeated member is a pre-save problem, and the server's 400 is shown in the dialog.
- **Import (#950).** The merge preview explains the 6.6 merge scope when workspaces are enforced, and imports now send `X-EDDI-Space` — they bypass `ApiClient` for the zip body and never carried the selected space, so every import landed in the default space whatever the switcher said; with the merge scoped to the importing space that decided which copy a merge updated. Malformed-archive 400 reasons were already surfaced; they are now announced (`role="alert"`).
- **Audit page (#952).** A second tab, *Administrative actions*, over `GET /auditstore/admin-actions`: actor filter (server-side), method and time filters over the loaded rows, *Load older* paging, and explicit 403 (admins only) / 404 (older EDDI) / empty / error states. `?view=admin` deep-links to it. [`admin-actions-view.tsx`](../../ui/manager/src/components/audit/admin-actions-view.tsx)
- **GDPR erasure result (#952).** `auditEntriesRedacted` tile, a plain-language paragraph on what happened to the audit ledger (redacted / kept under `erasure-mode=pseudonymize` / older EDDI that only pseudonymises), what *complete* now means, and a sentence per failed step (`auditRedaction` and others).
- **RAG editor (#945).** A `${…}` store location, a reserved `eddi_kb…` name and a non-plain pgvector table are flagged with the server's reasoning; the storage layout (own store by id vs. 6.5 name-based) is shown with its location; *Move to its own store…* sets `storeNamespace: "id"` only after a warning that the store starts empty, sources re-ingest and `/ingest` documents must be re-ingested. The store placeholders no longer suggest reserved `eddi_kb_…` names.
- **Platform Operator.** The cheat sheet now covers the tool-call caps and `NOT_EXECUTED`, the typed `value*` fields and MCP `maxResponseSizeInBytes`, and one seat per agent in groups. Revision 2 → 3. The admin-actions read is **not** added to the allow-list here: #952 adds it, and `findMissingEndpoints` would refuse operator activation on any backend without that endpoint if this PR merged first.
- **i18n:** 99 new keys in all 11 locales.

### Design decisions

- **Never write a default.** Placeholders show server defaults; a blank field is absent from the JSON. That is what keeps every new field safe against a 6.5 backend's strict parser.
- **Client checks mirror the server, the server stays authoritative.** Duplicate members, RAG location rules and value ranges are checked as the backend checks them, and the backend's own refusal is still rendered where it can arrive.
- **Method/time filters are client-side.** The endpoint filters by actor only; the view says the other filters narrow the loaded rows and stops offering *Load older* once nothing further back can match the time window.

```decision-log
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
```

### Not done

- **#953 (A2A in-flight bound / 503):** no Manager surface. The bound is a server setting (`eddi.a2a.max-concurrent-requests`) observed through metrics, and the Manager has no A2A traffic view to hang it on; a one-off counter would duplicate the Grafana panel.
- **GDPR `resealed` / `keptUnverified`:** computed by the backend but only logged, not returned by `DELETE /admin/gdpr/{userId}`; the result paragraph explains re-signing in words instead.
