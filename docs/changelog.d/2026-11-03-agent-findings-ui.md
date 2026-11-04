## 🔒 fix(manager): agent-findings batch for the Manager and Chat UI (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-ui`)

Verified 19 findings from the CodeRabbit agent-findings export against the current code. Most had already been closed by the review-fix PR #1200; the rest are fixed here.

### What changed and why

- **Per-call approval decisions survive a `__proto__` call id.** `ApprovalBanner` built its `toolDecisions` on a plain `{}`; a provider-chosen call id of `__proto__` re-prototyped the object, the reviewer's explicit rejection vanished from the JSON, and the backend read the omitted call as approved. The dictionary now has no prototype.
- **Secret rotation no longer restates the grant.** `rotateSecret` sent the grant and description read just before the PUT, so a concurrent revocation could be put back (last-writer-wins). Both backends keep an omitted grant and description on an update (`VaultSecretProvider.store`, `MongoSecretPersistence.secretWrite`, the PostgreSQL upsert), and the Manager is bundled with the backend that does, so the PUT now carries the value only.
- **Operator gate verification refuses exemptions it cannot judge.** `ToolApprovalGate.addressesOf` matches a call by `http.<method>:<path>`, `<source>:<name>` and the bare name, so an `exempt` entry or `AUTO_APPROVE` rule such as `deployAgent` or `http:*` could un-gate a write while the Manager certified the gate. `gateLooksInstalled` now accepts such an entry only when it fixes a read method (`http.get:`, `http.head:`, `http.options:`) or a non-`http` source literally; anything else is refused (the safe direction: write scope withheld, or a warning on a created agent). `operator-revision.json` is not bumped: neither the system prompt nor the allow-list changed.
- **Hostile-input bounds in the group transcript and diff viewer.** The markdown autolink matcher stops at the next `<` and is bounded (it ran to the end of the text from every opener); the JSON repair of malformed task arrays is a single forward pass that tracks string and escape state (a regex retried from every escaped quote); `reindentJsonText` caps printed indentation at 64 levels (N openers printed N lines of up to N levels) and trims a trailing-comma line by hand instead of with a regex scanning the whole output on every closer.

### Verdicts (19)

FIXED 6; ALREADY_FIXED 11 (by #1200: the diff-viewer budget, JSON pretty-print bounds, markdown destination, indented-run and verification-line bounds, selective export, conversation/agent binding, erasure cache sweep, latest-version matching, operator pause binding, and the every-write-method gate check); ACCEPTED_RISK 2 (`fc72ab76`, `96a089e2`); FALSE_POSITIVE 0; NEEDS_DECISION 0. The URL-token adoption in the chat widget is off unless the operator sets `eddi.chat.url-token.enabled` and is documented as a residual login-CSRF risk; the release check's `Origin` header cannot be suppressed by a cross-origin fetch and the card says so (a same-origin proxy would be a new backend endpoint).

**Files:** [`approval-banner.tsx`](../../ui/manager/src/components/hitl/approval-banner.tsx), [`secrets.ts`](../../ui/manager/src/lib/api/secrets.ts), [`operator.ts`](../../ui/manager/src/lib/api/operator.ts), [`group-utils.ts`](../../ui/manager/src/components/groups/group-utils.ts), [`reindent-json.ts`](../../ui/manager/src/lib/reindent-json.ts)
