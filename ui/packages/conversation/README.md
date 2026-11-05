# @eddi/conversation

Controllers and components for every kind of EDDI conversation (frontend
unification plan, `planning/frontend-unification-plan.md` §3.2). React and
`@eddi/core` are allowed; a router and `window.__EDDI_*__` are not — hosts pass
ids, callbacks and the server's settings in. Consumed **as source** through the
npm workspace link (`exports` point at `src/index.ts`), so there is no build step.

Phase 3b brings the **agent** kind (1:1 conversations). The operator and team
kinds follow in phase 6.

| Module | What it holds |
| --- | --- |
| `controller/agent-conversation.ts` | `AgentConversation`: the turn state machine of the 1:1 loops — open, send (streamed or not), undo, redo, rerun, stop, cancel a paused turn, restart, resume, the refresh after a pause. A plain store (`subscribe`, `getSnapshot`, `dispatch`), with host hooks (below) |
| `controller/state.ts` | `AgentConversationState`, `ConversationMessage` and `conversationReducer` (generic over a host's extra state) |
| `controller/transport.ts` | `AgentTransport` — `conversation`, `managed` or `local` — and `createConversationTransport` / `createManagedTransport` on @eddi/core's API |
| `controller/notices.ts` | What a client says about a dropped turn, a failed start, a pause |
| `i18n/text.ts` | The shared `conversation.*` strings in the eleven locales, `withConversationLocales` for an app's i18next setup, the English fallback |
| `react/hooks.tsx`, `react/context.tsx` | `useAgentConversation(controller)` (`useSyncExternalStore`), `ConversationTextProvider` / `useConversationText`, `ConversationProvider` (the state and dispatch the composer acts on) |
| `markdown/` | **The one markdown renderer** (`MarkdownContent`, §2.5): images in model text become links, raw HTML only through rehype-sanitize (the parser a lazy chunk, rendered for every agent until D9's per-agent setting), KaTeX (`$$…$$`, `trust: false`) and highlight.js as lazy chunks, links in a new tab, GFM parsed without its serialiser (`remark-gfm-parse.ts`) |
| `components/` | `Message` (markdown, designer images, a sent message's attachments, a reply's guardrail notes, copy, reply rating, an `actions` slot), `MessageList` (live region, follows new content while at the bottom), `Composer` (IME-safe, attachments with picker, drop and paste, secret mode, restored drafts), `SecretInput` (the field an agent requested), `QuickReplies`, `ThinkingIndicator` / `TypingIndicator` (typing, tool, cascade, guardrail — `guardrailActive`), `PausedCard` (read-only, cancel), `RatingCard`, `StepRating`, `ScrollToBottom` |

The components carry the Chat UI's BEM class names (`message--user`,
`chat-input__textarea`, …); the Chat UI's `chat.css` styles them. The Manager runs
its chat on the controller (`use-chat.ts`, through the host hooks) and renders it
with its own bubble around `MarkdownContent`.

## Host hooks

A host with its own store, copy and side panels drives the same turn loop through
options; without them the controller behaves as it does for the Chat UI. The
Manager uses all of them (`ui/manager/src/hooks/use-chat.ts`).

| Option | What it is for |
| --- | --- |
| `store` | Keep the state in the host's store (`get`, `set`, `subscribe`) — the Manager's Zustand chat store |
| `generation` | The host's own conversation generation, when something other than `reset()` replaces the conversation on screen |
| `detachedGraceMs` | Let a replaced conversation's stream drain this long, so the turn finishes on the server, then abort it |
| `refreshAfterStream` | Off: take undo/redo from the `done` payload (`onTurnSnapshot`) instead of a read after the stream |
| `attachmentDisplay` | `"chips"`: files on the message as `attachments` instead of `📎` lines; `send(text, secret, { attachments })` takes the host's staged files. A secret turn never forwards a file |
| `outputTexts` | How an output entry becomes texts (the Manager also reads the flat `output:text:*` keys) |
| `onStreamEvent`, `onTurnStarting`, `onTurnEnd` | Every event of the turn on screen (a debug panel) and the turn's boundaries |
| `onTurnSnapshot`, `turnFailure` | The snapshot a finished turn answered with; why it failed, shown in its reply |
| `presentFailure` | Show a refused or failed turn the host's way (toasts, error bubbles, a banner); `send` resolves `{ outcome }` so a caller can reject |

## Using it

```ts
import {
  AgentConversation,
  createConversationTransport,
  useAgentConversation,
} from "@eddi/conversation";

const controller = new AgentConversation({
  transport: createConversationTransport({ environment, agentId }),
  t, // the app's translator; English without it
});
await controller.open(false);          // or controller.resume(conversationId)
await controller.send("Hello");
const state = useAgentConversation(controller); // in a component
```

Strings: merge the bundles into the app's i18next setup, and give the components
the app's `t`:

```ts
const { english, loaders } = withConversationLocales(appEnglish, appLoaders);
setupI18n({ i18n, english, loaders, /* … */ });
// <ConversationTextProvider t={t}>…</ConversationTextProvider>
```

## Rules

- **Router-free, no server globals, no app import.** `eslint.config.js`
  (`sharedPackage({ react: true, routerFree: true })` in `ui/eslint.config.base.js`)
  enforces it; `core/src/boundaries.test.ts` checks it again, and that neither
  `@eddi/core` nor `@eddi/ui` imports this package.
- **The turn rules live in the controller, once.** A fix to how a turn is sent,
  refused, paused or resumed goes into `agent-conversation.ts` with a case in
  `agent-conversation.test.ts`, and every agent surface gets it.
- **Every string is a `conversation.*` key, in all eleven locales**
  (`i18n/text.test.ts` checks parity and placeholders). The Manager's
  `npm run i18n:check` reads these files with its own.
- **Strict.** It compiles under the strictest app (the Manager's
  `noUncheckedIndexedAccess` and ES2020 target).
- Checks: `npm run typecheck`, `npm run lint`, `npm test` in this directory. CI runs
  them in the `UI Chat` job.
