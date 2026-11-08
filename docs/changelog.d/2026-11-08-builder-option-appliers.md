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
