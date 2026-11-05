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
| `controller/agent-conversation.ts` | `AgentConversation`: the turn state machine of the 1:1 loops — open, send (streamed or not), undo, redo, rerun, stop, cancel a paused turn, restart, resume, the refresh after a pause. A plain store (`subscribe`, `getSnapshot`, `dispatch`) |
| `controller/state.ts` | `AgentConversationState`, `ConversationMessage` and `conversationReducer` (generic over a host's extra state) |
| `controller/transport.ts` | `AgentTransport` — `conversation`, `managed` or `local` — and `createConversationTransport` / `createManagedTransport` on @eddi/core's API |
| `controller/notices.ts` | What a client says about a dropped turn, a failed start, a pause |
| `i18n/text.ts` | The shared `conversation.*` strings in the eleven locales, `withConversationLocales` for an app's i18next setup, the English fallback |
| `react/hooks.tsx` | `useAgentConversation(controller)` (`useSyncExternalStore`), `ConversationTextProvider` / `useConversationText` |

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
