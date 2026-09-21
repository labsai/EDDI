## 🐛 fix(llm): Gemini 3.x could not use tools at all — thought signatures were dropped (2026-09-18)

**Repo:** EDDI (`fix/gemini-thought-signatures`, branched from `origin/main` @ 798c6e84d)

Every Gemini 3.x model was unusable with tools. On a production agent using function calling on
`gemini-3.8-flash`, the first tool call failed with `400 INVALID_ARGUMENT — Function call is missing
a thought_signature in functionCall parts`. Gemini 3.x attaches an opaque `thoughtSignature` to
`functionCall` parts and requires it echoed back when that model turn is replayed on the follow-up
request carrying the `functionResponse`. `gemini-3.8-flash` and `gemini-3.5-flash` reject the replay
without it, `gemini-2.5-flash` tolerates it, and `thinkingBudget: 0` does not help — measured table
in [`langchain.md`](../langchain.md).

### Where the fix landed, and why

langchain4j 1.20.0 **already models the field**, so this is neither an upgrade nor an adapter. It
gates both halves behind builder flags that default off: `PartsAndContentsMapper` captures the
signature into `AiMessage.attributes()["thinking_signature"]` only when `returnThinking == TRUE`,
and re-sends it only when `sendThinking == true`. EDDI set neither. Four places dropped the field:

1. **`GeminiLanguageModelBuilder`** — both flags now default **true** on `build` and
   `buildStreaming`, overridable as `recognisedParameters`. A default rather than opt-in: this is
   protocol correctness, not something an agent designer should learn from a 400.
   `ModelParameterValues.booleanValue(params, key, default)` reads it, so a typo falls back to the
   default instead of `Boolean.parseBoolean`'s silent `false`.
2. **`ToolApprovalGateSupport.normalizeToolCallIds`** rebuilt the message with `AiMessage.from(...)`,
   which carries only text and requests, so enabling the tool-approval gate alone broke Gemini 3.x
   again. Now `toBuilder()`. Blank text still collapses to null: the message is replayed, the Gemini
   mapper sends whitespace-only text as its own part, and Anthropic rejects such blocks.
3. **`gatingAssistantMessageOf`** read `getLast()`, but in a mixed batch the ungated calls execute
   and append their results *before* the pause is snapshotted, so it found nothing. It now walks
   back over this batch's tool results, stopping at anything else so it cannot borrow an earlier
   turn's message. `interimTextOf` had the same pre-existing blind spot — approvers lost the model's
   narration on mixed batches — and shares the walk.
4. **`ToolLoopResumer`, degraded resume** (transcript over its byte cap) replayed a bare
   `AiMessage.from(requests)`. `PendingToolCallBatch.gatingAssistantMessageJson` now keeps the
   gating message — written only when the transcript was omitted (a kept transcript already
   carries it, and a codec change would break both copies alike), capped at 64 KB on its own —
   never by the transcript's cap, since a small transcript cap is what triggers this path — shedding text
   then thinking before its attributes. `gatingExchange` replays it **unchanged**, original parts in
   original order, answering each ungated call with `HANDLED_BEFORE_PAUSE` — handled, not "ran",
   since an ungated call may have been refused or failed, and the outcome is what this path lost.

**Why replay the original parts** rather than rebuild from the gated calls: Gemini signs part 0 of
a parallel batch. Measured once against the live Gemini API (3.8 and 3.5 Flash), moving that
signature onto a different call was accepted — as were the original parts in order; only an
unsigned replay was rejected. So the rebuild works today, on undocumented leniency that would fail
on the rarely exercised degraded path if Google tightened it. Replaying what the model emitted
stays valid, matches the shape the primary resume path already produces, and records the ungated
call.

**Security.** The new field embeds the gated calls' raw arguments — the content
`sanitizePendingToolCallsForApprover` strips `argumentsRaw` for. The first cut excluded it from the
names-only projection but not from the approver `detail=full` surface; it is now dropped in
`stripRequestFingerprintsForRead`, which every full-detail read calls (REST through the approver
sanitizer, the MCP approval-status tool directly), and named in the sanitizer too. Canaries pin all
three projections. *Pre-existing and out of scope:* the MCP `detail=full` path applies only the
fingerprint strip, so it already serves `chatTranscriptJson` and `argumentsRaw` today; only the new
field is closed there.

**Multi-turn.** The HITL tool pause is the only place a tool-carrying model turn crosses a request
boundary and a MongoDB write; `AiMessage.attributes()` round-trips langchain4j's codec. Cross-turn
replay needs no signature: `ConversationHistoryBuilder` rebuilds prior assistant turns as text only.

