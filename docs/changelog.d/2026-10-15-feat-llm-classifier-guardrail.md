## ✨ feat(llm): `classifier` guardrail type — a safety-classifier model as configuration (2026-10-15)

**Repo:** EDDI (`feat/llm-classifier-guardrail`, stacked on `feat/llm-input-output-guardrails`) — WS4 Phase 4b.

### What changed and why

The 4a guardrails are deterministic heuristics. Phase 4b adds one generic model-backed type, `classifier` (input and output), that asks a safety-classifier model for a verdict, so Granite Guardian, ShieldGemma, a hosted moderation model or a TrustyAI-served detector are **configuration values, not code**. EDDI ships and endorses no model.

- **`ClassifierGuardrail`** (`modules/llm/guardrails/impl`) — a `ContentGuardrail` bean, picked up by `GuardrailService`'s type index like every other. Parameters: `provider` (an EDDI provider type) plus that provider's own model parameters in a task's vocabulary (`baseUrl`, `apiKey` — a `${vault:…}` reference, resolved and grant-checked as for a task — `modelName`/`model`, `customParameters`, `maxTokens`, …), and its own keys `promptTemplate`, `risk`, `unsafeLabel` (default `yes`), `safeLabel`, `labelPattern`, `timeoutMs` (default 3000, cap 60000), `includeRetrievedContext` (output), `inputPricePer1M`/`outputPricePer1M`, and the generic `failMode`.
- **Transport is `ChatModelRegistry.getOrCreate`** (made public), not `SafeHttpClient`: classifier endpoints are in-cluster private addresses, the same trust class as a task's model `baseUrl`; the registry also brings vault/global-variable resolution, the model cache and LLM telemetry. `timeoutMs` becomes the provider `timeout` (a stable cache key), and the call itself runs on a virtual thread bounded by `min(timeoutMs, turn deadline − reserve)` (#996); a turn already out of time does not call the classifier.
- **Prompt:** without `promptTemplate` the text goes as the model's native conversation (input: the user message; output: question + answer as an assistant message), which is what Granite Guardian on vLLM and ShieldGemma on Ollama expect — their chat templates build the classification prompt. With `promptTemplate`, the template is rendered by the restricted Qute engine with the screened text as **data** only (`{input}`, `{output}`, `{risk}`, `{context}`) — never as template source.
- **Verdict:** label = first capture group of the **last** `labelPattern` match (Granite's `<score> yes </score>`, after an optional `<think>` trace), else the first word with punctuation trimmed; compared case-insensitively with `unsafeLabel` / `safeLabel`.
- **Could not decide** → new `GuardrailUndecidedException(reason, costUsd)`: `GuardrailService.evaluate` now returns an internal `Evaluation` and the three failure sites (input, history, output) record the exception's reason (`classifier_unavailable`, `classifier_unparseable`) instead of `guardrail_error`, and add its cost. Everything else about failMode is 4a's single code path (closed by default for `block`/`reprompt`).
- **Cost:** priced with `TokenPricing` from the classifier's token usage into `GuardrailVerdict.costUsd`, which 4a's `accumulateCost` folds into `audit:cost` (WS2's tenant metering reads that key) — including the cost of an unparseable answer.
- **Context:** `GuardrailContext` gains `retrievedContext` (this turn's vector-store + `httpCallRag` context, output phase) and `deadline`; the old six-argument constructor stays. `LlmTask` collects the retrieved context it already appends to the system message and passes both. The HITL resume path has no retrieved context (null).
- **Metric:** timer `eddi.guardrail.classifier.duration{provider, outcome}` (provider tagged only after the registry accepted it, so bounded; outcome `safe|unsafe|unparseable|unavailable|timeout`); `eddi.guardrail.triggered` unchanged. Both now have panels on the Full Metrics dashboard — 4a's counter had none, which `MetricsDashboardCoverageTest` flagged.
- **Docs:** [langchain.md](../langchain.md#the-classifier-type-a-safety-classifier-model) (parameters, template safety, could-not-decide, cost/latency, worked examples for Granite Guardian 3.3 on vLLM and ShieldGemma on Ollama), [security.md](../security.md) (the failMode availability trade-off, transport trust), [metrics.md](../metrics.md), [audit-ledger.md](../audit-ledger.md). Operator prompt explains configuring a classifier (it never writes the vault reference itself); revision stays 17 (main is at 16), fingerprint updated. Manager `GuardrailConfig` doc comment lists the type.

### Design decisions

- **`screensHistory()` is false**: a model call per earlier history message on every turn would multiply the cost by the window size; each message was classified on its own turn.
- **`promptTemplate` optional** (the plan had it required): the two documented models expect the raw conversation and build their own prompt; a wrapper prompt would be classified as content.
- **`labelPattern` added** (not in the plan): Granite Guardian answers inside a `<score>` tag, which first-token matching cannot read. It is author config, compiled through 4a's bounded `PatternCache`.
- **Safe is a positive match, never a default** (review follow-up). An answer is safe only when it matches `safeLabel`; a refusal, an HTML error body, `maybe` or a truncated reply is undecided and follows `failMode` (closed by default). `safeLabel` defaults to `no` when `unsafeLabel` is left at `yes`; a custom `unsafeLabel` without `safeLabel` fails the deploy — `LlmTask.configure` now asks each guardrail type to validate its parameters (`ContentGuardrail.validate`, default: nothing to check; unknown types still only WARN at run time). A missing `provider` is refused the same way.
- **`labelPattern` runs over the last 4 KB of the answer** (the first word over the first 4 KB), so an author regex never scans an unexpectedly huge reply.
- **Llama Guard:** not given a worked example. Meta's Llama 3.2/3.3/4 acceptable-use policies withhold the licence rights for their multimodal models from EU-domiciled companies, which covers Llama Guard 4 and Llama Guard 3 Vision; the text-only Llama Guard 3 is mentioned with that caveat, and ShieldGemma is the Ollama example. EDDI takes no position on any model's licence.
- **Provider error messages are never logged or propagated** (they can echo the screened text); only the exception class.

### Manager

- The guardrails editor from the merged Manager PR offers `classifier` again on both sides (`GUARDRAIL_TYPES`, phases, no-rewrite set and type text in `guardrails.ts`); its settings panel and warnings were already in place. The editor test that asserted it was not offered now asserts it is. `operator-revision.json` is bumped for the operator prompt change.

### Not done

- The Granite Guardian `chat_template_kwargs` example is per the model card and vLLM's request extension; it was not run against a live vLLM here.
