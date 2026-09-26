## 🔐 fix(manager): secret overwrites, unbound approvals, unverified audit shield (2026-09-26)

**Repo:** EDDI (`fix/manager-security-ui`)

Manager findings from the 2026-09-25 UI review that change more than the operator asked for, or claim
more than was checked. Every change has a regression test that fails without it.

### What changed and why

- **High 7 + S1 (frontend) — silent secret overwrite and grant widening.** Rotate POSTed to a `/rotate`
  path EDDI has never had and fell back to a PUT without `allowedAgents`, which a backend before the
  vault-key-safety fix (#836) stores as `["*"]` with a blank description. Rotation is now a PUT carrying
  the grant and description **re-read just before the write** (not the row clicked minutes ago), and a
  key deleted in the meantime is refused instead of re-created. "Add Secret" on the secrets page, the
  picker's create dialog (LLM, channel, API-key fields) and "Add Variable" check a fresh listing and
  refuse an existing name in place (the secrets page offers Rotate instead). The dead `/rotate` MSW mock
  and its OpenAPI-contract exemption are removed.
- **High 8 — the approval queue could approve a pause nobody saw.** Queue rows (rule, group) and the
  inline tool-call panel, plus the conversation-detail banner, now bind the decision to the pause that
  was shown ([`hitl-pause-binding.ts`](../../ui/manager/src/lib/hitl-pause-binding.ts)): the backend's
  `pauseId` when approval-status reports one (#839 — the backend then answers 409 for a stale decision),
  otherwise a fresh approval-status read compared on state, pause kind, start instant and call ids,
  refusing on any mismatch. Both refusals read "this request changed since you opened it".
- **High 9 — green "SIGNED" shield without verification.** The audit banner called no verify endpoint
  and went emerald whenever every entry had an `hmac` field. It now calls `GET /auditstore/verify/…`
  and mirrors the report's `intact()` / `tamperingSuspected()`: green only for verified, red for
  disproven signatures or a broken chain, amber for anything unproven (unsigned, `UNKNOWN_KEY` from
  #836, skipped legacy rows, incomplete chain, duplicates), neutral while loading, on error and without
  a signing key. Failing entries are marked in the timeline. The superseded strings are removed; fr/es/ja
  carried them in English.
- **Share dialog.** A visibility click cascaded over the whole config graph immediately, replacing each
  resource's own visibility. It now proposes, explains, offers `cascade=false`, and applies on confirm.
- **Channels.** Bot token and signing secret are reference-only (`${vault:…}`; `${vars:…}` is refused
  because the router resolves vault references only); the create dialog will not advance and a save is
  refused with a literal. A plaintext value loaded from an older channel is masked in the
  reference-only picker until typed over. The detail draft is seeded once per id and version, so a
  refetch returning changed data no longer overwrites unsaved edits.
- **GDPR.** A 207 partial erasure toasted "deleted successfully"; it now warns.
- **Smaller items.** Vault paths percent-encode tenant and key; the tenant field is trimmed once; secret
  values are stored exactly as typed (no `.trim()`); shared mutations are reset when a dialog opens for
  another row, and the rotate dialog cannot be closed mid-request; approvals track busy state per
  conversation id and use `mutateAsync` (a TanStack mutation remembers only its latest call, which
  re-enabled row 1 while its resume ran and dropped its toast).
- **Lost-key flow (#836).** The secrets danger zone gains "Adopt master key"
  (`POST /secretstore/secrets/admin/adopt-master-key?confirm=true`, acknowledgement required); the
  result lists the tenants to reset, each a one-click switch to that tenant. A backend without the
  endpoint is told it needs no adopt step and to reset the tenant directly.

### Refuted / partial

- **"Every channel's full config, tokens included, is fetched into the query cache."** Partly wrong: the
  list query caches only the projection (`channelType`, `targetCount`, `channelId`), never
  `platformConfig`; a test pins that. The per-row config read itself stays — the descriptor carries none
  of those fields — and with reference-only credentials it no longer carries a plaintext token.

### Compatibility

- Works against current `main` and against #836 / #839: `pauseId` is sent only when approval-status
  reports one; rotation sends the grant explicitly, which #836 treats the same as omitting it; the adopt
  step degrades to a message on a 404/405. No backend change in this branch. No UI surface here depends
  on #838 or #840.
- Behaviour change: saving a channel whose stored token is plaintext is refused until it is replaced by
  a vault reference.

### Follow-ups

- The operator chat (`use-operator-chat.ts`) and the group transcript's streaming approval
  (`streamGroupApproval`) still send unbound decisions — other branches own those files.
- "Add" is checked client-side against a fresh listing; closing the last race needs a create-only
  precondition on the secret and variable PUTs.
- Rotation re-reads the grant but still sends it; on a backend with #836, omitting it would remove the
  remaining read-then-write window.

**Files:** [`secrets.ts`](../../ui/manager/src/lib/api/secrets.ts),
[`secrets.tsx`](../../ui/manager/src/pages/secrets.tsx),
[`approvals.tsx`](../../ui/manager/src/pages/approvals.tsx),
[`audit.tsx`](../../ui/manager/src/pages/audit.tsx),
[`audit-verify.ts`](../../ui/manager/src/lib/api/audit-verify.ts),
[`share-dialog.tsx`](../../ui/manager/src/components/workspaces/share-dialog.tsx),
[`channel-detail.tsx`](../../ui/manager/src/pages/channel-detail.tsx),
[`channel-secrets.ts`](../../ui/manager/src/lib/channel-secrets.ts),
[`gdpr.tsx`](../../ui/manager/src/pages/gdpr.tsx).

```decision-log
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
```