**Precondition, recorded where the flags are set.** The mapper joins every part's signature into
one attribute and re-sends it on the first `functionCall`. Measured once, every turn shape carried
exactly one signed part (a narrating text part is unsigned). Thought parts could add more — they
appear only with `thinkingConfig`, which EDDI does not expose; re-check before exposing it.

### Tests

`GeminiThoughtSignatureTest` swaps in langchain4j's own `HttpClient` through a package-private
builder seam, so the real model, mapper and codec run with only the socket replaced. The stub
**enforces** Gemini's rule (an unsigned `functionCall` gets the real 400) and an opt-out test proves
the check is live. Streaming is driven over two SSE frames, so attributes merge across frames.
Mutations, each failing the intended test: both flags removed; `sendThinking` only (the verbatim
400); `sendThinking` in `buildStreaming` only; `normalizeToolCallIds` back to `AiMessage.from`;
`gatingAssistantMessageOf` back to `getLast()`; the bare degraded rebuild; the field written with
a kept transcript; either leak strip removed; the Vertex warning call removed from `build()`.

**Live run of the patched build** (`5be8d02f1`, real Gemini API, `gemini-3.5-flash`, an agent with the
calculator and datetime built-in tools and a system prompt forcing tool use; the same driver script run
against both builds):

| Scenario | Patched `5be8d02f1` | Unpatched 6.4.0 release image |
| --- | --- | --- |
| Non-streaming turn 1 (tool call) | correct, READY | ERROR |
| Non-streaming turn 2 (history holds a tool turn) | correct, READY | ERROR |
| Non-streaming turn 3 (two tool calls in one turn) | correct, READY | ERROR |
| Streaming SSE tool turn | correct, READY (`task_start`, `tool_call`, `token`, `task_complete`, `done`) | ERROR (`task_failed`) |
| HITL `requireApproval: ["builtin:*"]` — pause before the tool runs | AWAITING_HUMAN | AWAITING_HUMAN |
| HITL — resume with APPROVED | correct, READY | ERROR |
| **Total** | **6/6** | **1/6** |

The unpatched container logged 33 Gemini 400s, "Function call is missing a thought_signature in
functionCall parts". Not covered live: the degraded resume path (transcript over its cap), which is
exercised only by the unit tests.

### Provider survey — the same defect class elsewhere

| Provider | Verdict |
| --- | --- |
| `gemini` | was live — fixed here |
| `gemini-vertex` | **live, not fixable in EDDI.** `langchain4j-vertex-ai-gemini:1.20.0-beta30` has no `thought`/`thinking`/`signature` anywhere, and the `Part` protobuf it uses (`proto-google-cloud-vertexai-v1:1.27.0`, via `google-cloud-vertexai`) has no `thought_signature` field. Needs upstream changes; `build()` now warns for Gemini 3.x ids, bare or fully qualified, naming `gemini` |
| Anthropic, Bedrock | **latent.** Signed thinking blocks, modelled by langchain4j under the same `thinking_signature` key, but no config key enables extended thinking, so no signed block is ever returned. Set `returnThinking(true)` when that is exposed; fixes 2–4 already apply |
| OpenAI, Azure OpenAI | fine — Chat Completions has no opaque reasoning token; EDDI never uses the Responses API |
| Mistral, Ollama | fine — plaintext thinking, no signature |
| HuggingFace, Jlama, Oracle GenAI | fine — no reasoning concept in the modules |

### Files

`GeminiLanguageModelBuilder`, `ModelParameterValues`, `VertexGeminiLanguageModelBuilder`,
`ToolApprovalGateSupport`, `ToolLoopResumer` (`gatingExchange`), `ChatTranscriptCodec`,
`PendingToolCallBatch`, `ConversationMemoryUtilities` (the strip and the sanitizer);
`docs/langchain.md`, `docs/hitl.md`; tests `GeminiThoughtSignatureTest`,
`ToolApprovalGateSupportNormalizeTest`, `ToolLoopResumerGatingMessageTest`,
`VertexGeminiVersionWarningTest`, plus additions to `ModelParameterValuesTest` and
`ConversationMemoryUtilitiesHitlTest`. `AgentOrchestratorCoverageTest`'s mixed-batch fixture was
given the runtime message order for accuracy; it guards nothing new.

`LanguageModelBuildersTest` shows 13 sandbox-only errors (`Unable to establish loopback connection`
from `JdkHttpClient`) — identical on a clean tree.

```decision-log
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
```
