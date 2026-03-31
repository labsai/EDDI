# LLM Provider Expansion — Final Plan ✅ COMPLETED (2026-03-27)

Expand EDDI from 7 to 12 model providers. All providers ship together for enterprise completeness.

> **Status:** Fully implemented, code-reviewed, tested, and documented. See `docs/changelog.md` for details.

## Native Image Compatibility Assessment

| Provider | Native Risk | Notes |
|---|---|---|
| **Mistral** | ✅ None | Same `langchain4j-http-client-jdk` as OpenAI/Anthropic |
| **DeepSeek** | ✅ None | Just a `baseUrl` on existing OpenAI builder |
| **Bedrock** | ✅ Low | AWS SDK v2 has built-in GraalVM support (auto-generates reflection config since v2.16.1). Quarkus has `quarkus-amazon-bedrock` extension |
| **Oracle GenAI** | ✅ Low | OCI SDK v3+ is on GraalVM's tested-libraries list. Has `oci-java-sdk-addons-graalvm` addon |
| **Azure OpenAI** | ⚠️ Medium | Azure SDK includes some GraalVM config, BUT `azure-ai-openai` uses Jackson+Kotlin with heavy reflection. A [July 2025 GH issue](https://github.com/openai/openai-java/issues/463) reports native image difficulties. May need native-image agent tracing to generate reflection metadata |
| **Cohere** | ✅ None | No new dependency (OpenAI-compatible baseUrl) |

> **⚠️ Azure OpenAI** is the only provider with non-trivial native image risk. The Azure SDK includes reflection config but the `openai-java` dependency uses Kotlin+Jackson reflection heavily. Mitigation: ship now for JVM mode, fix native config during Phase 12 CI/CD (native-image agent tracing during integration tests).

---

## Changes

### 1. OpenAI `baseUrl` — enables DeepSeek + Cohere (zero dependency)

**File**: `OpenAILanguageModelBuilder.java`
Add `baseUrl` param to `build()` and `buildStreaming()`. Enables any OpenAI-compatible API.

---

### 2. Mistral AI (clean, lightweight)

**Dependency**: `langchain4j-mistral-ai` / `${langchain4j-libs.version}`

**New file**: `MistralAiLanguageModelBuilder.java`
- Uses `httpClientBuilder(JdkHttpClient.builder())` ✅
- `MistralAiChatModel.builder()` / `MistralAiStreamingChatModel.builder()`
- Params: `apiKey`, `modelName`, `temperature`, `maxTokens`, `timeout`, `logRequests`, `logResponses`

---

### 3. Azure OpenAI (Azure SDK HTTP pipeline — NOT JdkHttpClient)

**Dependency**: `langchain4j-azure-open-ai` / `${langchain4j-libs.version}`

**New file**: `AzureOpenAiLanguageModelBuilder.java`
- ❌ No `httpClientBuilder()` — Azure SDK manages HTTP (Netty-based)
- Uses `deploymentName` not `modelName`
- Uses `logRequestsAndResponses` (combined) not separate flags
- Mandatory: `endpoint` (format `https://{resource}.openai.azure.com/`)
- Auth: `apiKey` (Azure) or `nonAzureApiKey` (regular OpenAI) or `tokenCredential` (Entra ID)
- `AzureOpenAiChatModel.builder()` / `AzureOpenAiStreamingChatModel.builder()`

---

### 4. Amazon Bedrock (AWS SDK — credential chain, no API key)

**Dependency**: `langchain4j-bedrock` / `${langchain4j-libs.version}`

**New file**: `BedrockLanguageModelBuilder.java`
- ❌ No `apiKey` — uses AWS default credential chain (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, IAM roles)
- ❌ No `httpClientBuilder()` — AWS SDK manages HTTP
- `region` param → convert String to `Region.of(string)`
- `modelId` — e.g., `anthropic.claude-v2`, `meta.llama3-70b-instruct-v1:0`
- `BedrockChatModel.builder()` / `BedrockStreamingChatModel.builder()`

---

### 5. Oracle GenAI (OCI SDK — OCI auth)

**Dependency**: `langchain4j-community-oci-genai` / `${langchain4j-beta.version}`

**New file**: `OracleGenAiLanguageModelBuilder.java`
- ❌ No simple `apiKey` — uses OCI `ConfigFileAuthenticationDetailsProvider` (reads `~/.oci/config`)
- Params: `modelId`, `compartmentId`, `configProfile` (OCI profile name, default "DEFAULT")
- `OciGenAiChatModel.builder()` — sync only (no streaming model in current version)

---

### 6. Module Registration

**File**: `LlmModule.java`

| Type Key | Builder |
|---|---|
| `mistral` | `MistralAiLanguageModelBuilder` |
| `azure-openai` | `AzureOpenAiLanguageModelBuilder` |
| `bedrock` | `BedrockLanguageModelBuilder` |
| `oracle-genai` | `OracleGenAiLanguageModelBuilder` |

---

## Verification

1. `mvnw compile` — zero errors
2. `mvnw test` — all existing tests pass
