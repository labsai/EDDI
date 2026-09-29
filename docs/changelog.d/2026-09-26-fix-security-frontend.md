## 🔒 fix(security): close client-side leaks and framing/CSP gaps in the UIs (2026-09-26)

**Repo:** EDDI (`fix/security-frontend`) — backend, `ui/manager/`, `ui/chat/` and config together

### What changed and why

A batch of verified client-side security findings across the two frontends, one
backend masking fix, and the deployment CSP.

**1. Secret-mode input no longer persists or echoes in plaintext (MEDIUM).**
A turn flagged secret (🔒 secret mode, or a password `inputField` whose property
scope was not `secret`) stored its raw text as public `input:initial` step data
and replayed it to the client on reload; only the echoed `input` output was
masked. `Conversation.scrubSecretUserInput` now rewrites `input:initial` /
`input:normalized` and the echoed `input` to the `<secret input>` placeholder in
the turn's `finally` — after the pipeline (so parser / property vaulting still
see the plaintext transiently) and before the audit flush (whose
`inputWasScrubbed()` keys off that placeholder to redact the recorded input). It
also **drops the parser-derived forms** (`expressions:parsed`,
`expressions:matches`, `intents`, `properties:extracted` and the `expressions` /
`intents` outputs): the parser emits `unknown(<token>)` expressions embedding the
normalized secret, which a free-text secret (API key / password) never matches
out of, and which are persisted and shown in the admin raw-step view — scrubbing
only `input:initial` left them behind. Mirrors `PropertySetterTask.dropParsedForms`.
The Manager and Chat UI honour the placeholder so masking survives a reload.
[`Conversation.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
[`conversations.ts`](../../ui/manager/src/lib/api/conversations.ts),
[`snapshot.ts`](../../ui/chat/src/api/snapshot.ts),
[`secrets-vault.md`](../../docs/secrets-vault.md).

**2. Chat UI bearer-token handling hardened (MEDIUM).** A `?token=` bearer is now
stripped from the URL on load (`history.replaceState`) so it cannot linger in
history/Referer/logs, and a `postMessage` handshake from an allow-listed parent
origin (`?tokenOrigin=`) is offered as the safer channel. Residual login-CSRF via
`?token=` is noted for a follow-up PKCE/OIDC flow. `docs/security.md` corrected
(the Chat UI has no `keycloak-js` login).
[`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx),
[`security.md`](../../docs/security.md).

**3. Manager no longer auto-starts a conversation from URL params (MEDIUM).**
`?agentId=` now only *preselects* the agent; starting is an explicit user action.
`?agentName=` is ignored and the display name is resolved from the deployed-agents
list. [`chat-panel.tsx`](../../ui/manager/src/components/chat/chat-panel.tsx).

**4. CSP + framing hardening (LOW).** `form-action 'self'`, `base-uri 'self'` and
`object-src 'none'` added to every CSP (none fall back to `default-src`). A
dedicated `/chat` CSP filter makes the widget embeddable via an operator-set
`frame-ancestors` allow-list (`EDDI_CHAT_FRAME_ANCESTORS`, default `'none'`), and
`X-Frame-Options` moved from a global header onto the default/Swagger filters so
`DENY` holds everywhere except `/chat`.
[`application.properties`](../../src/main/resources/application.properties).

**5. Group-transcript opt-in HTML no longer allows forms / inline style (LOW).**
The DOMPurify HTML path forbids `form`/`input`/`button`/`textarea`/`select` and
the `style` attribute.
[`agent-response-card.tsx`](../../ui/manager/src/components/groups/agent-response-card.tsx).

**6. Markdown images no longer auto-load as live `<img>` (LOW).** All LLM/agent
output markdown renderers in both UIs render images as links via a shared
override, closing zero-click image exfil independent of CSP.
[`markdown-safe.tsx`](../../ui/manager/src/lib/markdown-safe.tsx),
[`MessageBubble.tsx`](../../ui/chat/src/components/MessageBubble.tsx).

**7. Chat UI `?apiServer=` restricted to same-origin relative paths (LOW).**
`sanitizeApiServer` is now an allow-list — the value must be a clean absolute path
(single leading `/`, no control/whitespace/backslash). The previous deny-list was
bypassable with a leading control char (`?apiServer=%09https://attacker`): the
scheme/`//`/`\` tests missed the tab, but the WHATWG URL parser strips it and
`${base}${path}` resolved to the attacker origin, leaking the bearer token.
[`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx).

### Tests

Backend `ConversationSecretInputTest` (persisted `input:initial` is the
placeholder, and a non-vacuous test drives the mocked lifecycle to emit
`unknown(<secret>)` expressions and asserts the derived forms are wiped) and
`CspPolicyTest` (form-action/base-uri/object-src present, per-path XFO, chat
framing default `'none'`); Chat UI `snapshot`, `MessageBubble` and
`ChatWidget.helpers` vitest specs (incl. the `?apiServer=` control-char bypass);
Manager `chat-panel`, `agent-response-card` and `conversations` vitest specs. Key
behavioural tests were mutation-checked (revert → red → restore), including the
parser-derived-forms scrub.

```decision-log
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
```

```regression-note
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
```
