## 🔒 fix(security): `eddi.security.ssrf-protection.enabled` now covers LLM, embedding, vector-store and OpenAPI-discovery targets (2026-10-15)

**Repo:** EDDI (`fix/ssrf-coverage`; backend, docs)

Finding M2 of the 2026-10-15 security review.

### What changed and why

The SSRF switch governed httpcalls, MCP and A2A targets only. Every other endpoint an editor can write into a config — an LLM `baseUrl`, an Azure `endpoint`, an embedding endpoint, a pgvector `host`, a MongoDB `connectionString`, an OpenAPI spec location — was checked against the cloud instance-metadata service alone, protection on or off. With the switch on, an editor who could save an LLM config could still point it at any internal host; a knowledge base could open a raw connection to any host:port, EDDI's own MongoDB or PostgreSQL included, and pgvector would `CREATE TABLE` there. And `POST /apicallstore/apicalls/discover-endpoints` fetched the spec through swagger-parser's `RemoteUrl`, which follows up to five redirects with no check on where they lead.

- **One policy, `OutboundTargetPolicy`** ([`engine/httpclient/OutboundTargetPolicy.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/OutboundTargetPolicy.java)): the metadata service is refused always, as before; with the switch on, `UrlValidationUtils.validateUrl` runs too — http(s) only, no loopback/private/link-local address — with one escape hatch: a URL on this deployment's own origin (`eddi.self.base-url`, or the derived loopback address, via `SelfUrlResolver`) passes, because it is operator configuration and an agent calling EDDI's own `/v1` API is a supported arrangement. `validateHost` is the same rule for a bare `host[:port]` opened as a raw connection (no self escape hatch: EDDI's listener is never a database); an unresolvable host counts as private.
- **Every LLM builder with an endpoint** — OpenAI (and the eight OpenAI-compatible providers through it), Anthropic, Ollama, Mistral, Azure OpenAI, Gemini, Hugging Face, Vertex Gemini — field-injects the policy and runs its `baseUrl`/`endpoint`/`apiEndpoint` through it. A builder constructed with `new` (tests) behaves as with protection off.
- **`EmbeddingModelFactory`**: OpenAI, Azure, Ollama, Gemini, Mistral and Vertex endpoints. **`EmbeddingStoreFactory`**: Elasticsearch `serverUrl` and Chroma `baseUrl` as URLs; pgvector and Qdrant `host`, and every host a MongoDB connection string names (parsed by the driver's own `ConnectionString`, `mongodb+srv` seed names included), as bare hosts. With the switch on, the default `localhost` of pgvector, Qdrant, Elasticsearch, Chroma and Ollama is refused too: such a deployment must name a public address.
- **OpenAPI spec fetch** ([`engine/mcp/OpenApiSpecFetcher.java`](../../src/main/java/ai/labs/eddi/engine/mcp/OpenApiSpecFetcher.java)): `McpApiToolBuilder.parseSpec` no longer calls swagger-parser's `readLocation`. The document is fetched by EDDI through `SafeHttpClient.sendNoRedirect`, redirects followed by hand (at most five, GET only, no credentials) with every hop held to the policy, the body read through `BoundedBodyReader` and refused above 16 MB while streaming. The text is handed to `readContents` with the location as its base, so a multi-file spec's relative `$ref`s resolve as before. `RestApiCallsStore` and `AgentSetupService` inject the fetcher; the fetcher-less `parseSpec`/`parseAndBuild` overloads remain for inline specs and tests and fetch with protection off.
- **`AgentSetupService.createApiAgent`**'s `llmBaseUrl` goes through the same policy, so a URL the model build would later refuse fails at the wizard, before any resource exists.
- **Docs**: [`security.md`](../security.md) SSRF section lists the covered paths and the residual; [`langchain.md`](../langchain.md) OpenAI-compatible `baseUrl` note; [`configuration-reference.md`](../configuration-reference.md) row for the switch.

### Verified, no change needed

`EmbeddingStoreFactory.physicalName` cannot be coerced into another location at build time: a `${...}` reference in the raw `table`/`collectionName`/`indexName` is refused outright, a pgvector name must be a plain (optionally schema-qualified) identifier because the store puts it into SQL unquoted, and the `eddi_kb` prefix is refused at save time (`KnowledgeBaseStorageGuard`). A literal name equal to one of EDDI's own collections or tables is not refused — there is no central registry of those names to check against — but it only matters when the connection string names EDDI's own database, which is a private host and therefore refused when the switch is on. With the switch off, reaching internal databases is the documented choice.

### Residual

`$ref`s inside a fetched spec are still resolved by swagger-parser's own resolver (`setSafelyResolveURL(true)`), which checks each reference's initial URL but follows that URL's redirects itself. Disabling resolution would break multi-file specs; the spec document itself — the editor-controlled location — is now fetched safely.

```decision-log
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
```
