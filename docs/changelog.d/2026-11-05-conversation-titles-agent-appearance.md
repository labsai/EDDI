## ✨ feat(conversations): titles, rename and pin; agent appearance and chat controls (2026-11-05)

**Repo:** EDDI (`feat/conversation-titles-agent-appearance`)

The backend half of phase 4 of the frontend unification plan (`planning/frontend-unification-plan.md`, rows B2 and B4; decisions D7 and D9): what a sidebar of conversations and a per-agent home screen need from the server.

### What changed and why

**B2 — conversation titles, rename, pin.**

- `ConversationDescriptor` gains `title`, `titleSource` (`AUTO`, `LLM`, `USER`) and `pinned`. Stored on both backends as plain descriptor fields; every listing that returns descriptors (`GET /conversationstore/conversations`, MCP `list_conversations`) now carries them. A descriptor written before reads untitled and unpinned.
- **Default title** (`ConversationTitles`, `ConversationTitleService`): the first user message that was not secret, whitespace collapsed, control and bidi characters dropped, cut at a word to `eddi.conversations.title-max-length` (default 80). It is written from the turn's completion callback on exactly the turn that holds that message — a walk over the in-memory steps on every other turn, one write on that one. **Never from a secret turn:** a turn is skipped when the client flagged `secretInput` (a secret `inputField` does), when a `scope: "secret"` property vaulted the input, when the input carries a secret context placeholder, or when an input guardrail withheld it. All of it is read after `Conversation`'s turn-end scrub, so the plaintext is never seen; the next usable message titles the conversation, and with none it stays untitled.
- **Rename and pin:** `PATCH /conversationstore/conversations/{conversationId}` with `{"title", "pinned"}` (either). Owner or `eddi-admin` only, through the same strict owner check as delete (`requireConversationOwnerStrict`; reviewers get nothing); `400` for an empty body or a blank or over-200-character title, `404` for a soft-deleted conversation.
- **A rename is never overwritten.** Title writes are one conditional update per backend — `IResourceStorage.setFieldsIf` (Mongo `updateOne` with the guard in the filter, PostgreSQL `data || ?::jsonb` with the guard in the `WHERE`) — with precedence `USER` > `LLM` > `AUTO`: `AUTO` lands only where there is no title, `LLM` only over `AUTO`, the owner's edit always. No read-modify-write, so a model title arriving after the owner renamed cannot clobber it.
- **Model-written titles, opt-in** (`titleGeneration` on the agent, default off): after the first exchange a background virtual thread (at most 8 at once per node; beyond that the default title stays) asks the configured model through the shared `SummarizationService`, as Dream does. Refused by the unpriced-call policy and the tenant cost budget like other model calls; the priced spend is recorded on the tenant budget and reported to spend sinks as the new `SpendEvent.Kind.CONVERSATION_TITLE`, with conversation, agent, version and user. Counted on `eddi.conversation.titles{outcome}` (new Full Metrics dashboard panel).
- **GDPR:** the export's `ConversationExportEntry` carries `title` and `pinned` (live descriptor, else the archived copy of a soft-deleted conversation); erasure already deletes every version of each conversation's descriptor, which is where titles live — now pinned by a test.

**B4 — agent presentation.**

- `appearance {displayName, avatar, accentColor, starters[]}` and `conversationControls {undo, redo, attachments, rating, quickReplies, streaming, rawHtml}` on `AgentConfiguration`, all optional. Validated on every agent write path (REST, ZIP import, sync, setup) by `AgentPresentationValidator`: display name ≤ 80, no control characters; accent `#rgb`/`#rrggbb`; avatar an `https` URL without credentials or a `data:image/png|jpeg|gif|webp;base64` URI ≤ 64 KB (SVG and `http` refused — the browser renders it as `<img>`, EDDI never fetches it); ≤ 6 starters of ≤ 200 characters. `titleGeneration` is validated there too (provider and model required when enabled, `maxLength` 10–200).
- `GET /agents/{agentId}/profile` returns `appearance` (or `null`) and `conversationControls` resolved to booleans — absent switches `true`, **`rawHtml` `false` unless the agent sets it (D9)** — for the deployed version, read through `AgentConfigurationCache`.
- ZIP export/import and agent sync carry the blocks unchanged (they move agent JSON; the export's secret scrub keeps them — tested).
- Manager: a "Presentation & titles" section on the agent page (appearance, controls, model titles), keys in all 11 locales. Platform Operator: the prompt explains appearance, controls, the `rawHtml` default and titles; revision bumped. The `PATCH` is deliberately not in its allow-list — renaming is the conversation owner's act. `eddi-admin` skill mentions the fields.

### Design decisions

- Title derivation runs in the completion callback, not as a lifecycle task: it is a session concern of the turn's outcome, and reading after the scrub is what makes "never from a secret turn" hold for every way a turn becomes secret.
- Controls are presentation only: hiding undo does not disable the endpoint; `rating: false` can hide but not enable ratings.
- `rawHtml` is reported now; the widget keeps rendering HTML until its own phase ships, so agents can opt in first (`docs/chat-ui-embedding.md` → "Upgrading").

### What's next

- **Catalog (#1273):** once both land, `IRestCatalog.CatalogAgent` should carry `appearance` (and probably `conversationControls`) — `AppearanceProfile.of(config.getAppearance())` from the same deployed version the catalog already resolves; the operator revision files of both PRs will conflict and need one more bump.
- **Frontend:** Workforce sidebar (titles, rename/pin via the `PATCH`, grouping by date), home with per-agent starters, and the widget honouring `conversationControls` (including `rawHtml`). A `pinned` filter on the listing would let a sidebar fetch pinned items regardless of paging; not added yet.

**Files:** [`ConversationTitles.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationTitles.java), [`ConversationTitleService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationTitleService.java), [`ConversationDescriptor.java`](../../src/main/java/ai/labs/eddi/engine/memory/descriptor/model/ConversationDescriptor.java), [`IResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/IResourceStorage.java), [`RestConversationStore.java`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java), [`AgentPresentationValidator.java`](../../src/main/java/ai/labs/eddi/configs/agents/AgentPresentationValidator.java), [`IRestAgentProfile.java`](../../src/main/java/ai/labs/eddi/engine/api/IRestAgentProfile.java), [`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java); docs [`agent-config-authoring.md`](../agent-config-authoring.md), [`chat-ui-embedding.md`](../chat-ui-embedding.md), [`rest-api-reference.md`](../rest-api-reference.md), [`gdpr-compliance.md`](../gdpr-compliance.md), [`configuration-reference.md`](../configuration-reference.md), [`metrics.md`](../metrics.md).

```decision-log
| 2026-11-05 | Conversation titles are written with a conditional single-field update and the precedence USER > LLM > AUTO | A background model title must never overwrite the owner's rename, and a read-modify-write would race it | Read-modify-write of the whole descriptor; a version check (conversation descriptors all live at version 0) |
| 2026-11-05 | Default conversation title is read from the turn after Conversation's secret scrub, skipping secretInput, vaulted, secret-context and guardrail-withheld turns | One place that sees every way a turn becomes secret, and never the plaintext | Deriving from the request input before the pipeline (misses scope:secret vaulting) |
| 2026-11-05 | The rename/pin PATCH is not in the Platform Operator's allow-list | Renaming is the conversation owner's act, not an administrative one | Granting it with the other conversation-store reads |
```
