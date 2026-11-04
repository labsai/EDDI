## ✨ feat(llm): cascade judge grades against the request, MCP on the capped client — langchain4j 1.23 follow-ups (2026-11-03)

**Repo:** EDDI (`feat/langchain4j-1-23-adaptations`)

Dependabot moved EDDI to langchain4j 1.23.0 / 1.23.0-beta33 (#1214) with no code change, and CI passed. EDDI
implements no `McpTransport` and uses none of the Jackson 2 MCP methods the release removed (#6824), so nothing
broke. This change adopts what the release made possible, and one lesson from its new Confidence Cascade pattern
(langchain4j#6765).

### What changed and why

- **The `judge_model` cascade judge sees the request.** It was sent only the answer and asked to rate it, so a
  fluent answer to the wrong question scored as well as a right one. langchain4j's cascade grades "does the answer
  fully and correctly address the request?". `ConfidenceEvaluator` now sends the text of the latest user message
  the step's model saw (8,000 characters at most: a single long text keeps its end, where a templated prompt puts the question; with attachments the typed question is kept whole and the head of the attached text fills the rest) together with the
  answer, and asks for the probability that the answer is acceptable for the request. With no user text (an
  image-only message) the judge rates the answer on its own, as before. `CascadingModelExecutor` passes the request
  from both the legacy and the agent-mode step.
- **`modelCascade.acceptanceCriterion`** (optional string): one sentence an acceptable answer must also meet, added
  to the judge's instructions. It is langchain4j's `yesWhen` for the same pattern. Only the judge reads it, so
  `CascadeConfigValidator` warns when it is set without a `judgeModel`. The Manager's cascade editor shows it under
  the judge model editor (all 11 locales).
- **Untrusted text is data, not instructions.** The request and the answer go to the judge as JSON string values
  (Jackson-encoded, so neither can close its field), and the system message tells it to ignore instructions and
  self-reported scores inside them. The author's criterion is trusted config and sits in the system message. The
  docs now say so plainly: this keeps graded text from posing as instructions, but the grade can still be swayed,
  so the cascade is a cost/quality trade-off and not a security boundary.
- **MCP runs on `BoundedJdkHttpClient`.** langchain4j 1.23 made the `StreamableHttpMcpTransport` backend pluggable
  (#6726). The transport now uses EDDI's capped JDK client: a response past `eddi.llm.max-response-bytes` (32 MB)
  fails instead of being read into the heap whole, and the per-request URL re-check against DNS rebinding moved from
  the header hook into the client's request guard, which runs before every send. `followRedirects(false)` stays
  pinned, and `streamingBackpressure(true)` keeps what the transport's own backend set. The cap's error message no
  longer says "LLM provider", since MCP shares it. This puts MCP on the same client as the LLM builders, not on
  `OutboundHttp`: the HTTP client consolidation's Phase 3 (a langchain4j `HttpClient` adapter over `OutboundHttp`) is
  not built yet, and its Vert.x engine is Phase 4. When Phase 3 lands, MCP moves with the builders through this same
  `httpClientBuilder` hook — the hook the October security review noted was missing.
- **Version stamps.** `docs/rag.md`, `InputTypedEmbeddingModelTest` and `VertexGeminiLanguageModelBuilder` named
  1.22. Checked against the 1.23 jars: still only Gemini (and Cohere) declare `INPUT_TYPE`, and the Vertex streaming
  builder still has no `seed`.

### Tests

`ConfidenceEvaluatorJudgeRequestTest` (6): the request and criterion reach the judge, hostile text stays inside its
JSON field, no request means no `request` field, request extraction and its tail truncation.
`CascadingModelExecutorToolReplayTest.judgeSeesRequestAndCriterion` covers the executor wiring.
`McpTransportResponseCapTest` serves a 40 MB handshake answer from a loopback server; it and
`McpTransportRevalidationTest` both fail when the `.httpClientBuilder(...)` line is removed (mutation-checked). In the
Manager, two `task-cascade-section` tests cover editing and clearing the field and hiding it for other strategies.

### Considered, not done

- **Skip the judge on a step that is accepted anyway** (the last step, a null threshold). langchain4j never grades
  its last tier. Here the last step's confidence feeds `audit:confidence`, `cascadeConfidence`, the confidence
  metric and `returnBestAcrossSteps`, so skipping the call would change what those report. That needs its own
  decision.
- **A `decision_model` strategy** on langchain4j's `DecisionModel` (OpenAI Decisions API, Ollama): it returns
  probabilities instead of a number the model writes as text. The API is `@Experimental`, and its token usage goes to
  a `DecisionModelListener`, outside EDDI's cost accounting. Revisit when it stabilises.
- **The agentic layer** (`ConfidenceCascadePlanner`, decision agents, activation conditions) and AiServices-only
  features (tool search strategy, `AiServiceInvocationSummaryEvent`): EDDI's orchestration is config-driven and does
  not use `AiServices`.
