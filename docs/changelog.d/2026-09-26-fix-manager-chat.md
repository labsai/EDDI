## 🐛 fix(manager): chat panel and drawer — secret input, stream binding, pause handling, wizard providers (2026-09-26)

**Repo:** EDDI (`fix/manager-chat`)

Fixes the Manager chat findings of the 2026-09-25 UI review (fix-plan item 21):
High 6 (Manager side), the chat items of the Medium list, the wizard provider
findings, the built-in tool whitelist, L5, L6 and the agent-card undeploy.

### What changed and why

**Secret input (High 6).**
- *Password field ignored while streaming.* A backend `inputField` output item
  was only honoured on the non-streaming path, and streaming is the default. The
  key went into the ordinary textarea, in clear, and was sent without
  `secretInput`, so the backend neither masked it nor redacted it from the audit
  ledger. The streamed `done` snapshot, a greeting and a reopened conversation
  now all set the requested field. The field is cleared when the next turn is
  sent, and the reply decides whether it comes back.
- *The drawer never rendered the field at all.* `SecretInputField` moved out of
  the panel into [`secret-input-field.tsx`](../../ui/manager/src/components/chat/secret-input-field.tsx).
  The drawer renders it and sends the value as a secret turn.
- *Secrets came back on reload.* `snapshotToMessages` rebuilt user bubbles from
  the step's `input:initial`, which holds the raw text of every turn. It now reads
  the turn's conversation output first. That is where EDDI writes the displayed
  input, and for a secret turn it writes the `<secret input>` placeholder (shown
  as the mask). `input:initial` is kept as the fallback. This covers reload,
  resume, undo, redo and rerun.

**Stream binding (Medium).** Stream events were not bound to the conversation
that started them, so switching conversation or agent mid-stream wrote A's
tokens into B's bubble. The store now carries a `conversationEpoch`, bumped by
`setSelectedAgent`, `clearMessages` and `reset`. A send or start captures it and
writes nothing once it has moved on: tokens, `done`, the non-streaming reply, the
error bubble and the 409 rollback. A detached stream is drained, not aborted, so
the server-side turn still completes. `setSelectedAgent` also drops the previous
agent's quick replies, requested input field and the processing lock.

**Pause handling (Medium, `awaiting_approval`; compatible with #841).**
- The streaming `awaiting_approval` error code is handled like the non-streaming
  409. The optimistic message and placeholder are rolled back and the pause
  banner is shown, instead of an error appended to the transcript.
- A 409 is no longer read as "paused" on its own. The same status also answers
  "processing another turn", "agent version mismatch" and, with #841, "the
  conversation changed while your message was queued". The chat now reads the
  conversation state. It shows the banner only for `AWAITING_HUMAN`, and
  otherwise toasts the backend's reason (new key `chat.sendRejected`). It keeps
  the old reading if the state cannot be read.

**Drawer talked to the wrong agent (Medium).** The drawer shares the chat store
with the main panel. It now shows and sends only into a conversation of the agent
it was opened for. Its "New conversation" binds the store to that agent first.
The agent page's Chat button passes the environment it starts in, so "New
conversation" no longer restarts a test-only agent in production. Deploy & Chat
names production explicitly.

**Continue in Chat (Medium).** The chat panel ignored `?conversationId=` and
reopened the agent's most recent conversation. It now loads the named one, even
when that agent is already selected.

**Chat picker by id (Medium).** `useDeployedAgents` de-duplicated by name, so two
agents sharing a name collapsed into one entry. It now keys on the agent id.

**Undeploy from the agent card (Low).** The card asks for confirmation first,
with the same "End all active conversations" option as the agent page. A refusal
shows the backend's 409 reason instead of "Undeploy failed".

**Built-in tool whitelist (Medium, wizard + LLM editor).** Deselecting the last
selected tool wrote an empty whitelist, which the backend reads as "no
whitelist", so every tool was enabled. The last selected chip is now disabled and
explains how to turn built-in tools off (new keys `setupWizard.lastToolHint`,
`llmEditor.lastToolHint`).

**Wizard providers (Medium).**
- *huggingface:* `AgentSetupService.createLlmConfig` wrote `modelName`/`apiKey`,
  but `HuggingFaceLanguageModelBuilder` reads `modelId`/`accessToken`, so the agent
  deployed and then failed on its first turn. A dedicated branch writes the right
  names. Sub-agent credential inheritance also reads `accessToken`.
- *gemini-vertex:* the builder reads `modelId`, which is now written, and no key.
  The provider also needs `projectId` and `location`, and neither setup request
  can carry them. The agent wizard and operator activation therefore no longer
  offer it (`isProvisionableBySetup` in [`model-suggestions.ts`](../../ui/manager/src/lib/model-suggestions.ts)).
  A Vertex agent is created with another provider and switched in the LLM editor.

**L5.** The API-agent request never sent `llmBaseUrl`, so an API agent on Ollama
got the default URL whatever was typed. It is sent now.

**L6 (partly refuted in review).** The LLM editor's parameter grid could already
set Jlama's `authToken`, but the wizard could not. It now offers an optional
Hugging Face token for Jlama (sent in the key slot, which the backend maps to
`authToken`). A key is carried across a provider switch only between two
key-taking providers, so a Jlama token never becomes an OpenAI key.

### Decisions and scope notes

- **Studio stale keys** was not changed here. The Studio workflow query key
  missing its version is fixed on `fix/manager-version-after-save`, which owns
  Agent Studio saving. Touching the same lines here would only conflict.
- **Undo/redo** (`useUndoConversation`/`useRedoConversation`) is owned by
  `fix/manager-api-contract` and is untouched. `snapshotToMessages`, which they
  call, now masks secret turns for them too.
- **#839 / #845:** the Manager chat never resumes a pause (it links to the review
  page), and every chat request declares its body length. Neither needed a change.
- **Follow-ups:** the operator chat (`use-operator-chat.ts`, `operator-history.tsx`)
  and the conversation page still rebuild user input from `input:initial`, so the
  same secret leak exists there. The workforce and group wizard provider pickers
  still offer `gemini-vertex`. Adding `projectId`/`location` to the setup requests
  would make Vertex provisionable end to end.

**Tests:** `use-chat-stream-binding`, `use-chat-rejected-send`,
`use-chat-secret-input`, `chat-drawer-binding`, `chat-panel-continue`,
`agent-card-undeploy`, `agent-wizard-providers`, `agent-detail-chat`, new cases in
`resource-detail-llm` and `model-suggestions`, and `AgentSetupServiceBranchCoverageTest`
(huggingface, gemini-vertex). The two existing 409 tests now mock the paused
state they assert.
