## 🔒 fix(llm): review findings in the LLM module periphery — secrets, SSRF, tool limits (2026-10-31)

**Repo:** EDDI (`fix/review-llm-tools`)

### What changed and why

Fixes from the two external reviews of revision `01c3250` (CodeRabbit security scan and the code-review report), for the built-in tools, model builders and embedding/A2A tool providers.

**Secrets and destinations**

- **Embedding/vector-store errors no longer quote parameter values.** Parameters reach the factories with vault references already resolved, and RAG search responses and ingestion status return the error message to the editor: a `${vault:…}` reference put in `timeout`, `dimensions` or a store's `port` came back as plaintext. The messages now name the parameter only (and drop the `NumberFormatException` cause, whose message quotes the input); the `model`/`modelName` conflict check runs before resolution.
- **Vertex `apiEndpoint` is an endpoint key** for `allowedHosts` (`ChatModelRegistry.ENDPOINT_KEYS`), and an **OpenAI-compatible preset without `baseUrl`** is checked against the URL its editor-chosen `region` selects (new `ILanguageModelBuilder.defaultEndpoint`).
- **Secret invalidation reaches models built through `${vars:…}`**: the cache key holds unexpanded parameters, so every variable-bearing chat model is evicted with a changed secret.
- **Embedding model and store caches use structural keys** instead of `TreeMap.toString()`, which could render two different parameter maps identically and share one client.
- **Cached model clients re-check their destination before every request.** `BoundedJdkHttpClient` takes the `OutboundTargetPolicy` and validates each request URL (OpenAI, OpenAI-compatible, Ollama, Anthropic, Gemini, Mistral; OpenAI and Ollama embeddings) — a hostname validated once at build was otherwise re-resolved unchecked for the model's whole cache life (DNS rebinding). `docs/security.md` gains a row for this path and names what stays build-time only (other SDK transports, MongoDB Atlas replica-set discovery).

**Tools and providers**

- `WebScraperTool` and `PdfReaderTool` send model-chosen URLs `STRICT`, not `HOPS_ONLY`, so the first hop gets the facade's post-connect re-check. `OpenApiSpecFetcher` (policy `NONE`, own loop) re-validates each hop after the response arrives. `WeatherTool`/`WebSearchTool` keep `HOPS_ONLY`: their hosts are fixed.
- `WebScraperTool.extractWithSelector` refuses CSS escapes (jsoup decoded `:\6d atches` into a regex selector) and sibling combinators `~`/`+` (quadratic evaluation the parse timeout cannot interrupt); the same characters in quotes, attribute selectors, `:contains(…)` text or `:nth-*` formulas are not combinators and still pass (unterminated quotes or brackets fail closed).
- `TextSummarizerTool` splits sentences with a linear pattern (the old one was quadratic on a punctuation run without whitespace). `DataFormatterTool.csvToJson` caps rows and output size; `extractJsonValue` reports a missing/non-array field instead of an NPE.
- `AttachmentTextExtractor` extracts with a bounded stripper: an operator budget, a deadline and interrupt checks (repeated unfiltered forms spent no decode budget).
- A2A discovery bounds agent-name/skill-id length and skill count, logs duplicate skills once per card, and skips a tool name another peer already registered (the later peer's executor used to replace the first's under the first's spec).
- `find_agents_by_capability` filters by the caller's USE rule, as the REST search does. `converse_with_agent` carries the chain's depth ceiling (`delegationDepthMax`, inside the reserved context namespace) so a delegate cannot extend a chain the originator capped.
- Group artifact and task tools honour a member's non-empty `builtInToolsWhitelist` (slugs `artifacts`, `group_tasks`). **Behaviour change on upgrade:** a member whose LLM task already has a non-empty whitelist stops receiving these tools in groups that enable them until `artifacts` / `group_tasks` is added to that list. The Manager's tool picker (`BUILT_IN_TOOLS` in `ui/manager`) now offers both slugs, and switching a task to whitelist mode there selects them along with the rest — they are self-gating, so they enable nothing outside a group that asks for them.
- `FetchToolResponsePageTool` is marked `@ConversationBound` and no longer served from the tool cache.
- `UserMemoryTool` re-checks cancellation right before writing and takes back an insert that landed after the turn was cancelled (erasure race).
- Negative rate limits are refused at save time (`ToolRateLimiter.validateLimits`, called from `LlmStore`); `0` blocks a tool.
- `ObservableChatModel`/`ObservableStreamingChatModel` log model responses at DEBUG, like requests.
- The confidence judge's JSON extraction delegates to the new `ModelOutputParser.extractFirstObject`. Removed the unused `CustomToolConfiguration`.

### Design decisions

- Redaction instead of rejecting vault references in non-credential fields: the leak was the echo, and rejecting would break any config that legitimately keeps e.g. a host in the vault.
- The per-request destination check is the MCP transport's approach (pre-send check, cache-expiry residual), not address pinning — the JDK client has no resolver hook.
- `DataFormatterTool` and `OpenAiCompatibleLanguageModelBuilder.putPath` keep their own path walkers: `PathNavigator` works on maps with `+`/`-` arithmetic (a hyphenated JSON key would resolve to `null`) and its `setValue` neither creates intermediate maps nor copies shared ones.

### Deferred

- Jlama loader allocation/path-traversal findings are in `jlama-core` 0.8.4 (third party); needs an upgrade or an operator allow-list of model repositories.
- MongoDB Atlas replica-set discovery is not re-checked against the outbound policy (documented); forcing `directConnection` would break `mongodb+srv` clusters.
- A store-level erasure generation for user memories (needs `IUserMemoryStore` and `GdprComplianceService` changes).
- Provider-builder `build()`/`buildStreaming()` duplication: langchain4j's separate builder types make a shared helper no cleaner.

**Files:** [`ChatModelRegistry.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java), [`EmbeddingModelFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java), [`EmbeddingStoreFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java), [`BoundedJdkHttpClient.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/BoundedJdkHttpClient.java), [`A2AToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java), [`WebScraperTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/impl/WebScraperTool.java), [`security.md`](../security.md), [`secrets-vault.md`](../secrets-vault.md), [`langchain.md`](../langchain.md), [`group-conversations.md`](../group-conversations.md)
