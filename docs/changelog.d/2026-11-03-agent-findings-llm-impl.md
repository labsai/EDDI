## 🔒 fix(llm): agent-findings batch llm-impl — streaming suppression, linear reference escaping, budget accounting, rebinding re-checks (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-llm-impl`)

### What changed and why

Verified the 39 CodeRabbit agent findings against `modules/llm/impl/**` (scans of 2026-10-26 … 11-02) on current `main`. Verdicts: 10 fixed here, 17 already fixed (#1160, #1163, #1190, #1192, #1194), 8 accepted risk (documented residuals), 4 need a decision.

- **`addToOutput: false` no longer streams the raw answer.** The plain legacy branch built a streaming model and handed it the caller's event sink even when the task suppresses its raw output, so intermediate tokens reached an SSE caller before `postResponse` chose what to show. Such a task now takes the plain, buffered path (`LlmTask`).
- **Configuration-reference escaping is linear.** `escapeConfigReferenceMentions` ran `CONFIG_REF_MENTION.replaceAll`, which re-scans to the end of the value from every unclosed `{vault:` opener: an editor-written prompt full of them cost quadratic CPU on every turn, before any template limit. It is now a forward scan with the same matches; a mention is copied literally (the regex replacement also read `$`/`\` in a mention as group references).
- **An enforced tool budget is charged even with `enableCostTracking: false`.** The ceiling is checked against the cost tracker, but turning tracking off also stopped charging it, so the ceiling never moved. A task with an enforced `maxBudgetPerConversation` now always records the charge (`ToolLoopRunner`), a `summarize` truncation's model call included.
- **An endpoint key the builder does not read no longer skips its default's `allowedHosts` check.** `ChatModelRegistry` returned as soon as any endpoint-like key was set, so an `endpoint` value on an OpenAI-compatible preset (which reads `baseUrl` only) let the `region`-selected URL through unchecked. The builder's default is now checked whenever it reports one.
- **Self-conversation check decodes JSON.** The raw-argument fallback compared the serialized arguments with the conversation id; an id written with a JSON unicode escape passed the check and was decoded by the executor. The arguments' string values are now checked after JSON decoding as well (`ToolLoopResumer`, live and resume path).
- **Mistral and Gemini embeddings and the Chroma store re-check their destination per request.** They now use `BoundedJdkHttpClient` with the outbound policy, like OpenAI/Ollama embeddings (DNS rebinding on cached clients).
- **A MongoDB connection string's `proxyHost` is checked** like its seeds — for SSRF and as an `allowedHosts` destination; the driver dials the proxy and sends it `proxyUsername`/`proxyPassword`.
- **An invalid Chroma `apiVersion` no longer echoes its value** (resolved vault references reach this message in RAG search responses and ingestion status).
- Docs: [`security.md`](../security.md) (DNS-rebinding table, cost tracking) and [`secrets-vault.md`](../secrets-vault.md) (`allowedHosts` scope).

### Design decisions

- Fixed-vendor defaults (OpenAI, Anthropic, Mistral … with no endpoint key) stay unchecked against `allowedHosts`, as `secrets-vault.md` documents: the destination is a vendor host, not one the editor controls. Changing that is a contract change (every builder would report its fixed host, and existing secrets whose list omits the vendor host would stop working).
- The per-request re-check is a pre-send check with the documented cache-expiry residual, not address pinning: the JDK client has no resolver hook.

### Needs a decision (not implemented)

- **Jlama loader** (`jlama-core` 0.8.4): header allocation before a bounds check and unchecked shard paths in a downloaded index. Options: an operator allow-list of Hugging Face repositories for `jlama`, waiting for an upstream fix, or disabling `jlama` by default.
- **Elasticsearch (and other) store transports are not closed** when the store cache is invalidated; each secret or variable change followed by a rebuild leaves a live REST client behind. Options: share transports per endpoint/credential like the pooled `MongoClient`s, or close retired stores after a grace period.
- **HITL resume fallback** rebuilds messages from memory (which holds the input as sent) when the paused transcript was omitted, without re-running the task's input guardrails. Options: re-run the input guardrails on the rebuilt messages, or refuse model continuation when the screened transcript cannot be restored.

**Files:** [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`ChatModelRegistry.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`ToolLoopResumer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java), [`EmbeddingModelFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java), [`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java)
