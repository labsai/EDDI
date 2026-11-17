## ✨ feat(ui): "What I remember about you", composer modes, and the `/chat/embed.js` launcher (2026-11-06)

**Repo:** EDDI · **Branch:** `feat/workforce-memory-composer-embed` (on `feat/conversation-search-tool-ui`)

Phase 8 (Tier 2) of the frontend unification plan (`planning/frontend-unification-plan.md` §5): T2.4, T2.5 and T2.9. Frontend, plus one literal JAX-RS path for the launcher. T2.1/T2.2/T2.6/T2.7 and the per-user event stream (B12) are other branches.

### What changed and why

- **"What I remember about you" (T2.4).** A Workforce page at `/workforce/memory` ([`workforce-memory.tsx`](../../ui/workforce/src/pages/workforce/workforce-memory.tsx)), linked as **My memory** from the sidebar footer and from the ⌘K switcher. It lists the signed-in person's own persistent memory, newest first, with the agent that saved each entry (named from the catalog, else its id; a legacy entry without one says so) and its visibility in plain words (*Private*, *Team* with the team's name, *All agents*). It has a search box, **Forget** for one entry, and **Forget everything** behind a confirm that names the consequence: every agent and team, gone from new conversations, cannot be undone, conversations kept. It uses the existing REST API only (`GET /usermemorystore/memories/{userId}`, `DELETE /entry/{id}`, `DELETE /{userId}`), with the token's `preferred_username` as the user id. The backend's `OwnershipValidator` answers 403 for anyone else's, so the page can only show a person their own entries. The backend lets a person delete any of their entries, shared ones included, so the page offers that and says that a shared entry goes for every agent. The reserved `_gdpr_` rows cannot be deleted (400 on the single delete, kept by delete-all), so they are counted and named in a note, not listed. A stored value is a text child, never markdown or HTML. Row shaping is pure, in [`memory-entries.ts`](../../ui/workforce/src/lib/memory-entries.ts). The user-memory API and hooks moved from the Manager to `@eddi/app-kit` with `git mv`, and the Manager's admin tab now imports them from there. The MSW handlers already covered the three endpoints.
- **Composer modes (T2.5).** [`composer-mode.ts`](../../ui/packages/conversation/src/controller/composer-mode.ts) in `@eddi/conversation` defines the composer's three modes once: the field an agent asked for (`inputField`) wins over the user's lock (secret mode), and the lock wins over text. Only a `password` field (or one with no `subType`, in any case) is a secret. `email` and `text` are ordinary turns of that type. Quick replies show beside text and a requested field, and are hidden under the lock. The widget's `Composer`/`SecretInput` and `@eddi/app-kit`'s `ChatPanel`/`SecretInputField` (Manager, Workforce, test drawer) all follow it. Fixes that came with it:
  - **A secret was echoed in the clear.** The text draft and the masked value were one state in both composers, so turning the lock off showed the typed secret in the textarea. They are now separate: leaving secret mode drops the masked value, the text draft typed before survives the trip, and a secret the server refused (409) returns to the masked field.
  - **The app-kit field was wrong.** It masked and vaulted every `subType`, so an email address was sent as a secret. It also trimmed passwords. It now sends what the shared rule says, and gained the widget's **Type a message instead**.
