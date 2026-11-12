# @eddi/conversation

Controllers and components for every kind of EDDI conversation (frontend
unification plan, `planning/frontend-unification-plan.md` §3.2). React and
`@eddi/core` are allowed; a router and `window.__EDDI_*__` are not — hosts pass
ids, callbacks and the server's settings in. Consumed **as source** through the
npm workspace link (`exports` point at `src/index.ts`), so there is no build step.

Phase 3b brought the **agent** kind (1:1 conversations); phase 6 the **team**
kind (group discussions, below). The operator kind still runs its own loop in the
Manager (`use-operator-chat.ts`); see the changelog of phase 6 for why.

The entries, so an app resolves only what it renders:

| Entry | What | Who imports it |
| --- | --- | --- |
| `@eddi/conversation` | The agent kind: controller, components, the one markdown renderer, the `conversation.*` strings | All three apps |
| `@eddi/conversation/team` | The team kind: the `GroupDiscussion` controller and the team components | The Manager and Workforce |
| `@eddi/conversation/i18next` | `ConversationMarkdown`: the markdown renderer with a react-i18next app's translator | The Manager and Workforce |
| `@eddi/conversation/tool-activity` | What a turn's tools did (T1.9), the notice before Edit or Regenerate of such a turn (T1.6), and the turn notes: a cascade, a re-ask or a guardrail in plain words (T2.7), below | The Manager and Workforce |

The Chat UI never resolves the last three, so they cost its bundle nothing
(`tool-activity/ToolActivity.test.tsx` fails if `ui/chat/src` imports the tool entry or
`@eddi/core/tool-events`).

