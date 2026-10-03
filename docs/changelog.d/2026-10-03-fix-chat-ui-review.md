## 🐛 fix(chat-ui): embedded token hand-off works with auth on, designer images render, stale turns stay out (2026-10-03)

**Repo:** EDDI (`fix/chat-ui-review`), the Chat UI findings of the 2026-10-02 review (report card "Chat UI", §4.11).

### What changed and why

**postMessage token hand-off (Medium).** The widget accepted `{type: "eddi-chat-token"}` from an allow-listed parent, but it started the conversation the moment it mounted, before any host could have answered. With OIDC on, that first `POST /agents/{id}/start` went out without a token, came back `401`, and was never retried. A token arriving a moment later was installed but never used, so an embedded widget showed "You are not allowed to start a conversation" for good. Tokens also expired after the realm's lifespan with no way to renew them. Reproduced live against `labsai/eddi:6.5.0`.

The new [`embed-auth.ts`](../../ui/chat/src/api/embed-auth.ts) is a small protocol:

- **Ready.** The widget posts `eddi-chat-ready` to its parent once it is listening.
- **Token requests.** It posts `eddi-chat-token-request` a minute before a JWT's `exp`, and again on any `401`.
- **Retry.** [`http.ts`](../../ui/chat/src/api/http.ts) gets one 401 hook: ask the host for a token, wait up to 10 s, repeat the request once.
- **Exact targets.** Every outgoing message uses an exact `?tokenOrigin=` origin as its `targetOrigin`, never `*`.
- **Strict acceptance.** A token is accepted only from `window.parent` at an allow-listed origin. The widget drops a wildcard, a path or `null` from the list.
- **Memory only.** The token is never put in the URL or in storage.

A `?token=` or `ChatConfig` token no longer clears a token that arrived by `postMessage`. The full contract, with a host-page example, is the new page [chat-ui-embedding.md](../chat-ui-embedding.md).

**CSP blocked designer image outputs (Low-Medium).** `/chat`'s `img-src 'self' data: blob:` refused every `image` output item hosted elsewhere. `img-src` now appends `eddi.chat.img-sources` (`EDDI_CHAT_IMG_SOURCES`), default `https:`. Markdown images in model text are still rendered as links, so a model still cannot pick a URL for the browser to fetch. The trade-off, and the strict alternatives (a host list or `'self'`), are in [configuration-reference.md](../configuration-reference.md) and the embedding page. Only `/chat` changes: [`CspPolicyTest`](../../src/test/java/ai/labs/eddi/configs/CspPolicyTest.java) pins both halves.

**Low items.**

- **Generation guard.** A send's 409/401/403 branches now bail when the user started a new conversation while the request was in flight. Before, they withdrew a bubble, restored a draft and posted a warning into the replacement. Item 4 of the review brief found the same gap in undo, redo, retry, cancel and stop, so those are guarded too, including their `finally` that lowered `isProcessing` under a newer turn.
- **Attachment uploads across New conversation.** Found in review. An attachment upload that finished after New conversation was staged into the new conversation, so its next message carried a `storageRef` from the abandoned one. [`ChatInput.tsx`](../../ui/chat/src/components/ChatInput.tsx) now drops such an upload and deletes it from the old conversation, which frees its quota. The ref that holds the current conversation id is updated in a layout effect: not during render, and not in a passive effect. A passive effect left a gap between the commit and the update, and an upload settling in that gap still staged its file into the new conversation (CodeRabbit on #948). The delete is a background cleanup, `discardAttachment`: one retry after a transient failure (network, 5xx, 429), no retry on a 4xx, a console warning when the file is still left, and no UI. What is left is removed with its conversation: on delete, by the ended-conversation retention sweep, or by GDPR erasure. The user's own chip removal now goes through the same path. The ref belongs to the `ChatProvider`, not to one `ChatInput` instance (CodeRabbit on #948, round 2). `ChatWidget` swaps the composer out for `SecretInput` while an agent's input field is open. With an instance-owned ref, an upload that settled meanwhile was judged against the dead instance's conversation, so when the composer came back for a new conversation the old file was staged into it. The ref is deliberately not cleared on unmount: if the composer returns to the same conversation, the file is still the user's and its chip is waiting.
- **URL parameters.** `?theme=` accepts only `dark`, `light` or `system`. An unknown value used to be written into `data-theme` and left the widget unstyled. Each colour parameter must now be a plain colour, and `fontFamily` a plain font list. Before, any token sequence went into a CSS custom property, so a link could carry `url(...)`.
- **Vite dev proxy.** It now proxies `/conversations`, so attachments work under `npm run dev`. A test checks that every path prefix the API layer calls is proxied.
- **SSE parser.** It now yields a final frame that has no trailing blank line instead of dropping it. That frame is usually `done` or `error`.

**Platform Operator.** The docs map names `chat-ui-embedding`, so "how do I embed the chat?" goes straight to the page. Revision 2 → 3.

### Verification

- **Chat UI checks:** `npm run typecheck`, `npm run lint` and `vitest` (417 tests, 45 of them new) all pass. 23 mutants were applied to the new behaviour and all 23 were killed.
- **Backend:** `CspPolicyTest` and the repo guards pass. Removing the `img-src` append fails the new test.
- **Live:** stock `labsai/eddi:6.5.0` compared with this branch's jar, both with Keycloak on, using a host page on another origin. Evidence is attached to the PR.

**Files:** [`embed-auth.ts`](../../ui/chat/src/api/embed-auth.ts), [`http.ts`](../../ui/chat/src/api/http.ts), [`chat-api.ts`](../../ui/chat/src/api/chat-api.ts), [`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx), [`vite.config.ts`](../../ui/chat/vite.config.ts), [`application.properties`](../../src/main/resources/application.properties), [`chat-ui-embedding.md`](../chat-ui-embedding.md).

### Follow-ups

- Helm has no `eddi.chat.imgSources` value yet. Set `EDDI_CHAT_IMG_SOURCES` through `extraEnv`; a chart value means a chart version bump, which is out of this PR's scope.
- `?token=` remains a login-CSRF vector, as before. It is kept for compatibility, and the docs steer users to the hand-off.

```decision-log
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
```