- **The embed launcher (T2.9).** `<script src="https://eddi.example.com/chat/embed.js" data-agent-id="…" defer>` draws a floating button that opens the `/chat` widget in a panel ([`launcher.ts`](../../ui/chat/src/embed/launcher.ts)). It is framework-free and imports nothing from the widget. It is its own Vite build (`vite.embed.config.ts` → `dist/scripts/js/chat-ui-embed.js`), which the existing Maven `chat-ui*.js` copy, exclude and clean patterns already cover. EDDI serves it from a literal path, `RestHtmlChatResource#fetchEmbedScript` (beside the SPA catch-all, which would otherwise answer with HTML under `nosniff`), as JavaScript with `Cache-Control: public, max-age=3600`, and answers 404 when the jar has no UI.
  - **Settings.** `data-` attributes set the agent id, environment, position, colour (the widget's colour rule), label (a built-in "Chat" per widget locale), open-by-default (not on a phone), language, theme and a token provider.
  - **Token hand-off.** It relays the widget's existing protocol. `tokenOrigin` is set only when the host gives a provider, since the widget would otherwise wait five seconds for a token. A message is answered only from its own iframe's window and EDDI's origin, and the token is posted to that exact origin.
  - **Isolation.** The button and panel live in a shadow root. The launcher never touches cookies or storage and uses no `innerHTML` or `eval`.
  - **Accessibility.** The button is a labelled `<button>` with `aria-expanded`. The panel is a labelled dialog: focus moves to it, Tab is kept inside, Esc or the close button closes it, and focus returns to the button. On a phone the panel is full-screen with safe areas, and there is no animation under reduced motion.
  - **Budget.** `check-bundle-budget.mjs` learned `scripts` entries, and the launcher has its own budget of 5 KB.
- **Strings.** `Workforce.memory.*` (36 keys) in Workforce's eleven locales. T2.5 adds none: the app-kit field reuses `conversation.input.typeInstead`. The launcher's default label and close label are a small table in the script, in the widget's eleven locales.
- **Platform Operator.** The prompt now covers the launcher (what to set, the two settings it fails without: `eddi.chat.frame-ancestors` and the host page's CSP), "My memory" (what a user may delete, the `_gdpr_` rows), and the `inputField` rule. Revision **58** (57 is taken by sibling branches), fingerprint `9f251b17d4cf3b8a`. No allow-list or guard change.
- **Docs.** [`chat-ui-embedding.md`](../chat-ui-embedding.md) section 6, "The launcher": the snippet, the attribute table, authentication, the host page's CSP (`script-src`, `frame-src`, inline style in the shadow root, no SRI), and accessibility. Also [`user-memory.md`](../user-memory.md) ("What the user sees"), [`workforce-guide.md`](../workforce-guide.md) (the composer's modes, "What your agents remember about you"), [`agent-config-authoring.md`](../agent-config-authoring.md) (`inputField`), and the Chat UI and Workforce AGENTS and `@eddi/conversation` README.

### Bundle sizes (gzip -9, KB)

| Entry | Before | After | Budget |
| --- | --- | --- | --- |
| `chat.html` (widget first load) | 169.4 (169,379 B) | 169.5 (169,543 B) | 170 |
| `scripts/js/chat-ui-embed.js` (new) | — | 3.6 (3,647 B) | 5 |
| `workforce.html` | 255.0 | 256.5 | 280 |
| `manage.html` / `welcome.html` | 611.8 | 612.8 | 620 |

No existing budget was raised. Workforce grew 1.5 KB. About 1 KB is the entry chunk (the sidebar link and its icon, the switcher item). The rest is Rolldown splitting the shared `groups` chunk differently (`sse` 45.3 KB + `groups` 35.3 KB, before one 80 KB chunk), now that `SecretInputField` imports `@eddi/conversation`'s main entry. The memory page itself is a lazy route. The widget's growth is the composer split and the shared mode resolver. It is within the budget but not offset (see the decision log).

### Design decisions

- **Search in the browser over what the page shows**, not `GET /{userId}/search`. That endpoint matches keys and string values only (newest 200), while the person thinks in agent and team names, which only the page knows.
- **Forget one has no confirm. Forget everything does.** One entry is one row, and the toast names it. "Everything" is irreversible across every agent, so its dialog says so in full.
- **The launcher's dialog takes focus on open** (Esc works at once, Tab reaches the close button and the conversation). Inside the conversation, Esc keeps the widget's meaning, Stop. A widget-to-host "escape" message would need the widget to know its parent's origin even without a token provider, which costs widget bytes the budget does not have.

### Not done

- Esc from inside the conversation closing the launcher panel (see above).
- Editing a memory entry: the REST upsert is open to the owner, but what an end user may rewrite (an agent's private note, a team's entry) needs a product decision first.
- An E2E spec for `/workforce/memory`: the MSW tier runs with no signed-in user (the auth context has no `user`), and the page correctly asks for nothing then. The Vitest suite covers the flows with a signed-in context.

```decision-log
| 2026-11-06 | The composer's modes are one resolver in `@eddi/conversation` (field > lock > text; only `password` is secret; no quick replies under the lock) | The widget and the app-kit composer had drifted: the app-kit field vaulted email addresses, and both echoed a secret when the lock was switched off | Fixing each composer separately |
| 2026-11-06 | The widget grows 0.16 KB gz for T2.5 rather than offsetting it | The growth is the secret-echo fix itself; it stays under the 170 KB budget, and no dead code was found to remove | Lazy-loading the secret mode (a chunk fetch at the moment a user types a key) |
| 2026-11-06 | `/chat/embed.js` is a literal JAX-RS path that reads the built file from the classpath, cached an hour | The `/chat/{path:.*}` catch-all would answer with HTML, which `nosniff` refuses as a script; host pages hard-code the URL, so it cannot be content-hashed or immutable | Serving the launcher under `/scripts/js/` only; a redirect |
| 2026-11-06 | The launcher sets `tokenOrigin` only when the host gives a token provider | With `tokenOrigin` the widget holds its start up to five seconds for a token | Always setting it |
| 2026-11-06 | "My memory" lists `_gdpr_` rows as a note, not as entries | The backend refuses to delete them on this path; a button that always fails is worse than saying they exist | Listing them read-only |
```