| Module | What it holds |
| --- | --- |
| `controller/agent-conversation.ts` | `AgentConversation`: the turn state machine of the 1:1 loops — open, send (streamed or not), undo, redo, rerun, stop, cancel a paused turn, restart, resume, the refresh after a pause. A plain store (`subscribe`, `getSnapshot`, `dispatch`), with host hooks (below) |
| `controller/state.ts` | `AgentConversationState`, `ConversationMessage` and `conversationReducer` (generic over a host's extra state) |
| `controller/transport.ts` | `AgentTransport` — `conversation`, `managed` or `local` — and `createConversationTransport` / `createManagedTransport` on @eddi/core's API |
| `controller/notices.ts` | What a client says about a dropped turn, a failed start, a pause |
| `i18n/text.ts` | The shared `conversation.*` strings in the eleven locales, `withConversationLocales` for an app's i18next setup, the English fallback |
| `react/hooks.tsx`, `react/context.tsx` | `useAgentConversation(controller)` (`useSyncExternalStore`), `ConversationTextProvider` / `useConversationText`, `ConversationProvider` (the state and dispatch the composer acts on) |
| `markdown/` | **The one markdown renderer** (`MarkdownContent`, §2.5): images in model text become links, raw HTML only through rehype-sanitize (the parser a lazy chunk; `Message` renders HTML only for an agent with `conversationControls.rawHtml: true`, D9, and shows it as text otherwise), KaTeX (`$$…$$`, `trust: false`) and highlight.js as lazy chunks, a fenced block's language named above it, a ```` ```mermaid ```` block drawn by Mermaid (`securityLevel: "strict"`; `MermaidDiagram.tsx` and Mermaid are lazy chunks, drawn once the source has been still for 400 ms, the code block until then and when it does not parse), links in a new tab, GFM parsed without its serialiser (`remark-gfm-parse.ts`). `one-renderer.test.ts` fails on an import of `react-markdown`, the rehype/remark plugins or `mermaid` outside this directory |
| `components/` | `Message` (markdown, raw HTML per the agent's `enableRawHtml` (off by default), designer images, a sent message's attachments, a reply's guardrail notes, copy, reply rating, an `actions` slot), `MessageList` (live region, follows new content while at the bottom), `Composer` (IME-safe, attachments with picker, drop and paste — `attachments={false}` hides them, secret mode, restored drafts), `SecretInput` (the field an agent requested), `QuickReplies`, `ThinkingIndicator` / `TypingIndicator` (typing, tool, cascade, guardrail — `guardrailActive`), `PausedCard` (read-only, cancel), `RatingCard`, `StepRating` (lazy inside `Message`), `ScrollToBottom`, `ReplyAnnouncer` (a polite status region that says each completed reply once, with its speaker, and the client's notices; the transcript beside it stays `aria-live="off"`). `PausedCard` and `RatingCard` also have their own entries (`@eddi/conversation/paused-card`, `/rating-card`) for a host that loads them on demand, as the widget does |

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
| `stopVerifyDelaysMs` | After Stop, re-read the conversation at these delays (the apps pass `STOP_VERIFY_DELAYS_MS`, 1.5 s and 5 s). `/cancel` stops a running turn only on its own pod (docs/hitl.md); when a read finds the turn ran on, a notice says so — it finished anyway (its whole reply replaces the part Stop left) or is still running — and a cancel that could not reach the server says that. A 409 means it had already finished. Off without the option |
| `reconnectDelaysMs` | A stream that drops after part of the reply arrived is followed: `reconnecting` is set and the conversation re-read at these delays (`RECONNECT_DELAYS_MS`, about half a minute) until the turn settles, then applied as its `done` would have been. Off without the option, and after the last delay the turn ends as a lost connection, as before |

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

## The team kind (`@eddi/conversation/team`)

| Module | What it holds |
| --- | --- |
| `controller/group-discussion.ts` | `GroupDiscussion`: one group's discussion streamed over SSE — the state a run builds (phases, member turns, the human member's turn, the approval pause, the decision, costs, stances, tasks), `applyGroupStreamEvent` (the reducer every event goes through), and start, approve-and-resume, continue a round, detach (`abort`), Stop (`cancel`: the deferred Stop, 409 as already ended), `reset`, `whenAccepted`. A plain store (`subscribe`, `getSnapshot`) over a `GroupTransport` the host binds to its client, with an optional host store |
| `team/i18n.ts`, `team/i18n/locales/*` | The team components' strings: nested `groups.*` keys read through the app's react-i18next, eleven locales, `withTeamLocales` (deep merge; `@eddi/app-kit`'s `setupAppI18n` calls it) |
| `team/group-utils.ts`, `group-entry-body.ts`, `group-payloads.ts`, `group-decision.ts`, `discussion-styles.ts`, `duration.ts`, `use-discussion-digest.ts` | What a transcript entry says (structured bodies, VOTE/BID/BARGAIN/RETRO payloads, a decision's headline), style labels, ISO durations, the overview's digest |
| `team/*.tsx` | `StructuredTurnCard`, `StructuredEntryBody`, `DecisionRecordCard` (its first line is `decisionSummary`, the decision in plain words), `TaskBoard` / `PersistedTaskBoard`, `NegotiationLedger`, `ArtifactsPanel`, `DiscussionInsights`, `HumanTurnBanner` (`viewerIsMember: false` says whose turn it is and who may answer) |
| `team/artifact-content.tsx`, `artifact-versions.tsx`, `artifact-version-list.ts` | T2.6: `ArtifactContent`, the one rendering policy for an artifact (MARKDOWN through the one renderer with raw HTML escaped, JSON pretty-printed, TEXT plain); `ArtifactVersions`, the list, one artifact opened and a switch between the versions its `history` keeps; `artifactVersions`, those versions newest first |
| `team/decision-summary.ts` | `decisionSummary(decision)`: one or two plain sentences from the record's typed fields (never an agreement's proposal id), as i18n key, fallback and values |
| `team/overview/*` | The overview: `PhaseRail`, `MemberRoster`, `ParticipationMatrix`, `InteractionMap`, `BidBoard`, `DiscussionOverview`, the transcript / overview / split toggle |

The contract they render — `TranscriptEntry`, `GroupConversation`, the SSE events —
is `@eddi/core/groups`. The components are Tailwind-styled (both apps scan
`src/team` through `@eddi/app-kit`'s `app.css`) and router-free: what links
somewhere arrives from the host. The signed-in apps bind the controller in
`@eddi/app-kit`'s `hooks/use-group-discussion-stream.ts` (a transport over their
client, one controller per group writing its slot of a Zustand store, so a
discussion keeps streaming while the user is on another screen).

```ts
import { GroupDiscussion } from "@eddi/conversation/team";

const discussion = new GroupDiscussion({ groupId, transport }); // transport: GroupTransport
await discussion.start("Should we ship?");
discussion.getSnapshot().transcript; // QUESTION, member turns, SYNTHESIS…
```

## Rules

- **Router-free, no server globals, no app import.** `eslint.config.js`
  (`sharedPackage({ react: true, routerFree: true })` in `ui/eslint.config.base.js`)
  enforces it; `core/src/boundaries.test.ts` checks it again, and that neither
  `@eddi/core` nor `@eddi/ui` imports this package.
- **The turn rules live in the controller, once.** A fix to how a turn is sent,
  refused, paused or resumed goes into `agent-conversation.ts` with a case in
  `agent-conversation.test.ts`, and every agent surface gets it. The same holds for
  a group discussion: `group-discussion.ts`, with a case in `group-discussion.test.ts`
  (the apps' stream tests run through it as well).
- **Every string is a `conversation.*` key, in all eleven locales**
  (`i18n/text.test.ts` checks parity and placeholders) — except the team
  components', which are nested keys in `team/i18n/locales` (`team/i18n.test.ts`).
  Both apps' `npm run i18n:check` read every `i18n/locales` directory of the
  package with their own files.
- **The Chat UI's bundle does not move.** Nothing the main entry reaches may import
  `team/` or `i18next/`; the widget's first load sits on its budget.
- **Strict.** It compiles under the strictest app (the Manager's
  `noUncheckedIndexedAccess` and ES2020 target).
- Checks: `npm run typecheck`, `npm run lint`, `npm test` in this directory. CI runs
  them in the `UI Chat` job.

## Tool activity (`@eddi/conversation/tool-activity`)

The stream's `tool_result` events (B6, parsed by `@eddi/core/tool-events`) as the user sees
them. Tailwind-styled and read through the app's react-i18next, like the team kind.

| Module | What it holds |
| --- | --- |
| `tool-activity/tool-activity-log.ts` | `ToolActivityLog`: the calls of each turn by conversation and step, a plain store (`subscribe`, `getSnapshot`). A host calls `beginTurn(conversationId, step)` from `onTurnStarting` (the step and every later, undone one start empty), `record` from `onStreamEvent` through `toolResultListener`, `clearStep` after a turn ran again without a stream. At most 50 calls per turn (the rest are counted) and 20 conversations. `toolCardPositions` puts a turn's cards under its question; `toolNames` lists a turn's tools once each |
| `tool-activity/ToolActivity.tsx` | `ToolActivityCards` (one disclosure per turn, collapsed: "Tools used: 2 · 1 failed"; open, a card per call with the tool, whether it worked and the summary **as a text child, never markdown or HTML**), `ToolTurnNotice` (which tools the turn ran, beside Edit or Regenerate: neither takes back what they did), `useToolTurns(log, conversationId)`, `formatToolList` (`Intl.ListFormat`, three names then "+N more") |
| `tool-activity/turn-notes.ts`, `TurnNotes.tsx` | T2.7: `parseTurnNote` reads `cascade_escalation` and `llm_retry` (their `reason` only), `TurnNotesLog` files them like `ToolActivityLog` (`beginTurn`, `record` through `turnNoteListener`, `clearStep`), `TurnNotes` renders a turn's notes as plain sentences, `GuardrailNote` words a `guardrail_triggered` notice the controller keeps on the reply. Only what the events say; live only |
| `tool-activity/i18n.ts`, `i18n/locales/*` | `toolActivity.*` and `turnNotes.*` in the eleven locales, `withToolActivityLocales` (`setupAppI18n` calls it) |

**Live only.** The events are not stored in a form a client may read: the detailed snapshot
holds the raw tool trace, which the server redacts only on the way out through the stream. A
reload, or a conversation opened later, shows no cards for its earlier turns.
