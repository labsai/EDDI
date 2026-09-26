## 🐛 fix(chat-ui): streaming contract, environment, secret input, rendering and a flaky focus test (2026-09-26)

**Repo:** EDDI (`fix/chat-ui`) — UI review 2026-09-25 items High 6 (Chat UI side), High 12, High 13 and the Chat UI Medium/Low items; fix-plan item 26.

### What changed and why

**Streaming and the backend contract**
- **High 12, extra space per token.** `RestAgentEngineStreaming.padDataLines` prefixes every `data:` line with one space, and the parser kept it, so every token gained a space ("quota tion"). The parser in [`chat-api.ts`](../../ui/chat/src/api/chat-api.ts) now strips exactly one; a token's own leading space follows it. The test that enshrined the old behaviour is rewritten.
- **High 13, environment ignored.** `startConversation` now sends the route's environment as `?environment=`; the backend defaulted a missing one to production, so `/chat/test/{id}` returned 404 for a test-only agent and silently used production for an agent deployed in both. A failed start now says why in the transcript instead of staying on "Starting conversation…".
- **Streamed refusals.** An `error` frame carrying a pre-turn refusal `code` (`awaiting_approval`, `conversation_ended`, `agent_not_ready`, …) is handled like a 409: the optimistic bubble is withdrawn and the draft handed back. The code list is pinned against the Java source by `sse-events.review.test.ts`. A mid-turn failure (no code) still stays as an error on the sent message.
- **`rerunLastStep` no longer sends `language=en`.** The backend already treats it as optional; the hard-coded value became the conversation's `lang` property.

**Secret input (High 6, Chat UI side)**
- A requested `inputField` is read from the streaming `done` frame too, not only the non-streaming snapshot, so the password field appears on the default transport and the key is sent with `secretInput`.
- Only `subType: "password"` is masked and flagged secret; `text` and `email` are ordinary fields (`SecretInput` used to mask every subType).
- A refused secret goes back into the composer **masked, with secret mode on** instead of being dropped.
- Rebuilt transcripts (undo, redo, reload) mask a secret turn from the turn output's `input` (`<secret input>`). This needed one backend change: see below.

**Backend changes the Chat UI findings required**
- [`ConversationMemoryUtilities.convertSimpleConversationMemory`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java) now keeps the exact `input` key in non-detailed `conversationOutputs`. It is the display copy of the user's message and reads `<secret input>` for a secret turn, while `input:initial` in the steps stays raw. Without it, the only copy of the input on the wire was the plaintext. The change is additive; readers that look up specific keys are unaffected. The Manager's `snapshotToMessages` (from `fix/manager-chat`) reads this key, so the change also makes its secret masking work with `returnDetailed=false`.
- [`RestAgentManagement.loadConversationMemory`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java) re-runs the last step only when the caller asked for a language. A load without `?language=` compared the stored `lang` against null and re-ran the step on every widget load.

**Conversation lifecycle**
- Managed **New conversation** ends the current managed conversation (`POST /agents/managed/{intent}/{userId}/endConversation`) before loading. Before, the load returned the same conversation.
- Start and restart share one `openConversation`, which re-checks the conversation generation after every await. The first load had no such check, so a restart during its welcome read could get the old greeting and id.

**Rendering**
- Links open in a new tab (`rel="noopener noreferrer"`). A link used to navigate the widget, or the whole iframe, away.
- `<img>` is removed from the sanitize schema. A model-chosen image URL is a beacon, and only EDDI's own CSP blocked it; an embedding page's CSP might not.
- `image` output items are rendered by the widget, http(s) or same-origin only. `applicationLink` becomes a link and `button` shows its label. All three used to be dropped silently.
- KaTeX and syntax highlighting (advertised in the README, never wired) now work. Both are **loaded on first use** from their own chunks (`markdown-plugins.ts`), so the main bundle stays about the same (645 → 652 kB). The KaTeX fonts go to `fonts/`, which the Maven build already copies and prunes.
- `MessageBubble` takes its flags as props instead of subscribing to the store, so a streamed token no longer re-renders and re-parses every bubble.

**Smaller items**
- The transcript is `role="log"` with `aria-live="polite"` and `aria-busy` while a reply streams.
- `useTheme` guards every storage access. Blocked storage used to throw and blank the widget.
- `?token=` is read once and removed from the address bar.
- The agent name comes from `/descriptorstore/descriptors/{id}/simple?version=N` with the bearer token. The old call went to `/agentstore/agents/{id}` without auth or version, and that response has no name.
- ESLint (the same base as the Manager) with a zero-warning `lint` script and a `Lint` step in the `UI Chat` CI job.

**Flaky test: "moves focus to the attach button when the last chip is removed"**
- Root cause: the focus intent was consumed by the first `pendingAttachments` effect to run. The upload resolves outside `act`, so React commits the new chip but defers that commit's passive effect to a scheduler task. When the remove click landed before that task ran, React flushed the stale effect first. That effect took the intent while the chip was still mounted and focused the remove button that was about to unmount, so focus fell to `<body>`. Whether the task ran first depends on Node's `setImmediate` vs `setTimeout(0)` ordering, which changes under CPU load.
- Fix: the intent now carries the removed `storageRef` and is only acted on once that chip is gone. A new test forces the race deterministically by clicking natively the moment the chip appears. It failed 5/5 before the fix.

### Refuted / not changed
- **"A double-clicked restart starts two conversations."** Refuted as stated. Every restart control unmounts synchronously on the first click (`CLEAR_MESSAGES` clears the `conversationId` the action bar and banners depend on), so no second click can reach one. The related real race, a restart during the first load's in-flight read, is fixed by the generation checks above.
- Designer-configured images from external hosts are still blocked by EDDI's own `img-src 'self' data:` CSP when EDDI serves the widget. That is a deployment choice and is left alone.
- The descriptor endpoint is editor-only, so an end user with only `eddi-user` still sees the configured `title` instead of the agent name. A user-readable name endpoint would be a backend feature and is a follow-up.

```decision-log
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | Simple snapshots carry the turn output's `input` key | A reloaded transcript could only print `input:initial`, which is raw for a secret turn | Scrubbing `input:initial` after the pipeline (changes what rerun and the audit see) |
```
