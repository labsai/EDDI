## feat(operator): edit configs that hold vault references with PATCH (2026-10-07)

**Repo:** EDDI (`feat/operator-credential-safe-edits`)

### What changed and why

The operator's `llmstore` write grant was unusable for any LLM configuration with an API key: it read the document (`apiKey: ${vault:…}`), sent the whole thing back with `PUT`, and the backend refused the echoed reference. It then had to ask the admin to make the edit by hand. The backend now has `PATCH` (RFC 6902) on `llmstore`, `apicallstore` and `mcpcallsstore` (see the backend entry of this date), and the operator uses it:

- **Allow-list.** `PATCH /llmstore/llms/{id}`, `PATCH /apicallstore/apicalls/{id}` and `PATCH /mcpcallsstore/mcpcalls/{id}` are in `WRITE_ENDPOINTS` (`PATCHABLE_EXTENSION_STORES`). `PUT` stays granted: it still works for documents with no reference.
- **Gate guard.** `gate-guard.ts` reads patch operations. An operation whose `path` or `from` names `toolApprovals`, or whose `value` carries it at any depth (which covers replacing a whole task, `/tasks` or the root), is refused as `carries-gate`. `copy` and `move` are refused outright (`unsupported-patch-op`) — they can carry a stored gate override, or a stored credential reference, somewhere the body does not name. A body that is not a well-formed patch, is truncated, or is unpinned is refused like a `PUT` body. The `llmstore` grant is valid only together with this guard, as before.
- **Approval card.** A patch is listed operation by operation (op, path, value), with the document, the version it applies to and the version it will store; the raw body sits behind a toggle.
- **Prompt.** One delimited section tells the operator to edit with PATCH, never to send back a document it read, which operations exist, that copy/move, a written `${vault:…}` reference and `toolApprovals` are refused, and that a 409 means re-read. It is derived from the granted endpoints.
- **Revision.** `operator-revision.json` is bumped to 3.
- **Snapshot.** `openapi-operations.json` gains the three operations.

### Design decisions

- **No server-side `toolApprovals` restriction.** The REST layer cannot tell the operator from an admin with the same role, so it would also block legitimate API patches; the Manager guard is the control, as it already is for `PUT`.
- **`copy`/`move` are not reasoned about, they are refused.** Neither is needed to edit a configuration.
- **`ragstore` is not patchable** because it is not writable; it takes the same three-part change when it is.

```decision-log
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
```

### Files

[`gate-guard.ts`](../../ui/manager/src/lib/operator/gate-guard.ts), [`json-patch.ts`](../../ui/manager/src/lib/operator/json-patch.ts), [`tool-scopes.ts`](../../ui/manager/src/lib/operator/tool-scopes.ts), [`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts), [`request-preview.tsx`](../../ui/manager/src/components/operator/request-preview.tsx), [`blocked-calls.ts`](../../ui/manager/src/lib/operator/blocked-calls.ts), [`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json), [`operator-write-scope-plan.md`](../../planning/operator-write-scope-plan.md)
