# EDDI Chat UI — AI Agent Guidelines

> **This file is loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**eddi-chat-ui** is a standalone React 19 chat widget for [EDDI](https://github.com/labsai/EDDI) agents. Built with Vite + TypeScript 5.7, vanilla CSS with CSS custom properties, and `react-markdown` for rich message rendering.

### Tech Stack

| Technology     | Version | Purpose                        |
| -------------- | ------- | ------------------------------ |
| React          | 19      | UI framework                   |
| TypeScript     | 5.7     | Type safety                    |
| Vite           | 6       | Build tool + dev server        |
| Vitest         | 3.x     | Unit testing (jsdom)           |
| react-markdown | 9.x     | Markdown rendering in messages |

### Ecosystem

- **EDDI backend** — Quarkus REST API at `/agents/{env}/{agentId}`, serves chat UI from `META-INF/resources/`
- **EDDI Manager** — Admin UI with embedded chat panel, shares API patterns
- All repos at `c:\dev\git\`

---

## 2. Project Structure

```
src/
├── api/            # API layer
│   ├── http.ts             # Status-aware fetch core, ApiError, errorPayload, auth token
│   ├── chat-api.ts         # Conversation lifecycle (start, read, send, stream, undo, redo)
│   ├── hitl-api.ts         # Approval status, cancel, deadline maths, poll cadence
│   ├── attachments-api.ts  # Upload/delete + attachment_N context construction
│   ├── sse-events.ts       # Pure interpretation of SSE payloads
│   └── demo-api.ts         # Mock API for demo mode
├── components/     # UI components
│   ├── ChatWidget.tsx      # Main orchestrator (lifecycle, SSE, query params)
│   ├── ChatHeader.tsx      # Logo/title, undo/redo, theme toggle, new conversation
│   ├── MessageBubble.tsx   # User/agent messages with markdown
│   ├── ChatInput.tsx       # Auto-grow textarea, attachment chips, secret mode
│   ├── PausedCard.tsx      # Awaiting-approval state (read-only, no approve/reject)
│   ├── QuickReplies.tsx    # Pill buttons for suggested replies
│   ├── Indicators.tsx      # Typing (dots), Thinking (brain), Escalating (cascade)
│   └── ScrollToBottom.tsx  # Floating scroll button
├── hooks/
│   ├── useTheme.ts         # Dark/light/system theme with localStorage
│   └── useHitlPolling.ts   # Polls approval-status while a turn is paused
├── store/
│   └── chat-store.tsx  # Context + useReducer state management
├── styles/
│   ├── variables.css   # CSS custom properties (dark/light tokens)
│   └── chat.css        # All component styles (BEM naming)
├── test-utils/
│   └── sse.ts          # SSE stream + status-aware fetch harnesses
└── types.ts            # Shared TypeScript types
```

### Backend contract (verified against EDDI source — do not guess these)

- **`ConversationState` has SIX values**: `READY`, `IN_PROGRESS`, `ENDED`,
  `EXECUTION_INTERRUPTED`, `ERROR`, `AWAITING_HUMAN`.
- **Eight SSE events**: `task_start`, `task_complete`, `task_failed`, `token`,
  `cascade_step_start`, `cascade_escalation`, `done`, `error`. There is **no
  `thinking` event** — the backend never emits one.
- **`error` payload is JSON** `{"message":"…"}`, not a bare string.
- **`done` is a trimmed snapshot** — only `conversationState` and
  `conversationOutputs`. It omits `undoAvailable`/`redoAvailable`, so re-read
  the snapshot to refresh them.
- **`output[]` entries are not uniformly objects** — HITL writes its
  pending-approval placeholder and rejection message as bare strings.
- **Attachments reach the model ONLY via `attachment_N` context keys** whose
  `value` is an object carrying `storageRef`. A ref in the message text is
  silently ignored. Omit `fileName` when empty — the extractor backfills the
  stored name only when the key is **absent** (`getFileName() == null`); `""` is
  a String and suppresses the fallback.
- **The per-turn cap of 5 is a default, not a contract** —
  `eddi.attachments.max-per-turn` is a `@ConfigProperty` and no endpoint exposes
  the effective value. Same for the 20 MiB upload / 10 MiB forward caps. Do not
  write client code that treats any of them as fixed truth.
- **Upload cap ≠ forward cap.** A file in between is stored, returns 201, and is
  skipped at forward time — but the model is **not** left unaware: the forwarder
  substitutes a text note (`AttachmentForwarder` → `TextContent.from(...)`)
  saying the file was not sent and that `readAttachment` can fetch it. Surface
  `forwardableInline` so the user knows the bytes were not inlined, but do not
  claim the agent cannot see the file. Note it is an incomplete signal: a
  separate `max-forward-aggregate-bytes` cap can skip a file that reported
  `true`.
- **`setPublic(false)` is NOT a wire filter.** `isPublic()` is never read as a
  predicate anywhere in the backend — it is only copied into the snapshot. What
  actually keeps a key out of the response is the key-prefix allowlist in
  `ConversationMemoryUtilities.convertSimpleConversationMemory`, and
  `returnDetailed=true` bypasses it entirely. This client asks for
  `returnDetailed=false`, which is a client choice.
- **Failures answer `{error, code}`** — parse both (`errorPayload` in `http.ts`).
  `ATTACHMENT_REJECTED` is a catch-all: a declared-vs-detected MIME **mismatch**
  (there is no type allowlist — an unknown type passes), the per-conversation
  file/byte quotas, empty files, and on Postgres any wrapped `SQLException`, so
  a database outage also arrives as a 400 "rejection". Only `error` says which.
  A body-less 413 from Quarkus is possible but not the normal path — this repo
  raises `max-body-size` to 25M precisely so in-cap uploads do not hit it.
- **A 409 means the input was never consumed** — restore it, do not leave it in
  the transcript as if sent.

---

## 3. Key Conventions

1. **CSS** — BEM naming: `.chat-header__logo`, `.message--user`, `.quick-replies__btn`. No Tailwind.
2. **State** — `ChatProvider` → `useChatState()` / `useChatDispatch()`. No prop drilling.
3. **API** — Pure `fetch` in `chat-api.ts`. SSE streaming uses `AsyncGenerator`.
4. **Testing** — Wrap components in `<ChatProvider>`. Mock `window.matchMedia` in `test-setup.ts`.
5. **Demo mode** — `/chat/demo/showcase` uses `demo-api.ts`. Check with `isDemoMode()`.
6. **Query params** — `hideUndo`, `hideRedo`, `hideNewConversation`, `hideLogo`, `theme`, `title`, `apiServer`, `token`.
7. **HITL is read-only here** — the widget surfaces a paused turn and offers cancel, but never Approve/Reject. Deciding belongs to a reviewer in Manager UI (`/agents/pending-approvals`).

---

## 4. Build & Deploy

Production build goes to **EDDI backend** at `src/main/resources/META-INF/resources/`:

```bash
npm run build    # Outputs to EDDI backend resources
```

---

## 5. Mandatory Workflow

1. **Before work**: `git status`, read this file + any `changelog.md`
2. **During work**: Commit with `feat(chat-ui):` / `fix(chat-ui):`. Each commit must build.
3. **After work**: Update `changelog.md`

### DO NOT

- Add external state libraries (zustand, redux) — use Context + useReducer
- Add CSS frameworks — use CSS custom properties
- Duplicate logic from `demo-api.ts` into components
