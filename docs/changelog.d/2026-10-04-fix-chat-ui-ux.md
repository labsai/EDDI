## 💬 fix(chat): UX review of the standalone Chat UI (2026-10-04)

**Repo:** EDDI (`fix/chat-ui-ux`) — `ui/chat`, [`ui/chat/README.md`](../../ui/chat/README.md), [`security.md`](../security.md)

### What changed and why

A review of the standalone chat widget found a run of problems that cost users text, orientation or accessibility. Fixed in `ui/chat`:

- **Reading while it streams.** Auto-scroll fired on every token with smooth scrolling, fighting anyone who scrolled up. The view now follows only while the reader is at the bottom (an instant jump), a send re-engages it, and the scroll-to-bottom button shows when it is not following. Streaming bubbles re-parse markdown at most every 50 ms instead of per token.
- **`postMessage` token vs auto-start.** With `?tokenOrigin=` the conversation used to start before the host could send the token, so the start (and the review-notice lookup) went out unauthenticated. The widget now posts `{"type":"eddi-chat-ready"}` to each allowed origin by name (never `*`, repeated every second), holds the start until `eddi-chat-token` arrives, and gives up after 5 seconds and starts without one. Documented in the README and [`security.md`](../security.md).
- **A failed send no longer eats the message.** Any 4xx, a server error or a dropped connection before the first token withdraws the user bubble and puts the text back in the composer. If tokens had already arrived, the partial answer stays, "Connection lost — the response may be incomplete" is appended, and the conversation is re-read so the next send does not 409. The streaming, non-streaming and managed transports behave the same.
- **Typed failures have their own copy**: 409 (the backend's operator text, naming `POST /agents/<id>/resume`, is no longer shown), 401 ("session has expired"), 403, 404 (agent not ready), 413, 429, 503, and 410, which now always sets ENDED.
- **Stop works on every transport.** An `AbortSignal` is passed through `sendMessage` and `sendManagedAgentMessage`; a late reply for a stopped turn is ignored and stopping is not reported as a failure.
- **Start/empty states.** A failed start offers "Try again". An agent with no greeting shows "Say hello to get started" instead of "Starting conversation…" forever. A paused conversation whose approval status has not arrived shows an indicator.
- **Header.** `?title=` is rendered (and sets the tab title), and `?hideLogo=true` shows the text title instead of an empty bar, as the README always promised.
- **Safer controls.** "New conversation" asks first when the conversation has messages; an agent-requested input field has "Type a message instead"; the secret toggle has a fixed label with `aria-pressed`, is disabled mid-turn, and its tooltip now says what the vault does (hidden in the chat, masked in stored history) rather than "encrypted".
- **Accessibility.** IME composition (CJK, mobile keyboards) no longer sends on the confirming Enter, in the composer and the requested field. Avatars are `aria-hidden` and each bubble says who spoke ("You said:" / "<Agent> said:"). The composer textarea has a label. Focus returns to the composer after a secret send or leaving the requested field. The paused card is no longer a nested live region and its minute-by-minute countdown is hidden from assistive technology in favour of a fixed clock time.
- **Look.** Inputs are 16px on phones (no iOS zoom), long quick replies wrap, accent tints use `color-mix()` so any CSS colour works, `?theme=` beats a remembered preference and the toggle cycles dark, light, system.
- **Notices are not agent bubbles.** Text the widget wrote (failed sends, undo failures, cancellations, upload problems) renders as a system notice.
- **Undo/redo** now rebuild quick replies and the requested input field from the snapshot, and undoing to an empty conversation clears the old bubbles.
- **Copy buttons** on agent messages and code blocks.
- **i18n.** The widget's own text is a small string table (`src/i18n.ts`: English, German, French, Spanish), chosen by `?lang=` or the browser language, falling back per key to English; `<html lang>` follows. Documented in the README.

### Design decisions

- One module-level locale rather than React context: the SSE and HITL copy helpers live outside React and must translate too.
- Notices are a `kind: "notice"` flag on `ChatMessage`, so existing transcript code is untouched.
- The restart confirmation is inline, not a modal, because the widget is often a small iframe; an untouched conversation restarts without asking.
- The ready-signal repeats until the token arrives because the parent's listener may not exist when the iframe's first message goes out.

### Deliberately not done

- `button` output items stay bold text: `onPress` is a free-form map for clients that implement it, with no contract this widget could honour, so there is nothing safe to click.
- The 403 on start cannot be told apart as an origin rejection (the response does not say), so its copy names both causes without accusing the user.
- Only English, German, French and Spanish are translated; the Manager's other seven locales (ar, hi, ja, ko, pt, th, zh) fall back to English until their tables are added.

**Files:** [`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx), [`i18n.ts`](../../ui/chat/src/i18n.ts), [`MessageBubble.tsx`](../../ui/chat/src/components/MessageBubble.tsx), [`ChatInput.tsx`](../../ui/chat/src/components/ChatInput.tsx), [`SecretInput.tsx`](../../ui/chat/src/components/SecretInput.tsx), [`PausedCard.tsx`](../../ui/chat/src/components/PausedCard.tsx), [`ChatHeader.tsx`](../../ui/chat/src/components/ChatHeader.tsx)
