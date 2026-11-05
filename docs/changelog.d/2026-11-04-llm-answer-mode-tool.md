## ✨ feat(llm): structured answers through a terminal reply tool, `answerMode: "tool"` (2026-11-04)

**Repo:** EDDI (`feat/llm-answer-mode-tool`)

### What changed and why

A tool-using agent on Gemini, Anthropic or Bedrock cannot use native JSON mode (the providers reject it
next to `tools`), so its JSON answer was only held to the schema by the prompt block — and in
production 1.6–5.7% of such answers were replaced by the fallback. Function-call arguments, by
contrast, are enforced by every provider. The new opt-in task field `answerMode: "tool"` adds one
synthetic tool, `reply` (`answerToolName`), whose parameters are the `responseSchema`; the model
answers by calling it.

- **Loop** (`ToolLoopRunner`): reply calls are taken out of each model response before the call caps,
  the approval gate, quotas, rate limits, cache and cost tracking see it. A reply ends the loop with no
  further model call; its arguments become the answer text and run through the unchanged
  `convertToObject` path (parse, `responseSchema` / `nonBlankFields`, `onSchemaMismatch`,
  `postResponse`). Reply plus other calls: the others run first; if one pauses for approval the reply
  is discarded (not in the frozen transcript). Duplicate replies: the first wins, WARN once per
  conversation. Traced as step type `answer` without arguments; never a `tool_call`/`tool_result`
  event (those are emitted inside `executeToolCall`, which the reply never reaches — true for the
  current event code and for `feat/resume-stream-tool-events`).
- **Forced call** (`answerToolChoice`, default `required`): `ToolChoice.REQUIRED` on the request for
  `openai`, `azure-openai`, `anthropic`, `gemini`, `mistral` (verified in the langchain4j bindings);
  every other provider gets `auto`, logged at INFO at deploy. `maxToolIterations` still bounds the
  loop; a loop that spends it without a reply ends with the budget text, which goes to `onInvalidJson`.
- **Plain-text answer** goes through the existing invalid-JSON path (local repair → `onInvalidJson`);
  no parallel path.
- **Assembly** (`AgentOrchestrator.buildToolSetup`): the reply tool is added last, with no executor;
  a runtime tool of the same name is dropped with a WARN. The HITL resume, LAZY loading, cascade steps,
  streaming and the format re-ask (`reaskFinalAnswer`) all offer it.
- **Validation** (`AnswerToolConfigValidator`, called from `LlmStore` and at deploy): tool mode needs
  `convertToObject: "true"` and a convertible object-root `responseSchema`; the name must not collide
  with an engine tool or a tool the task names in its whitelist, rate limits, pricing, timeouts, cache
  scopes or quotas.
- **Prompt**: in tool mode the `RESPONSE FORMAT` block tells the model to call the tool instead of
  repeating the schema; no response format is sent on those requests.
- **Metric** `eddi.llm.answer_tool{outcome}`: `reply`, `text`, `duplicate`, `discarded_by_pause`,
  `no_reply`, `collision_dropped`.

### Design decisions

- The reply arguments are returned as the loop's answer *string*, so every downstream policy is the
  existing one — "tool" can never lose an answer "text" would have kept.
- The reply call is removed from the transcript rather than answered with a synthetic result, so a
  pause's frozen transcript and a cascade's carried exchange never hold an unanswered call.
- The "only tool" INFO is logged at first assembly per task, not at configure: httpcall/MCP tools come
  from the workflow, which `configure` cannot see.

**Files:** [`AnswerTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AnswerTool.java),
[`AnswerToolConfigValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/AnswerToolConfigValidator.java),
[`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`AgentOrchestrator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java),
[`LlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java),
[`langchain.md`](../langchain.md#answering-through-a-reply-tool-answermode-tool),
[`llm-resilience.md`](../llm-resilience.md).

**Review follow-ups.** A `modelCascade` on a `tool`-mode task with no other tool ran every step on the
legacy text path, because the cascade's tool-loop gate was `task.isAgentMode()` alone, so the reply tool
was never offered. `CascadingModelExecutor` now also enters the tool loop when `answerMode` is `tool`.
`enableInAgentMode` still gates a task with other tools, because `LlmTask` skips the cascade for those
before this point. A blank `answerToolName` is now saved as the default `reply`, which is how the runtime
already read it; before, it was refused with a 400. `langchain.md` now warns that a tool-mode turn adds the
reply JSON to the output unless `addToOutput` is `"false"`, and tells authors to set that when a
`postResponse` template renders the answer.
