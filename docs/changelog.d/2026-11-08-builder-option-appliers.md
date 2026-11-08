## 🧪 refactor(llm): chat/streaming parity test for every provider builder (2026-11-08)

**Repo:** EDDI (`refactor/builder-option-appliers`)

Every provider builder configures two langchain4j clients, a synchronous one and a streaming one, and the two builder types
share no supertype, so each builder applied its parameters twice, in two method bodies that differed only in the retry
setting. Nothing failed when one of them was edited and the other was not: only Anthropic had a test comparing the two, and
the whole builder package was excluded from the coverage gate.

### What changed and why

- **`ProviderChatStreamingParityTest`** builds both clients of every provider (OpenAI, the eight OpenAI-compatible
  presets, Anthropic, Mistral, Ollama, Gemini, Azure OpenAI and Bedrock) from parameter maps that together set every key the
  builder recognises, and asserts that the two report equal `defaultRequestParameters()` and send the same first request:
  URL, headers, JSON body without the streaming switch, and client timeouts. A key added to `recognisedParameters()`
  without a value in the test fails it, so the check cannot silently fall behind. Bedrock's AWS client cannot be intercepted
  offline and is compared on its request parameters only.
- **Streaming test seams** for OpenAI, Mistral and Ollama (`buildStreaming(…, HttpClientBuilder)`), matching the ones
  Anthropic and Gemini already had, and an `HttpClientProvider` seam for both Azure clients. Production passes the same
  clients as before.
- **`RecordingHttpClient`** records the connect and read timeouts the provider client configures, so the timeout is compared
  too.
- **JaCoCo** now excludes only the two builders whose clients cannot be built offline (Vertex: GCP credentials; OCI:
  `~/.oci/config`), instead of the whole package on the stale ground that it had no logic and needed live keys. The package
  holds the parameter catalog, the value parsing and the bounded HTTP client, all unit-tested.
- The OpenAI and Azure Javadoc no longer claims the two clients "cannot drift"; the test is what checks it.

**Files:** [`ProviderChatStreamingParityTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/builder/ProviderChatStreamingParityTest.java),
[`RecordingHttpClient.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/builder/RecordingHttpClient.java),
[`OpenAILanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java),
[`MistralAiLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/MistralAiLanguageModelBuilder.java),
[`OllamaLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OllamaLanguageModelBuilder.java),
[`AzureOpenAiLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AzureOpenAiLanguageModelBuilder.java),
[`pom.xml`](../../pom.xml)

## ♻️ refactor(llm): provider request parameters built once for both clients (2026-11-08)

**Repo:** EDDI (`refactor/builder-option-appliers`)

With the parity test in place, the request-level settings of each provider builder are now built once, into langchain4j's
own request-parameter type for that provider, and handed to the synchronous and the streaming client through
`defaultRequestParameters(...)`. langchain4j starts every request from those, so the two clients cannot send different
sampling, token, stop, seed, caching or thinking settings any more; what each client builder still receives on its own is
the short list of options that are not part of a request (endpoint, credentials, timeout, logging, headers) or that the
library only accepts there.

### What changed and why

- **OpenAI** (and through it the OpenAI-compatible presets): `OpenAiChatRequestParameters` carries model, sampling,
  token limits, stop, seed, logit bias, reasoning effort, service tier, parallel tool calls, store, metadata, user, prompt
  cache key and the custom body fields. `strictTools`, `strictJsonSchema` and the `json_object` response format stay on
  the client builders.
- **Anthropic**: `AnthropicChatRequestParameters` carries model, `maxTokens`, sampling, stop, thinking type and budget,
  the thinking switches, the cache flags and TTL, `disableParallelToolUse` and `userId`. `thinkingDisplay`, `strictTools`,
  version, beta and the custom body fields stay on the client builders, which are the only place langchain4j takes them.
- **Mistral**: `MistralAiChatRequestParameters` carries everything except `strictJsonSchema` and the connection options.
- **Ollama**: the two hand-kept `applyTo` twins are gone; the parameters are read once into a `Settings` record whose
  `OllamaChatRequestParameters` carries the sampling and context options, `think` and `truncate`.
- **Gemini**: the two `applyExtras` twins are replaced by one `read` into a `Settings` record, so the thinking-config and
  media-resolution parsing (and their warnings) run once per build instead of once per twin. Model, sampling, token limit,
  stop and the cached-content name travel as `GoogleAiGeminiChatRequestParameters`; the rest (seed, thinking config,
  tools, safety settings, media resolution) are options only the client builders take.
- **Azure OpenAI**: Azure has no request-parameter type of its own, so the common sampling settings travel as
  `DefaultChatRequestParameters` and the Azure-specific ones stay on the client builders.
- **Bedrock** is unchanged: it already built one `BedrockChatRequestParameters` for both clients.

No configuration key, default or wire field changes. The requests both clients send, and the models' default request
parameters, were compared field by field before and after the change for every provider, with a full and a minimal
parameter map, and are identical.

**Files:** [`OpenAILanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java),
[`AnthropicLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AnthropicLanguageModelBuilder.java),
[`MistralAiLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/MistralAiLanguageModelBuilder.java),
[`OllamaLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OllamaLanguageModelBuilder.java),
[`GeminiLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/GeminiLanguageModelBuilder.java),
[`AzureOpenAiLanguageModelBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AzureOpenAiLanguageModelBuilder.java)
