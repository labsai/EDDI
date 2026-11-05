## 📝 docs(connections): Google Workspace recipe — Gmail and Drive as per-user LLM tools (2026-11-05)

**Repo:** EDDI (`docs/google-workspace-tools-recipe`)

### What changed and why

The Google Workspace plan's conversation-tools slice, as configuration only: nothing here is new
engine code. Per-user Google access was the motivating case for connections, but no worked config
showed it end to end, and the prerequisites (verified identity, allowlisted credential endpoints,
Google's publishing-status rules) were scattered or missing.

- **Recipe** — [`docs/agent-configs/google-workspace-tools/`](../agent-configs/google-workspace-tools/),
  an importable agent in the ZIP layout: the `google-workspace` connection
  (`OAUTH2_AUTHORIZATION_CODE`, `PER_USER`, scopes `gmail.readonly` + `gmail.compose` +
  `drive.readonly`), a Gmail httpcalls config (`searchMail`, `readThread`, `createDraft`), a Drive one
  (`searchDrive`, `readDriveFile`), an LLM task with httpcall tools on and built-in tools off, and the
  setup wizard's catch-all rule. Every tool has an `argumentSchema` with `unknownFields: reject` and
  authenticates with `"Authorization": "${connection:google-workspace}"`.
- **[`google-workspace.md`](../google-workspace.md)** (new, in `SUMMARY.md` under Connections) —
  prerequisites (OIDC-verified users and which entry points are `SELF_ASSERTED`; connection settings
  with both Google origins on the credential-endpoint allowlist; the OAuth client; Linked accounts,
  including that only an admin can *start* a link from that page), Google's publishing-status table,
  installation, and the security notes below.
- **[`email-integration.md`](../email-integration.md#gmail-and-google-workspace)** — the Gmail note
  expanded: why an inbound Gmail integration is refused at save (authorization-code grants are
  `PER_USER`, a poll has no verified principal — `ConnectionResolver.requireMailLoginTarget`), that app
  passwords over `BASIC` are not blocked but untested, and what is planned (admin-linked `SERVICE`
  grant, domain-wide delegation).
- **`StrictBoundaryShippedConfigsTest`** now maps `.connection.json` to `ConnectionConfiguration`, so
  the recipe's connection — and any future fixture under `connections/` — is parsed strictly instead
  of being counted as skipped.
- Cross-links from [`connections.md`](../connections.md#see-also) and
  [`agent-config-authoring.md`](../agent-config-authoring.md#reference-implementation).

### Design decisions

- **No send tool; drafts only.** A model that reads mail and can send is an exfiltration channel for
  prompt injection in mail bodies. The page says how to gate a send tool behind
  `hitlConfig.toolApprovals`, and that `gmail.compose` already permits sending — the "no send" is the
  tool set's, not the token's.
- **CR/LF in `to`/`subject` is neutralised in the template, not rejected by the schema.** The argument
  schema check deliberately drops `pattern` (backtracking risk on model text), so a pattern forbidding
  `\r\n` would accept everything. Instead `to` has CR and LF removed with `.replace(…)` and `subject`
  is an RFC 2047 encoded word around `encoder:base64(subject)`; the body is `base64Mime` after the
  header block. Verified against qute-core 3.40.1.1: a raw CR/LF inside a Qute string literal is kept,
  and nested namespace calls work as `str:concat` arguments.
- **`readThread` returns Gmail's base64url body parts as-is** — templating has no decoder; documented
  as a limitation rather than worked around.

### What's next

A real `pattern` (or a narrower "no control characters" keyword) for tool arguments would let such
fields be refused rather than rewritten. Admin-linked `SERVICE` grants and domain-wide delegation for
Gmail as an inbound channel are later slices of the plan.

```decision-log
| 2026-11-05 | Google Workspace recipe ships without a send tool and neutralises header CR/LF in the template | Read + send turns mail-borne prompt injection into exfiltration; the argument schema ignores `pattern`, so a CR/LF ban there would be a no-op | A send tool behind `toolApprovals` by default (still one approval away from mailing attacker-chosen content); a schema `pattern` (silently ignored) |
```
