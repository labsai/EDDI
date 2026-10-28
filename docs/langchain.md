# LLM Integration

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

## Overview

The **LLM Lifecycle Task** (formerly "Langchain") is EDDI's unified integration point for Large Language Models (LLMs).

By default, it provides **simple chat** with any LLM provider. Optionally, you can enable **agent mode** to give your LLM access to built-in tools (calculator, web search, weather, etc.).

EDDI supports **19 named LLM providers** out of the box — eleven model builders (OpenAI, Anthropic, Google Gemini (`gemini`), Google Vertex AI (`gemini-vertex`), Mistral AI, Azure OpenAI, Amazon Bedrock, Oracle GenAI, Ollama, Hugging Face and Jlama) plus eight first-class OpenAI-compatible providers (xAI Grok, DeepSeek, Moonshot Kimi, Alibaba Qwen, Z.ai GLM, MiniMax, OpenRouter and Groq; see [OpenAI-compatible providers](#openai-compatible-providers)). Any other OpenAI-compatible endpoint works through the `openai` type and a `baseUrl`.

The task automatically detects which mode to use based on your configuration—no manual switching required.

---

## EDDI's Value Proposition for LLMs

EDDI doesn't just forward messages to LLMs—it **orchestrates** them:

1. **Conditional LLM Invocation**: Use Behavior Rules to decide whether to call an LLM based on user input, context, or conversation state
2. **Pre-processing**: Parse, normalize, and enrich user input before sending to the LLM
3. **Context Management**: Control exactly what conversation history and context data is sent to the LLM
4. **Multi-LLM Support**: Switch between different LLMs (OpenAI, Claude, Gemini, Ollama, Hugging Face, Jlama) based on rules or user preferences
5. **Post-processing**: Transform, validate, or augment LLM responses before sending to users
6. **Hybrid Workflows**: Combine LLM calls with traditional APIs (e.g., LLM generates query → API fetches data → LLM formats result)
7. **State Persistence**: All LLM interactions are logged in conversation memory for analytics and debugging
8. **Tool Calling**: Enable LLMs to use built-in tools or custom HTTP call tools to access external capabilities

---

## Role in the Lifecycle

The Langchain task is a lifecycle task that executes when triggered by Behavior Rules:

```
User Input → Parser → Behavior Rules → [LangChain Task] → Output Generation
                           ↓
                    Action: "send_to_llm"
```

---

## Supported LLM Providers

The Langchain task integrates with multiple LLM providers via the langchain4j library:

- **OpenAI** (ChatGPT, GPT-4, GPT-4o)
- **OpenAI-compatible providers** with their own type: `xai`, `deepseek`, `moonshot`, `qwen`, `zhipu`, `minimax`, `openrouter`, `groq` ([details](#openai-compatible-providers))
- **Self-hosted OpenAI-compatible servers** — vLLM, llama.cpp's `llama-server`, TGI, KServe, LiteLLM and the like — with type `openai-compatible` and per-endpoint capability flags ([details](#self-hosted-openai-compatible-servers))
- **Anthropic** (Claude)
- **Google Gemini** (`gemini` - AI Studio API, `gemini-vertex` - Vertex AI)
- **Mistral AI** (Mistral Large, Codestral, Pixtral)
- **Azure OpenAI** (GPT-4o via Azure-hosted endpoints)
- **Amazon Bedrock** (Claude, Llama, Titan via AWS credential chain)
- **Oracle GenAI** (Cohere Command A, Meta Llama and others via OCI authentication)
- **Ollama** (Local models)
- **Hugging Face** (Various models)
- **Jlama** (Local Java-based inference)

**Note**: The Manager's agent wizard (`/manage/agents/wizard`) and the Platform Operator (`/manage/operator`) both generate a working LLM task for you, so you rarely need to hand-write this config from scratch.

---

## Configuration Modes

### Default: Simple Chat

By default, the Langchain task provides straightforward LLM chat functionality. Just configure your LLM provider and start chatting.

### Optional: Agent Mode with Tools

To give your LLM access to tools (calculator, web search, weather, etc.), set `enableBuiltInTools: true` in your configuration.

The task automatically switches to agent mode when tools are enabled.

**Note**: Custom HTTP call tools (via the `tools` parameter) are also supported. You can provide a list of EDDI HTTP call URIs to give the agent access to your own APIs.

---

## Simple Chat Configuration

This is the standard way to use the Langchain task - just connect to an LLM and start chatting.

### Basic Example

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "simpleChat",
      "type": "openai",
      "description": "Simple chat interaction",
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "prompt": "",
        "logSizeLimit": "-1",
        "convertToObject": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

### Configuration Parameters

| Parameter                  | Type    | Description                                           | Default           |
| -------------------------- | ------- | ----------------------------------------------------- | ----------------- |
| **Core Parameters**        |         |                                                       |                   |
| `apiKey`                   | string  | API key for providers that authenticate with one: `openai`, `anthropic`, `gemini`, `mistral`, `azure-openai` and the OpenAI-compatible types. Prefer a `${vault:...}` reference over a plaintext key. Other providers authenticate differently and take no `apiKey`: `ollama` needs none, `bedrock` uses the AWS credential chain, `gemini-vertex` uses Google application default credentials, `oracle-genai` an OCI config profile (`configProfile`), `huggingface` an `accessToken` and `jlama` an optional `authToken` | Provider-specific |
| `modelName`                | string  | Model identifier (e.g., "gpt-4o", "Claude")           | Provider-specific |
| `systemMessage`            | string  | System message for LLM context                        | ""                |
| `prompt`                   | string  | Override user input (if not set, uses actual input)   | ""                |
| **Context Control**        |         |                                                       |                   |
| `logSizeLimit`             | int     | Conversation history limit (`-1` = unlimited, `0` = none) | falls back to `conversationHistoryLimit` (default 10) |
| `includeFirstAgentMessage` | boolean | **Deprecated — do not use in new configs.** Include the opening **agent** message in context. `false` drops it — and only it: a first message from the *user* is always kept. Setting it logs a WARN; see [Deprecated parameters](#deprecated-parameters) | true              |
| **Output Control**         |         |                                                       |                   |
| `convertToObject`          | boolean | Parse response as JSON. Enables three-layer enforcement: system prompt reinforcement, native API JSON mode (see the [provider matrix](#native-json-mode--provider-matrix)), and pre-parse validation | false             |
| `responseSchema`           | string  | JSON schema for structured output. When set with `convertToObject=true`, the exact schema is injected into the system prompt so the LLM knows the expected format. If it is a JSON Schema it is also validated against the parsed reply and sent natively to OpenAI, Azure OpenAI, Mistral and Gemini — see [Structured Output](#shape-validation-responseschema-and-nonblankfields) | ""                |
| `addToOutput`              | boolean | Add response to conversation output                   | false             |
| **Logging**                |         |                                                       |                   |
| `logRequests`              | boolean | Log API requests (sync and streaming)                 | false             |
| `logResponses`             | boolean | Log API responses (sync and streaming)                | false             |
| **API Configuration**      |         |                                                       |                   |
| `temperature`              | string  | Model temperature (0-1)                               | Provider-specific |
| `maxTokens`                | string  | Maximum output tokens per response — see [Output Token Limits](#output-token-limits) | Provider-specific |
| `timeout`                  | string  | Request timeout (milliseconds) — see [Timeouts](#timeouts-and-streaming) | Provider-specific |

> **These three settings are part of a model's identity.** Two tasks that differ only in `timeout`, `logRequests` or `logResponses` get two separate cached model instances, so a task always runs with the settings it declares regardless of which task was constructed first.
>
> **Logging is EDDI's, not the provider's.** `logRequests`/`logResponses` are honoured by EDDI's own model decorators, which truncate the logged request to 200 and the logged response to 500 characters and run both through the secret redaction filter. The request line — the last message, which is the user's own text — is written at **DEBUG**, so `logRequests: true` shows nothing until the logger `ai.labs.eddi.modules.llm.impl` is at DEBUG; the response line stays at INFO. They are deliberately **not** forwarded to the provider builders: langchain4j's client-level logging writes whole request and response bodies at INFO with no truncation, so switching it on would put full prompts, full conversation history and full model output into the application log. (`logRequestsAndResponses`, which only the Azure OpenAI and Gemini builders accept, is a provider-level escape hatch and *is* still forwarded — use it only where that exposure is acceptable.)
>
> **An unusable `timeout` is ignored, not fatal.** The value is normalised once before it reaches a provider builder: it is trimmed, and dropped entirely when it is blank, non-numeric (`"30s"`) or non-positive (`"0"`, historically "no timeout"), with a WARN naming the model type. Provider builders parse the value with an unguarded `Long.parseLong`, so without this a stored config carrying one of those values would fail on every turn. `" 5000 "` and `"5000"` are the same timeout and share one cached model.

### More Parameters, and Which Model Accepts Them

Beyond the table above every provider reads many more settings: sampling (`topP`, `topK`, `seed`, `stopSequences`, penalties), reasoning (`reasoningEffort`, `thinkingType`, `thinkingBudgetTokens`, `thinkingLevel`, `effort`), caching (`cacheSystemMessages`, `promptCacheKey`), safety (`safetySettings`, Bedrock guardrails), connection (`customHeaders`, `customQueryParams`) and `customParameters`, an escape hatch that adds extra top-level fields to the request body of OpenAI, the OpenAI-compatible providers and Anthropic. The full per-provider list, with value formats, ranges and the model versions that reject or ignore each one, is the generated **[LLM Parameters Reference](llm-parameters.md)**; the Manager shows it as typed controls under **Model settings**.

All values are strings. Lists are a JSON array (`"[\"END\"]"`) or a comma list; maps (`customHeaders`, `metadata`, `safetySettings`) are JSON objects. A value that cannot be parsed is ignored with a warning and the provider default stays.

**Model-aware.** Parameters are not valid for every model of a provider: OpenAI reasoning models reject `temperature` and `topP`, newer Claude models reject non-default sampling settings and manual thinking budgets, newer Gemini models ignore sampling settings. EDDI keeps a catalog of this (`model-parameters.json`) and, when a model is first built, **removes parameters the configured model would reject** (one warning naming the parameter, the model and the reason) instead of letting every turn fail with an HTTP 400. Parameters with no effect are kept and warned about. Set `eddi.llm.parameter-catalog.drop-rejected=false` to only warn. Where vendor documentation is silent or contradictory no rule is written, so an unknown model is never restricted. On Azure OpenAI set `baseModel` (never sent) to name the underlying model, because the deployment name does not identify it.

### Output Token Limits

The `maxTokens` parameter controls the **maximum number of output tokens** the LLM can generate per response. This is a ceiling, not a target — the model generates only what it needs and stops. Setting it higher does not increase cost unless the model actually produces more tokens; the `timeout` parameter is the real cost safety net.

> [!WARNING]
> **Anthropic + Extended Thinking**: Models with extended thinking (e.g. `claude-sonnet-5`, `claude-sonnet-4`) count **thinking tokens** toward `maxTokens`. If the limit is too low, thinking can consume the entire budget, leaving **zero tokens for the actual response** — resulting in empty/null output. This is a silent failure: the API returns successfully, token usage shows consumption, but `text()` is `null`.

#### Provider Limits and EDDI Defaults

| Provider | Config Key | Max Output Capability | EDDI Default (if omitted) | Notes |
|----------|------------|----------------------|--------------------------|-------|
| **Anthropic** | `maxTokens` | 8,192 (standard) / 128,000 (with extended thinking) | **16,384** | Required by API. Set higher for thinking models |
| **Gemini** | `maxOutputTokens` | 65,536 | Provider SDK default | Use `maxOutputTokens` (not `maxTokens`) |
| **Vertex AI Gemini** | `maxOutputTokens` | Model-dependent | Provider SDK default | Use `maxOutputTokens` (not `maxTokens`) |
| **OpenAI** | `maxTokens`, or `maxCompletionTokens` | 4,096–16,384 (model-dependent) | Provider SDK default | Reasoning models (o-series, GPT-5 and later) reject `maxTokens`: use `maxCompletionTokens`. The cap also covers hidden reasoning tokens |
| **Azure OpenAI** | `maxTokens`, or `maxCompletionTokens` | Same as OpenAI | Provider SDK default | Same rule as OpenAI |
| **Bedrock** | `maxTokens` | Model-dependent | Provider SDK default | |
| **Mistral** | `maxTokens` | 32,768 | Provider SDK default | |
| **Oracle GenAI** | `maxTokens` | Model-dependent | Provider SDK default | |

#### Recommended Settings

For most conversational use cases:
```json
"maxTokens": "16384"
```

For complex analysis, multi-step reasoning, or models with extended thinking:
```json
"maxTokens": "32768"
```

For maximum output (e.g. long-form document generation):
```json
"maxTokens": "65536"
```

### Timeouts and Streaming

Two settings bound an LLM call, and they are deliberately distinct:

| Setting                                | Where               | Unit | What it bounds                                                                                                                                       |
| -------------------------------------- | ------------------- | ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `timeout` (`parameters`)               | model parameter     | ms   | The provider call. On a non-streaming task it bounds the whole request. On a **streaming** task it is the provider HTTP client's request/read timeout — for the JDK client, the time until the provider's first response, so it catches a provider that never answers without truncating one that answers slowly. |
| `streamingTimeoutSeconds` (task field) | task-level, sibling of `parameters` | s    | The **overall** wall-clock backstop for the whole stream, for providers whose native timeout does not fire (or does not exist). Streaming only.       |

How the backstop is resolved:

1. An explicit positive `streamingTimeoutSeconds` always wins.
2. Otherwise the backstop is **120s**, raised (never lowered) to cover a longer explicitly configured `timeout`. So `timeout: "300000"` with no `streamingTimeoutSeconds` yields a 300s backstop rather than being cut short at 120s; any `timeout` at or below 120s leaves the 120s default untouched.
3. Otherwise 120s.

Both stored shapes therefore keep working: a config that sets only `streamingTimeoutSeconds` behaves exactly as before, and a config that sets only `timeout` is now honoured on the streaming path instead of being discarded.

```json
{
  "actions": ["send_message"],
  "id": "longRunning",
  "type": "openai",
  "streamingTimeoutSeconds": 300,
  "parameters": {
    "apiKey": "your-openai-api-key",
    "modelName": "gpt-4o",
    "timeout": "300000"
  }
}
```

> `timeout` is read from the task's stored parameters when deriving the backstop. A Qute-templated value (e.g. `"{vars.llm-timeout}"`) cannot be resolved at that point and simply leaves the 120s default in place — it never produces a shorter bound.

### Provider-Specific Examples

#### OpenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "openaiChat",
      "type": "openai",
      "description": "OpenAI GPT-4o chat",
      "parameters": {
        "apiKey": "your-openai-api-key",
        "modelName": "gpt-4o",
        "temperature": "0.7",
        "timeout": "15000",
        "logRequests": "true",
        "logResponses": "true",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Anthropic Claude

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "claudeChat",
      "type": "anthropic",
      "description": "Anthropic Claude chat",
      "parameters": {
        "apiKey": "your-anthropic-api-key",
        "modelName": "claude-sonnet-4-20250514",
        "temperature": "0.7",
        "maxTokens": "16384",
        "timeout": "60000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

> **No `includeFirstAgentMessage` needed.** Older Anthropic examples set
> `"includeFirstAgentMessage": "false"`; the parameter is deprecated (see
> [Deprecated parameters](#deprecated-parameters)) and has no replacement. The advice it used to
> illustrate — "Anthropic doesn't allow the first message to be from the agent, so set
> this to `false`" — described a restriction the
> [Messages API](https://platform.claude.com/docs/en/api/messages) no longer documents,
> and a history beginning with an assistant turn is accepted. Leave the parameter off
> new Anthropic configs unless you genuinely want the opening greeting withheld.
>
> **maxTokens**: Anthropic requires `max_tokens` in every request. If omitted, EDDI defaults to **16384**. For models with **extended thinking** (e.g. `claude-sonnet-5`), thinking tokens count toward this budget — set it higher (e.g. `"32768"` or `"65536"`) for complex analysis tasks.

#### Google Gemini (AI Studio)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "geminiChat",
      "type": "gemini",
      "description": "Google Gemini chat",
      "parameters": {
        "apiKey": "your-gemini-api-key",
        "modelName": "gemini-3.8-flash",
        "temperature": "0.7",
        "maxOutputTokens": "8192",
        "timeout": "60000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

##### Thought signatures — required for tool calling on Gemini 3.x

Gemini 3.x attaches an opaque **`thoughtSignature`** to every `functionCall` part
it emits, and requires it echoed back verbatim when that model turn is replayed on
the follow-up request carrying the `functionResponse`. Without it the API answers:

```
400 INVALID_ARGUMENT — Function call is missing a thought_signature in functionCall parts.
```

Measured against `generativelanguage.googleapis.com`:

| Model | Emits `thoughtSignature` | Replay without it |
| --- | --- | --- |
| `gemini-3.8-flash` | yes | **400** |
| `gemini-3.5-flash` | yes | **400** |
| `gemini-2.5-flash` | yes | 200 (tolerated) |

There is **no `thinkingConfig` setting that avoids this** — Gemini 3.x emits the
signature and rejects its absence even with `thinkingBudget: 0`.

EDDI handles it for you: the `gemini` builder sets langchain4j's `returnThinking`
(capture the signature) and `sendThinking` (echo it back) to **`true` by default**.
Both are exposed as parameters:

| Parameter | Default | Effect |
| --- | --- | --- |
| `returnThinking` | `true` | Captures `thoughtSignature` off the response. Also routes any thought text to a separate field rather than into the user-visible reply. |
| `sendThinking` | `true` | Echoes the captured signature back on follow-up requests. |

> **Setting either to `false` breaks tool calling on every Gemini 3.x model.** There is
> little reason to: Gemini 2.x tolerates the echoed signature. Note that `false` is not
> the state before thought signatures were handled — EDDI used to leave `returnThinking` unset, which prepends any thought
> text to the reply, whereas `false` drops it. EDDI exposes no `thinkingConfig`, so Gemini
> returns no thought text today and the two are indistinguishable in practice.

#### Google Gemini (Vertex AI)

> **Gemini 3.x with tools is not supported on `gemini-vertex` — use `gemini`
> instead.** The thought-signature requirement above applies to Gemini 3.x on
> Vertex AI as well, but the field cannot be carried on this path: neither
> `langchain4j-vertex-ai-gemini` nor the `com.google.cloud.vertexai.api.Part`
> protobuf it depends on models `thought_signature`, so there is nothing for EDDI
> to configure. Fixing it needs an upstream langchain4j change plus a
> `google-cloud-vertexai` bump. `gemini-vertex` remains correct for Gemini 2.x, and
> for Gemini 3.x **without** tools. Configuring a Gemini 3.x model id here logs a
> warning at model-build time naming the alternative.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "geminiChat",
      "type": "gemini-vertex",
      "description": "Google Gemini chat",
      "parameters": {
        "publisher": "vertex-ai",
        "projectId": "your-project-id",
        "location": "us-central1",
        "modelId": "gemini-pro",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Ollama (Local Models)

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "ollamaChat",
      "type": "ollama",
      "description": "Ollama local model chat",
      "parameters": {
        "baseUrl": "http://ollama:11434",
        "model": "llama3.2:3b",
        "timeout": "120000",
        "think": "false",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Ollama-specific parameters**

| Parameter        | Effect                                                                                      |
| ---------------- | ------------------------------------------------------------------------------------------- |
| `baseUrl`        | Ollama's address. Default `http://localhost:11434`, overridable deployment-wide with `EDDI_OLLAMA_DEFAULT_BASE_URL`. |
| `model`          | Model tag as `ollama list` reports it, e.g. `llama3.2:3b`.                                   |
| `think`          | `"true"` / `"false"`. Unset leaves it to Ollama and the model — see below.                    |
| `returnThinking` | `"true"` surfaces the separate `thinking` field instead of discarding it. Off by default.     |
| `temperature`, `maxTokens`, `topP`, `topK` | Standard sampling controls. `maxTokens` maps to Ollama's `num_predict`. |

> **Reaching Ollama from a container.** Inside the `eddi` container `localhost`
> is the container. Use `http://host.docker.internal:11434` for an Ollama on the
> host, or bring up the overlay —
> `docker compose -f docker-compose.yml -f docker-compose.ollama.yml up -d` —
> which puts Ollama on the same network as `http://ollama:11434` and pre-fills
> that base URL for new agents.

> **Reasoning models look like a hang.** gemma3n, deepseek-r1, qwen3 and friends
> think before they answer, and where that reasoning goes depends on the model:
>
> - Reported in a separate `thinking` field — not part of the streamed content, so
>   a streaming chat window shows nothing at all for as long as the model reasons,
>   then the whole answer at once. `"returnThinking": "true"` surfaces it instead
>   of discarding it.
> - Prepended to the content itself as `<think>…</think>` — the tags stream into
>   the reply, where the user sees them. `returnThinking` does not affect this
>   case; there is no `thinking` field to parse.
>
> `"think": "false"` turns reasoning off entirely and is the quickest way to a
> model that answers immediately. Give such a model a generous `timeout` either
> way.

#### Hugging Face

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "huggingfaceChat",
      "type": "huggingface",
      "description": "Hugging Face model chat",
      "parameters": {
        "accessToken": "your-huggingface-access-token",
        "modelId": "llama3",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Jlama (Local Java Inference)

Jlama is the only provider that runs inference **inside the EDDI JVM**. There is no
second process, no Ollama, no HTTP hop — which also means the model's memory, CPU and
weight storage are EDDI's problem rather than a sidecar's. Read the two subsections
below before deploying it; both describe defaults that fail in a container.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "jlamaChat",
      "type": "jlama",
      "description": "Jlama local model chat",
      "parameters": {
        "modelName": "tjake/Llama-3.2-1B-Instruct-JQ4",
        "modelCachePath": "/var/lib/eddi/jlama",
        "temperature": "0.7",
        "maxTokens": "512",
        "timeout": "30000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

| Parameter | Meaning |
| --------- | ------- |
| `modelName` | Hugging Face repo id, e.g. `tjake/Llama-3.2-1B-Instruct-JQ4`. Jlama downloads it on first use |
| `modelCachePath` | Where weights are cached. **Set this in any container** — see below. Default: `${user.home}/.jlama/models` |
| `authToken` | Hugging Face token, for gated or private repos. Use `${vault:...}` rather than a literal |
| `quantizeModelAtRuntime` | `true` quantizes on load: slower startup, smaller memory footprint |
| `workingDirectory` | Scratch space for the loader. Needs to be writable |
| `workingQuantizedType` | Jlama `DType` name for working-set quantization, e.g. `F32`, `I8`. Case-insensitive; an unknown name is logged and ignored |
| `temperature`, `maxTokens` | As for every other provider. Jlama defaults to `0.3` and the model's full context length |
| `timeout` | Honoured, but applied by EDDI as a wall-clock bound around the call rather than by Jlama itself — Jlama's own builder has no timeout |

##### Required JVM flag

Jlama needs the Java Vector API for SIMD tensor operations:

```
--add-modules=jdk.incubator.vector
```

**EDDI sets this for you** in both container images, in the Maven Surefire fork and in
the `mise` dev tasks. (Deliberately not in the Failsafe fork — declaring an `argLine`
there would replace the implicit `${argLine}` that carries the JaCoCo integration-test
agent and Quarkus's module opens, and no integration test builds a Jlama model anyway.) You only need to add it yourself if you launch `quarkus-run.jar` with
your own command line.

The image carries it on **`JDK_JAVA_OPTIONS`**, deliberately, rather than on
`JAVA_OPTS_APPEND` where EDDI's other JVM settings live. The `java` launcher reads
`JDK_JAVA_OPTIONS` itself, so the flag survives an operator overriding either of the
other two variables — and overriding them is normal: a `docker run -e JAVA_OPTS_APPEND=…`
*replaces* the image's value rather than adding to it, so a deployment that sets its
MongoDB connection string that way would otherwise silently drop the flag and fall back
to scalar inference.

> ⚠️ If you set `JDK_JAVA_OPTIONS` yourself, carry
> `--add-modules=jdk.incubator.vector` across — that one *does* replace the image's value.
> You will see `NOTE: Picked up JDK_JAVA_OPTIONS` in the startup log either way.

This matters more than a usual tuning flag, because the failure is silent. Jlama probes
for the Vector API inside a `catch (Throwable)`; without the module it logs one line,
falls back to `NaiveTensorOperations` — scalar Java matrix arithmetic — and answers
normally, just orders of magnitude slower than SIMD. Nothing errors; the agent is simply
too slow to use. EDDI logs its own warning naming this flag when it builds a Jlama model
on a JVM that lacks it.

> The JVM prints `WARNING: Using incubator modules: jdk.incubator.vector` at startup.
> That is expected and is not an error.

##### Deploying Jlama in a container

Three things behave differently inside a container. None of them raises an error — which
is exactly why each is worth setting explicitly.

- **`modelCachePath` — set it.** Jlama writes weights to `${user.home}/.jlama/models`,
  which in a container is the pod's ephemeral writable layer. That *works*, which is what
  makes it a trap rather than an error: the multi-gigabyte weights live exactly as long as
  the pod does, so every restart, rollout and reschedule re-downloads them from Hugging
  Face before the first turn can be answered. Point it at a mounted volume. (The download
  happens on the *first turn*, not at deploy time, so a mistake here surfaces long after
  the agent was configured and saved.)
- **Thread count — give the pod a CPU *limit*, not a parameter.** Jlama runs inference on
  a process-global `PhysicalCoreExecutor` sized at `max(2, availableProcessors() / 2)`.
  `availableProcessors()` honours a container CPU **limit**, so `limits.cpu: 4` yields two
  inference threads. It does **not** honour a CPU **request**: cgroup shares have been
  ignored since JDK 19, so a pod with only `requests.cpu` sees the whole node. EDDI
  deliberately exposes no `threadCount` parameter — Jlama applies it through a one-shot
  process-global latch that throws on its second call, so it cannot be a per-model setting.
  If you must override it, size the pod.
- **Memory — size for the weights, outside the heap.** Jlama memory-maps the safetensors
  files, so the weights land in RSS and page cache, not the Java heap. They still count
  against the container's memory limit. Size the pod for the model *plus* EDDI's heap, and
  note that `JAVA_MAX_MEM_RATIO` only governs the heap, so raising it does not make room
  for the model — it takes room away. The image sets it to 75 % for EDDI's own work; with
  an in-process Jlama model, lower it (for example `JAVA_MAX_MEM_RATIO=50`) and raise the
  memory limit by the size of the weights.

For an air-gapped deployment, pre-seed `modelCachePath` from a machine that has network
access and mount it read-only. Jlama reaches out to Hugging Face whenever the model is
not already in the cache, so an empty cache with no egress fails rather than degrades.

**Note**: Jlama runs models locally in Java without requiring external services like
Ollama. It is CPU inference — there is no GPU path — so it suits small quantized models
(1B–8B) rather than large ones. For a GPU or a larger model, serve it with vLLM or
`llama-server` and reach it with type `openai-compatible`
([Self-hosted OpenAI-compatible servers](#self-hosted-openai-compatible-servers)).

**`modelName` must be a Hugging Face repository id in `owner/name` form** — for
example `tjake/Llama-3.2-1B-Instruct-JQ4` or
`tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4`. Jlama resolves the model through its
own registry, which downloads it from Hugging Face on first use; a bare name
such as `llama-3.2-1b` has no owner to resolve and fails on the agent's first
turn, long after the configuration was saved. Private repositories additionally
need `authToken`.

**There is no `baseUrl`.** Jlama runs *inside the EDDI JVM* — there is no model
server to point at, and a `baseUrl` parameter is dropped with an
"unrecognised parameter" warning. See the parameter table above for everything
the builder does read; `threadCount` is deliberately absent from it — see
"Deploying Jlama in a container" above.

#### Mistral AI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "mistralChat",
      "type": "mistral",
      "description": "Mistral AI chat",
      "parameters": {
        "apiKey": "your-mistral-api-key",
        "modelName": "mistral-large-latest",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

#### Azure OpenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "azureChat",
      "type": "azure-openai",
      "description": "Azure OpenAI chat",
      "parameters": {
        "apiKey": "your-azure-api-key",
        "deploymentName": "gpt-4o",
        "endpoint": "https://your-instance.openai.azure.com",
        "temperature": "0.7",
        "timeout": "15000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Azure OpenAI uses `deploymentName` (not `modelName`) and requires an `endpoint` URL for your Azure instance.

#### Amazon Bedrock

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "bedrockChat",
      "type": "bedrock",
      "description": "Amazon Bedrock chat",
      "parameters": {
        "modelId": "anthropic.claude-v2",
        "region": "us-east-1",
        "temperature": "0.7",
        "maxTokens": "16384",
        "timeout": "30000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Bedrock uses `modelId` (not `modelName`) and does not require an `apiKey`. Authentication is via the AWS SDK default credential chain (environment variables, IAM roles, `~/.aws/credentials`).

#### Oracle GenAI

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "oracleChat",
      "type": "oracle-genai",
      "description": "Oracle GenAI chat",
      "parameters": {
        "modelName": "cohere.command-a-03-2025",
        "compartmentId": "ocid1.compartment.oc1..your-compartment-id",
        "configProfile": "DEFAULT",
        "temperature": "0.7",
        "maxTokens": "16384",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Note**: Oracle GenAI does not require an `apiKey`. Authentication is via OCI SDK (`~/.oci/config`). The `configProfile` parameter selects which OCI profile to use (defaults to `"DEFAULT"`).

#### OpenAI-compatible providers

Eight providers that speak the OpenAI chat-completions protocol have a type of their own, so
the config needs only a key. The endpoint, the default model and the vendor's reasoning quirks
come from a preset (`src/main/resources/llm/openai-compatible-providers.json`), which the
Manager mirrors for its provider picker.

| `type` | Provider | Default endpoint | Regions (`region`) | Default model |
|---|---|---|---|---|
| `xai` | xAI Grok | `https://api.x.ai/v1` | `intl`, `us` | `grok-4.7` |
| `deepseek` | DeepSeek | `https://api.deepseek.com` | | `deepseek-flash` |
| `moonshot` | Moonshot Kimi | `https://api.moonshot.ai/v1` | `intl`, `cn` | `kimi-k3` |
| `qwen` | Alibaba Qwen | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` | `intl`, `cn`, `us` | `qwen3.7-plus` |
| `zhipu` | Z.ai GLM (Zhipu) | `https://api.z.ai/api/paas/v4` | `intl`, `cn` | `glm-5.3` |
| `minimax` | MiniMax | `https://api.minimax.io/v1` | `intl`, `cn` | `MiniMax-M3` |
| `openrouter` | OpenRouter | `https://openrouter.ai/api/v1` | | `openrouter/auto` |
| `groq` | Groq | `https://api.groq.com/openai/v1` | | `openai/gpt-oss-120b` |

Model ids and endpoints were checked against the vendors' documentation on 2026-09-29. Vendors
retire models often, so treat the defaults as a starting point and set `modelName` explicitly
in production. Alibaba is moving the Singapore and Beijing regions to workspace-specific hosts;
the shared hosts above still work (they enter maintenance mode on 2026-09-30, which means no
new features rather than shutdown), and a workspace host
(`https://{WorkspaceId}.<region>.maas.aliyuncs.com/compatible-mode/v1`) goes in `baseUrl`.
xAI's `us` region is its US data-residency host: it serves `grok-4.7` and `grok-4.6` only, at
roughly a 10% premium. DeepSeek's V4.1 Flash (released 2026-09-10) is multimodal and is the
model DeepSeek recommends; whether `deepseek-v4-pro` is still a distinct model is unconfirmed.
Moonshot fixes `temperature` on `kimi-k2.7-code` and `kimi-k2.6`, so do not set it there.

**Precedence.**
- Endpoint: an explicit `baseUrl`, then the `region` parameter (a region id from the table), then
  the default endpoint. An unknown region fails with the list of valid ids. `baseUrl` is always
  checked against the cloud-metadata block list, and with `eddi.security.ssrf-protection.enabled`
  it must also be a public address (or this deployment's own `eddi.self.base-url`) — the same
  rule as for every provider's `baseUrl`/`endpoint`, see [security.md](security.md#ssrf-protection--urlvalidationutils).
- Model: `modelName`, then the provider default.
- Any parameter you set beats the preset's defaults (below).
- `apiKey` is required. Use a vault reference, for example `${vault:xai-key}`; the error names
  the provider and suggests one.

**Thinking defaults.** Reasoning models return their reasoning in a separate field, and some
providers require it back on the next request of a tool loop. The presets set:

| Provider | `returnThinking` | `sendThinking` | Why |
|---|---|---|---|
| `deepseek`, `moonshot` | `true` | `true` | The API rejects a tool-loop follow-up that omits the previous turn's reasoning |
| `zhipu` | `true` | `true` | Z.ai asks that the historical `reasoning_content` be returned during tool use |
| `minimax` | `true` | `true` | With `reasoning_split: true` (sent by the preset) thinking arrives in `reasoning_content`, and MiniMax's docs say the full assistant message must be appended to the history |
| `qwen`, `xai`, `openrouter`, `groq` | default | default | Nothing to echo |

`returnThinking` and `sendThinking` are ordinary parameters and also work on `type: openai`
(both default to `false` there, so existing configs are unchanged). Today `returnThinking` only
matters together with `sendThinking`: EDDI does not read a response's thinking for any other
purpose, so returning it without echoing it buys nothing. `minimax` additionally sends the
request-body field `reasoning_split`; that is fixed by the preset. A parameter left blank
(`""`) counts as not set, so the preset default still applies.

**Capabilities.** `jsonResponseFormat: auto` sends a JSON response format to `xai`, `deepseek`,
`moonshot`, `qwen`, `zhipu` and `groq` when the request carries no tools (never with tools), and
never to `minimax` or `openrouter`. Image input is enabled by model id: `grok-4*`,
`deepseek-flash`/`deepseek-v4-flash`, `kimi-k3`, `kimi-k2.6`, `kimi-k2.7-code`, the Qwen
`qwen3.8*`, `qwen3.7-plus`, `qwen3.7-flash`, `qwen3.6-plus`, `qwen3.6-flash` and
`qwen3-vl`/`qwen-vl`/`qvq` families (not the text-only `qwen3.7-max`), `glm-5.3-flash`,
`glm-4.6v`/`glm-4.5v` (`glm-5.3` itself is text-only), `minimax-m3`, `openrouter/auto` and
Groq's `qwen3.8-27b`. Override either per task or per deployment as usual.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "grokChat",
      "type": "xai",
      "parameters": {
        "apiKey": "${vault:xai-key}",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "deepseekChat",
      "type": "deepseek",
      "parameters": {
        "apiKey": "${vault:deepseek-key}",
        "modelName": "deepseek-flash",
        "temperature": "0.7",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "qwenChina",
      "type": "qwen",
      "parameters": {
        "apiKey": "${vault:qwen-key}",
        "region": "cn",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

**Any other endpoint.** A server you run yourself belongs on
[`type: openai-compatible`](#self-hosted-openai-compatible-servers). Cohere, Together, a corporate
gateway and the like still work through `type: openai` with a `baseUrl`; they simply have no
preset, and are judged by OpenAI's capabilities.

**Known limitation.** A durable tool-approval pause stores the in-flight transcript, including
each assistant turn's `thinking`, and resumes from it, so DeepSeek and Kimi tool turns survive a
pause. On the degraded resume (the transcript exceeded `eddi.hitl.tool.transcript-max-bytes`) the
gating assistant message is replayed with its thinking, unless that single message exceeds its
64 KB cap: it is then persisted without the thinking, and those two providers can reject the
resumed request with a 400. Raise the cap or approve such calls quickly if you hit it.

#### Self-hosted OpenAI-compatible servers

Production on-prem inference usually runs on vLLM, llama.cpp's `llama-server`, Hugging Face
TGI, KServe or a LiteLLM gateway. All of them speak the OpenAI chat-completions protocol, but
none of them is OpenAI: what each supports depends on the server, its version and the model it
serves. Type `openai-compatible` reaches such a server through the OpenAI client and lets the
config **declare** what it supports, instead of inheriting OpenAI's answers.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "granite",
      "type": "openai-compatible",
      "parameters": {
        "baseUrl": "http://vllm.inference.svc.cluster.local:8000/v1",
        "modelName": "ibm-granite/granite-4.0-h-small",
        "apiKey": "${vault:vllm-key}",
        "serverName": "vllm",
        "jsonMode": "true",
        "jsonModeWithTools": "false",
        "vision": "false",
        "timeout": "120000",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "true"
      }
    }
  ]
}
```

| Parameter | Required | Meaning |
|---|---|---|
| `baseUrl` | yes | The server's OpenAI API, usually ending in `/v1`. `http://` is accepted (in-cluster traffic); the cloud-metadata service is refused, as for every model URL |
| `modelName` | yes | The model id exactly as the server lists it under `/v1/models`. There is no default |
| `apiKey` | no | Sent as a bearer token when set. Left blank or unset, the request carries **no** `Authorization` header at all, which is what most local servers expect |
| `jsonMode` | no, default `false` | The server accepts `response_format: {"type": "json_object"}`. With `convertToObject: "true"` a tool-less request then carries it |
| `jsonModeWithTools` | no, default `false` | It also accepts that format in the same request as tool definitions (llama-server, TGI and older vLLM releases do not always) |
| `vision` | no, default `false` | The served model accepts images. Image attachments are then sent to it whatever its name: a self-hosted model cannot be recognised by name, so this type has no model table. Images are inlined, never sent by URL |
| `serverName` | no, default `openai-compatible` | What traces and metrics report as `gen_ai.provider.name` and the `provider` tag, so `vllm` and `llama-server` traffic can be told apart. Lower-cased; anything other than 1–32 characters of `a-z 0-9 . _ -` falls back to the default, because it becomes a metric tag |
| `customParameters` | no | Extra top-level request-body fields as a JSON object: vLLM's `chat_template_kwargs`, `guided_json`, `top_k` and the like. It must be a JSON object; anything else fails the model build instead of being dropped |

Every other `type: openai` parameter works too (`temperature`, `maxTokens`, `returnThinking`,
`sendThinking`, `customHeaders`, …), without OpenAI's model-specific rules.

**Conservative by default.** All three capability flags start off, so a server EDDI knows nothing
about is never sent a request feature it might answer with a 400; turn on what your server
supports. Each flag must be `"true"` or `"false"`. Any other value (a typo such as `"ture"`)
fails the deploy with a message naming the parameter instead of reading as `false`, and so does
a missing `baseUrl` or `modelName`. A flag may also be a template (`{context.x}`) or a global
variable (`${vars:vllm-json}`); it is then read per request once resolved, and a value that is
still not a boolean counts as `false` with one WARN per distinct value. It never fails the turn.

**Precedence.** The per-task `jsonResponseFormat` (`on`/`off`) and `multimodal.vision` overrides
still win over the endpoint's flags; then the flags; then, for vision only, the deployment
override `eddi.multimodal.openai-compatible.vision`; then the default, which is off. In a
[model cascade](model-cascade.md) each `openai-compatible` step is judged by its **own**
parameters (merged over the task's, as every step's are), not by the task's type.

**Not yet.** Native `json_schema` structured output stays off for this type: schemaless JSON is
sent when `jsonMode` allows it, and the prompt block carries the schema. Model discovery through
`GET /v1/models` is not wired up either.

**Migrating from `type: openai` + `baseUrl`.** That keeps working exactly as before (nothing is
rewritten), but EDDI judges such an endpoint as OpenAI: it sends JSON mode alongside tools,
decides vision by OpenAI model names and reports the traffic as OpenAI. To get the endpoint's
real capabilities, change `type` to `openai-compatible`, keep `baseUrl`, `modelName` and
`apiKey`, and add the flags your server supports.

---

## Agent Mode (Enhanced Features)

### AI Agent with Built-in Tools

```json
{
  "tasks": [
    {
      "actions": ["help"],
      "id": "aiAgent",
      "type": "openai",
      "description": "AI agent with calculator and web search",
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant with access to tools."
      },
      "enableBuiltInTools": true,
      "builtInToolsWhitelist": ["calculator", "datetime", "websearch"],
      "conversationHistoryLimit": 10
    }
  ]
}
```

### Agent Mode Parameters

| Parameter                  | Type     | Description                                      | Default                |
| -------------------------- | -------- | ------------------------------------------------ | ---------------------- |
| **Tool Configuration**     |          |                                                  |                        |
| `enableBuiltInTools`       | boolean  | Enable built-in tools                            | false                  |
| `builtInToolsWhitelist`    | string[] | Specific tools to enable                         | (all if not specified) |
| `tools`                    | string[] | Custom HTTP call tool URIs to enable             | (none)                 |
| **Context Control**        |          |                                                  |                        |
| `conversationHistoryLimit` | int      | Max conversation turns in context                | 10                     |
| `maxToolIterations`        | int      | Most model↔tool rounds in one turn before the turn answers with what it has. Values above the engine ceiling of 100 are clamped (WARN) | 10 |
| `maxToolCallsPerIteration` | int      | Most tool calls executed from a **single model response**. Calls past the cap are not run: each gets a `NOT_EXECUTED` result naming the limit, so the model can answer with what it has. `-1`/`0` disables. See [Tool-call caps](#tool-call-caps) | 20 |
| `maxToolCallsPerTurn`      | int      | Most tool calls executed across the whole turn (all iterations, including those a HITL resume continues). Refused the same way. `-1`/`0` disables | 100 |
| `toolRepeatGuard` | object | Refuses a tool call that already failed identically with nothing succeeding since, instead of re-running it. **On by default**; `{"enabled": false}` turns it off. See [Repeated failing tool calls](#repeated-failing-tool-calls) | enabled |
| `maxToolContextTokens`     | int      | Aggregate token ceiling on the **in-turn** tool-call context (tool requests + tool results across all loop iterations). The oldest complete tool exchange is evicted when exceeded. `-1`/`0` disables. See [In-Turn Tool Context Budget](#in-turn-tool-context-budget). | 60000 |
| **Cost & Performance**     |          |                                                  |                        |
| `maxBudgetPerConversation` | number   | Ceiling on accumulated **tool** cost per conversation, in USD. Enforced unless `enforceBudget` opts out | (unlimited) |
| `enforceBudget`            | boolean  | Refuse tool calls once `maxBudgetPerConversation` is passed. **Opt-out** — `false` keeps the ceiling report-only (cost is still tracked), and such a task is named once in a WARN | true (`eddi.tools.budget.enforce-by-default`) |
| `toolPricing`              | map      | Per-call tool prices in USD. Keyed on the built-in slug (`{"websearch": 0.005}`) or on a single dispatch name (`{"searchNews": 0.01}`), which takes precedence — so one operation can be priced apart from its siblings | (built-in defaults) |
| `inputPricePer1M` / `outputPricePer1M` | number | Token prices in USD per 1M tokens for this task's **model calls**, feeding the conversation's tracked cost (`audit:cost`), any group cost ceiling and the tenant's [monthly cost budget](tenant-quotas.md#the-monthly-cost-budget). Applies to non-cascade calls; a [model cascade](model-cascade.md) prices its steps via its own fields of the same name. Negative values fail deployment | (unpriced — $0) |
| `enableToolCaching`        | boolean  | Cache tool results to reduce API calls. Built-in tools only, unless an HTTP/MCP/A2A tool is named in `toolCacheScopes` — see [Tool cache scoping](#tool-cache-scoping) | true                   |
| `toolCacheScopes`          | map      | Per-tool cache partition: `user`/`conversation`/`global`. Naming an HTTP, MCP or A2A tool here is also what opts it into caching | (all `user`)   |
| `defaultToolCacheScope`    | string   | Cache partition for tools without an override    | `user`                 |
| `enableRateLimiting`       | boolean  | Limit tool/LLM usage rate                        | true                   |
| `defaultToolTimeoutMs`     | int      | Wall-clock ceiling in milliseconds on a single tool execution. On expiry the call is abandoned and the model gets an error result; the turn continues. `-1` or `0` disables the bound — see [Execution timeouts](#execution-timeouts) | 120000 |
| `toolTimeoutsMs`           | map      | Per-tool execution timeouts in milliseconds, keyed on the built-in slug (`{"websearch": 15000}`) or on a single dispatch name, which takes precedence. `-1` exempts one tool without unbounding the task | (all `defaultToolTimeoutMs`) |
| `toolLoadingStrategy`      | string   | `EAGER` sends every tool spec on every request. `LAZY` sends only a `discover_tools` meta-tool, and injects the tools the model asks for from the next iteration on. Use `LAZY` when a large tool set is crowding the context window | `EAGER` |
| `maxToolsInContext`        | int      | Maximum tool specifications returned per discovery call under `LAZY`. Ignored under `EAGER` | 20 |
| `retry`                    | object   | Retry policy for LLM calls — see [Retry configuration](#retry-configuration) | (none) |
| `responseValidation`       | object   | Validates the model's response and applies a remediation action. Policies: `onEmpty`, `onTruncation`, `onContentFilter`, `onInvalidJson`, `onSchemaMismatch`, `onContextTooLong`, `onRefusal`, `onStreamingTimeout`. The `fallback` action serves a configurable message — see [Fallback answers and `onError`](#fallback-answers-and-onerror); the `retry` action re-asks the model — see [Recovery policies](#recovery-policies-retry-where-it-helps) | (none) |
| `onError`                  | object   | `{"action": "fallback" \| "error"}`. With `fallback`, a failed model phase serves the configured fallback and the turn completes normally instead of failing — see [Fallback answers and `onError`](#fallback-answers-and-onerror) | `error` |
| `circuitBreaker`           | object   | Opt-in per-model circuit breaker: `{"enabled": true, "window": 10, "threshold": 8, "coolDownMs": 60000}`. Skips a model that keeps failing with the same permanent class (invalid output, bad request, model not found; auth or quota at once) — see [Circuit breaker](#circuit-breaker-skip-a-model-that-keeps-failing) | disabled |
| **Retrieval (RAG)**        |          | Requires the agent's workflow to bind the knowledge base with an `eddi://ai.labs.rag` step — see [RAG](rag.md#configuration) | |
| `knowledgeBases`           | object[] | Knowledge bases this task retrieves from, by `name`, each optionally overriding `maxResults` / `minScore`. The name must match a KB the workflow binds; an unmatched name is skipped silently | (none) |
| `enableWorkflowRag`        | boolean  | Retrieve from **every** knowledge base the workflow binds, instead of listing them. Ignored when `knowledgeBases` contains at least one reference; an empty `knowledgeBases` array does **not** suppress it | false |
| `ragDefaults`              | object   | `maxResults` / `minScore` applied under `enableWorkflowRag`; falls back to each KB's own defaults | (KB defaults) |
| `httpCallRag`              | string   | Name of an httpCall to execute as a search, injecting its response as `## Search Results:`. Needs no vector store and no workflow step, but calls an **external** search API — it cannot query an EDDI knowledge base | (none) |
| `maxRagContextChars`       | int      | Ceiling on the assembled RAG context, in characters. `-1` or `0` disables it and restores the older unbounded behaviour | 20000 |
| `markRagProvenance`        | boolean  | Wrap retrieved context in a `[retrieved context — source '…' …]` … `[end of retrieved context]` envelope that marks it as data, not instructions, before it joins the system prompt. The envelope adds about 200 characters on top of `maxRagContextChars` | true |
| **Prompt & History Limits** |         |                                                  |                        |
| `maxSystemPromptChars`     | int      | Hard ceiling on the whole assembled system prompt, applied after RAG context, counterweight, identity masking and response-format blocks are appended. `-1` leaves it untouched | -1 |
| `conversationSummary`      | object   | Rolling conversation summary — see [Rolling Conversation Summary](#rolling-conversation-summary) | (none) |

### Behavioral Safety (Counterweight & Identity Masking)

EDDI provides two per-task safety mechanisms that are injected into the system prompt before sending it to the LLM. Both must be explicitly enabled with `"enabled": true` — they are off by default.

#### Behavioral Counterweight

Counterweights append behavioral safety instructions to the system prompt. Three preset levels are available:

| Level | Effect |
|-------|--------|
| `normal` | No-op — no safety instructions added (default) |
| `cautious` | Adds guidelines for careful responses, hedging on uncertain topics, and suggesting professional consultation |
| `strict` | Adds stronger instructions: refuse harmful content, flag uncertainty, always suggest human oversight |

**Auto-downgrade**: When an agent runs via the `scheduled` channel (e.g., `ScheduleFireExecutor`), `strict` is automatically downgraded to `cautious` to prevent overly rigid responses in automated pipelines.

**Configuration**:

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "type": "openai",
      "parameters": { "apiKey": "...", "modelName": "gpt-4o" },
      "counterweight": {
        "enabled": true,
        "level": "cautious",
        "placement": "suffix"
      }
    }
  ]
}
```

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `counterweight.enabled` | boolean | Enable counterweight injection | `false` |
| `counterweight.level` | string | `normal`, `cautious`, or `strict` | `normal` |
| `counterweight.placement` | string | `suffix` (after system prompt) or `prefix` (before) | `suffix` |
| `counterweight.customInstructions` | string[] | Custom instruction list that overrides the preset entirely | (none) |

> **Note**: Both `enabled: true` **and** a `level` other than `normal` are required for counterweight to have any effect.

**Customizing presets**: Counterweight preset text is resolved from [Prompt Snippets](prompt-snippets-guide.md) (keys `counterweight-cautious` and `counterweight-strict`). If no snippet exists, built-in defaults are used. This allows admins to customize safety language via the REST API without redeployment.

#### Identity Masking

Identity masking prepends identity concealment rules to the system prompt. This prevents the LLM from revealing its model name, provider, or underlying architecture when asked.

**Configuration**:

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "type": "openai",
      "parameters": { "apiKey": "...", "modelName": "gpt-4o" },
      "identityMasking": {
        "enabled": true,
        "rules": [
          "Never reveal you are an AI language model",
          "If asked about your identity, say you are Aria, a helpful assistant"
        ]
      }
    }
  ]
}
```

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `identityMasking.enabled` | boolean | Enable identity masking | `false` |
| `identityMasking.rules` | string[] | Identity rules prepended to system prompt | `[]` (empty) |

> **Note**: Both `enabled: true` **and** at least one rule are required. If `rules` is empty, masking is skipped even when enabled.

**Execution order**: Identity masking is applied first, then counterweight. Both modify the system prompt before it is sent to the LLM.

---

## Input and Output Guardrails

A task can screen the **user's message before the model is called** (`inputGuardrails`) and the **model's answer before it is stored or delivered** (`outputGuardrails`). Both are ordered lists; each entry names a guardrail `type` and what a finding does (`action`). Nothing is screened unless you configure it — a task without these lists runs no guardrail code at all.

```json
{
  "tasks": [ {
    "actions": ["support"],
    "type": "openai",
    "parameters": { "systemMessage": "You are a support agent.", "addToOutput": "true" },
    "inputGuardrails": [
      { "type": "prompt-injection", "action": "block", "blockMessage": "I can't process that request." },
      { "type": "pii-redaction", "action": "redact", "parameters": { "patterns": "email,ssn,creditcard" } }
    ],
    "outputGuardrails": [
      { "type": "pii-redaction", "action": "redact" },
      { "type": "json-object", "action": "reprompt", "maxRetries": 2 }
    ]
  } ]
}
```

| Field | Description | Default |
|---|---|---|
| `type` | The guardrail (table below). An unknown type is skipped with a WARN in the log — never an error, so a config written for a newer EDDI still runs. | — |
| `action` | `block`, `redact`, `reprompt` or `warn` (below). An unknown value is treated as `block`. | `block` |
| `maxRetries` | `reprompt` only: how many times the model may be asked again. Clamped to 0–5. | `2` |
| `onExhausted` | `reprompt` on output only: what happens once the retries are spent — `block`, or `deliver-best` to deliver the attempt this guardrail rated best, still screened by the guardrails after it. See [When the retries run out](#when-the-retries-run-out-onexhausted). | `block` |
| `blockMessage` | The answer the user gets when this guardrail blocks. Used as-is — it is never run through the template engine. | `Request blocked by guardrail '<type>'.` / `Response withheld by guardrail '<type>'.` |
| `parameters` | Per-type settings, plain strings (never templated). Two work for every type: `repromptMessage` overrides the corrective message of a reprompt (`{reason}` is replaced by the guardrail's reason), and `failMode` (`closed`/`open`) decides what a failing guardrail does — see below. | — |

### Actions

| Action | Input phase | Output phase |
|---|---|---|
| `block` | The model is **not called** and `preRequest` does not run; the block message is the turn's answer, and `postResponse` runs as after any other fallback. | The answer is replaced by the block message (not parsed as JSON, not recorded as the model's response). |
| `redact` | The message the model sees is rewritten. Conversation memory keeps what the user sent. | The answer is rewritten before it is stored, parsed or delivered. A guardrail with no rewrite (a pure violation) is replaced by the block message. |
| `reprompt` | Behaves as `block` — there is nothing to re-ask. | The **same model** is asked again: the original request, the rejected answer as the assistant's turn, then a corrective message. The new answer is screened again from the first guardrail. When `maxRetries` is spent, or the turn has less than `responseValidation.minAttemptMs` left, the answer is blocked — or, with `onExhausted: "deliver-best"`, the best attempt is delivered. |
| `warn` | Recorded (audit, metric, SSE) — nothing else. | Same. |

A block message is served the way an [`onError` fallback](#fallback-answers-and-onerror) is: the turn is flagged as a fallback, so later turns' history leaves the block message out, and a `convertToObject` task with `responseValidation.fallbackField` gets it wrapped as `{"<fallbackField>": "<block message>"}`. `postResponse` runs with that shape — on an input block exactly as on an output block, a spend refusal or an `onError` fallback — so a task with `addToOutput: false` whose `postResponse` builds the reply still answers the user.

**A refused message never reaches a model on a later turn.** When an input guardrail blocks a message, the turn's step is flagged `llm:input_withheld:<taskId>`, and on every later turn the conversation history EDDI hands a model — the message window, the [rolling summary](#rolling-conversation-summary)'s input and the recall tool — carries `[earlier message withheld by a guardrail]` in place of that user message: its text, attachment extracts and input files. The turn's files are left out too: they are not re-inlined for a vision model, not named in the "files shared earlier" note, and `listAttachments` / `readAttachment` do not serve them (a file also attached on a turn that was not refused stays readable). This holds for every guardrail type, whether or not it re-screens history, and it is decided by the flag written when the block happened, not by screening the message again. The same flag is set when a message is **redacted** by a guardrail that does not re-screen history (the `classifier`), so a later turn cannot undo that redaction either; a redaction by the deterministic types is simply re-applied (below). It is also set when the [spend gate](#per-conversation-llm-cost-ceiling) refused the turn before any input guardrail ran, if one of them does not re-screen history: that message was never classified. Conversation memory and the transcript people see keep the message as the user sent it. A turn blocked before this flag existed is recognised by its input-guardrail audit record with action `block`.

Three limits. **The current turn** is not withheld by its own flag: a second LLM task of the same turn sees the message and decides it with its own `inputGuardrails` (give every task that reads user input its own). **A turn on which the guarded task did not run** — no matching action — was never screened, and a guardrail that does not re-screen history (the `classifier`) will not see it later either. And **template data is not filtered**: `{memory.last.input}`, `{memory.past…}`, `{conversationLog}` and properties captured by a `property` step (which may run before the guardrail) hold the message as the user sent it. Do not template earlier user input into the prompt, `systemMessage` or an `httpCall` body of a guarded task.

**When a guardrail fails.** A guardrail that throws, or returns no verdict, could not check the text. What happens then is its `failMode`:

| `failMode` | Effect | Default for |
|---|---|---|
| `closed` | Treated as a finding that blocks: the turn is answered with the guardrail's `blockMessage` (an earlier history message is withheld). | `block`, `reprompt` — a guardrail that exists to keep something out must not let it through because it could not look (a `reprompt` cannot verify any answer either, so it blocks without re-asking) |
| `open` | Recorded as a `warn`; the text passes. | `redact`, `warn` |

Either way the audit record and the `guardrail_triggered` event carry reason `guardrail_error` (action `block` or `warn`) — or, for a [`classifier`](#the-classifier-type-a-safety-classifier-model) or [`judge`](#the-judge-type-grading-the-answer-with-issue-codes) that could not decide, `classifier_unavailable` / `classifier_unparseable` or `judge_unavailable` / `judge_unparseable`, and for an [`expression`](#the-expression-type-a-deterministic-check-over-a-json-answer), `expression_unparseable` / `expression_undecided:<cause>`. Set `"parameters": { "failMode": "open" }` on a blocking guardrail only when availability matters more than screening for it.

A `block` is a returned value, never an exception, so a block message can never be mistaken for a transient provider error and retried.

### Built-in types

| Type | Phases | Finds | Parameters |
|---|---|---|---|
| `prompt-injection` | input | Instruction-override phrasings: "ignore all previous instructions", "reveal your system prompt", "you are now in developer mode", chat-template tags. A heuristic — it catches the common verbatim attacks, not paraphrased or encoded ones. | `patterns` — extra regular expressions, one per line; `mode` — `append` (default) or `replace` the built-in list |
| `pii-redaction` | input, output | E-mail addresses, US social security numbers, payment card numbers (Luhn-checked), phone numbers; rewrites each to `[REDACTED:<kind>]`. Pattern-based — not names, addresses or free-form identifiers. | `patterns` — comma-separated subset of `email,ssn,creditcard,phone`; `replacement` — e.g. `***` (`{kind}` is replaced by the kind) |
| `regex-filter` | input, output | Your own patterns; each match is rewritten to `replacement`. | `patterns` — regular expressions, one per line, case-insensitive (required); `replacement` (default `[REDACTED]`) |
| `max-length` | input, output | Text longer than `maxChars`; `redact` truncates. `reprompt` asks for a shorter answer on the output side only; on the input side it behaves as `block`. | `maxChars` (default `8000`) |
| `judge` | output | What *your* criteria call wrong — a model grades the answer and names the issue codes you declared, each with your fix text for the reprompt. See [The `judge` type](#the-judge-type-grading-the-answer-with-issue-codes). | `provider`, `promptTemplate`, `issues` (required), … |
| `json-object` | output | An answer that is not one JSON object (a Markdown code fence is tolerated). It checks the shape only — for a schema use [`responseSchema` with `onSchemaMismatch`](#shape-validation-responseschema-and-nonblankfields). | — |
| `expression` | output | A JSON answer for which your boolean expression is false — arithmetic and cross-field rules such as `sum(lines[*].amount) == total`. See [below](#the-expression-type-a-deterministic-check-over-a-json-answer). | `expr`, `issue`, `fix` (all required) |

For a type that *finds* rather than rewrites (`pii-redaction`, `regex-filter`, `max-length`), any finding counts: with `block` the text is refused, with `redact` the rewrite is applied.

Patterns in `parameters` are author configuration evaluated against untrusted text. Keep them free of nested quantifiers (`(a+)+`). The `regex-filter` and `prompt-injection` patterns of one guardrail get **250 ms together per text**: a pattern that backtracks past that has not checked the text, which counts as *could not decide* (reason `pattern_timeout`) — its `failMode` applies. Failing closed (the default for `block`) the text is refused. Failing open (the default for `redact`) what the finished patterns found is still used: a `redact` keeps the rewrites they made (recorded as a `redact` with reason `pattern_timeout`), a `block` whose earlier pattern matched still blocks, and only when nothing matched is it a warning with the text passing. A pattern that is not a valid regular expression **fails the deploy** (the message names its line), rather than being dropped — which would leave a block-only filter that never fires.

### The `classifier` type: a safety-classifier model

`classifier` (input and output) asks a **safety-classifier model** for a verdict — Granite Guardian, ShieldGemma, a hosted moderation model, a detector served behind an OpenAI-compatible API. Which model, how to prompt it and how to read its answer are all configuration: EDDI ships no classifier model and endorses none. Check the licence and acceptable-use terms of the model you deploy yourself.

```json
"inputGuardrails": [ {
  "type": "classifier",
  "action": "block",
  "blockMessage": "I can't help with that request.",
  "parameters": {
    "provider": "ollama",
    "baseUrl": "http://ollama.guardrails.svc:11434",
    "model": "shieldgemma:2b",
    "unsafeLabel": "yes",
    "safeLabel": "no",
    "timeoutMs": "3000"
  }
} ]
```

The classifier is called the way an LLM task calls its model: `provider` is an EDDI provider type (`openai-compatible` for a self-hosted OpenAI-compatible server such as vLLM or `llama-server`, `ollama`, `openai`, `azure-openai`, …), and **every parameter that is not one of the classifier's own is passed to that provider unchanged**, in the same vocabulary as a task's `parameters` — `baseUrl`, `apiKey`, `modelName` (`openai-compatible`, `openai`) or `model` (`ollama`), `maxTokens`, `customParameters`, and so on. `apiKey` should be a `${vault:…}` reference; vault and `${vars.…}` references are resolved as they are for a task, and the vault grant check covers them. The endpoint is reached through the provider client, not through EDDI's SSRF-guarded HTTP client, because a classifier is normally an in-cluster service on a private address: it is the same trust class as a task's own `baseUrl`, so only people who may edit agent configuration can point it anywhere.

| Parameter | Meaning | Default |
|---|---|---|
| `provider` | The EDDI provider type to call. Required — without it the guardrail cannot decide (see `failMode`). | — |
| `promptTemplate` | A Qute template rendered and sent as one user message. Without it the text is sent as the model's own conversation — input: the user's message; output: the user's question and the answer as an assistant message — and the model's chat template builds its classification prompt, which is what most safety models expect. | — |
| `risk` | Free text available to the template as `{risk}` (a risk name, or a whole policy). A short plain name (`jailbreak`, `harm`) is also appended to the audit reason. | — |
| `unsafeLabel` | The label that means "flag it". Compared case-insensitively. | `yes` |
| `safeLabel` | The label that means "fine". A text passes **only when the answer positively matches it**; any answer that is neither label — a refusal, an error page, `maybe`, a truncated reply — counts as **could not decide**. Required when `unsafeLabel` is anything but `yes`: the deploy is refused without it. | `no` when `unsafeLabel` is `yes` |
| `labelPattern` | A regular expression; the label is its first capture group in the **last** match within the last 4 KB of the answer (a reasoning trace before the verdict may mention a label itself). Without it, the label is the first word of the answer, punctuation trimmed (`Yes,` and `**No**` work). | — |
| `timeoutMs` | How long the call may take; the provider client is built with the same timeout. The turn's [deadline](#turn-deadline) wins when it is closer, and a turn already out of time does not call the classifier at all. Capped at 60000. | `3000` |
| `failMode` | `closed` or `open` — what *could not decide* does; see below. | `closed` for `block`/`reprompt` |
| `includeRetrievedContext` | Output phase: make this turn's retrieved RAG context (vector store and `httpCallRag`) available to the template as `{context}`, for a groundedness check. Without a `promptTemplate` it has no effect. | `false` |
| `inputPricePer1M`, `outputPricePer1M` | Dollar price per million tokens, applied to the token usage the classifier reports. | unpriced (free) |

**Template data, never template source.** `promptTemplate` is your configuration. The text being screened reaches it only as data — `{input}` (the user's message; in the output phase, the user message the model answered), `{output}` (the answer, output phase), `{context}` and `{risk}` — so a user who types `{risk}` or `{#for …}` sends exactly those characters to the classifier; they are never evaluated.

**Could not decide.** Safe is a positive finding, never a default (Pillar 4). A classifier that names an unsupported provider, cannot be reached, times out, has a `promptTemplate` that does not render, or answers something that is not a label has not checked the text. Its `failMode` decides, exactly as for a failing built-in guardrail — but the audit reason says why: `classifier_unavailable` (no answer) or `classifier_unparseable` (an answer that is neither label). A flagged text carries reason `classifier_flagged` (or `classifier_flagged:<risk>`).

**Cost and latency.** Each screened text is one classifier call — on the input side the typed text plus one call per attachment text part of the turn (an extract, or the note naming files shared earlier), all before the main model, so it adds its latency to every turn; keep `timeoutMs` short and the classifier close. The call's price is added to the turn's cost (`audit:cost`), so it is metered and counted against cost ceilings like any other model call — including the call whose answer could not be parsed. The task's [spend gate](#per-conversation-llm-cost-ceiling) is asked **before** the input classifier — a turn refused for the tenant budget or the conversation ceiling pays no classifier call (nor a retrieval query) and is answered with the fallback — and again after the input guardrails, so the classifier's own price cannot carry the main model call past a ceiling it reached. The classifier does **not** screen the earlier user messages of the history window (each was classified on its own turn): one model call per history message on every turn would multiply its cost by the window size. A message it blocked or redacted is withheld from later history instead (above). It is timed by `eddi.guardrail.classifier.duration{provider, outcome}` (outcome `safe`, `unsafe`, `unparseable`, `unavailable` or `timeout`), and its provider calls appear in the LLM telemetry like a task's.

#### Example: Granite Guardian 3.3 on vLLM

[Granite Guardian 3.3](https://huggingface.co/ibm-granite/granite-guardian-3.3-8b) (Apache 2.0) takes the risk to check as a chat-template argument and answers in a `<score> yes </score>` / `<score> no </score>` tag (`yes` = the risk is present). Served with `vllm serve ibm-granite/granite-guardian-3.3-8b`, it is reached through the [`openai-compatible`](#self-hosted-openai-compatible-servers) provider (`baseUrl` and `modelName` are required; `serverName` names it in the LLM telemetry); the risk goes in through vLLM's `chat_template_kwargs` request field, set with `customParameters`, and no `promptTemplate` is needed:

```json
{
  "type": "classifier",
  "action": "block",
  "blockMessage": "I can't help with that request.",
  "parameters": {
    "provider": "openai-compatible",
    "baseUrl": "http://vllm.guardrails.svc:8000/v1",
    "modelName": "ibm-granite/granite-guardian-3.3-8b",
    "serverName": "vllm",
    "customParameters": "{\"chat_template_kwargs\": {\"guardian_config\": {\"criteria_id\": \"jailbreak\"}, \"think\": false}}",
    "maxTokens": "20",
    "labelPattern": "<score>\\s*(\\w+)\\s*</score>",
    "unsafeLabel": "yes",
    "safeLabel": "no",
    "risk": "jailbreak",
    "timeoutMs": "3000",
    "inputPricePer1M": "0.10"
  }
}
```

The example sends no `apiKey` because an in-cluster vLLM is typically unauthenticated. If your server needs one, add `"apiKey": "${vault:<name>}"` and serve the endpoint over `https://`: the key travels in an `Authorization` header, and over plain `http://` it is readable on the network.

Use other `criteria_id` values (or `custom_criteria` with your own description) from the model card for other risks. With `"think": true` the model writes a reasoning trace before the score — raise `maxTokens` and `timeoutMs` accordingly; `labelPattern` still finds the final score.

#### Example: ShieldGemma on Ollama

[ShieldGemma](https://ollama.com/library/shieldgemma) (Gemma terms of use, with Google's prohibited-use policy) answers `Yes` (violates the policy) or `No`. Ollama's chat template builds the classification prompt from the conversation, so the native mode works as-is — as an input guardrail it judges the user's message, as an output guardrail the answer:

```json
{
  "type": "classifier",
  "action": "block",
  "parameters": {
    "provider": "ollama",
    "baseUrl": "http://ollama.guardrails.svc:11434",
    "model": "shieldgemma:2b",
    "unsafeLabel": "yes",
    "safeLabel": "no",
    "timeoutMs": "2000"
  }
}
```

To check against your own policy instead of the model's default one, write the prompt yourself in the format the [model card](https://huggingface.co/google/shieldgemma-2b) describes, with the policy in `risk`:

```json
"promptTemplate": "Policy:\n{risk}\n\nUser message:\n{input}\n\nDoes the user message violate the policy? Your answer must start with 'Yes' or 'No'.",
"risk": "No instructions for building weapons."
```

> **Llama Guard.** Llama Guard 3 (`llama-guard3` on Ollama) works the same way with `"unsafeLabel": "unsafe"` and `"safeLabel": "safe"` (both required — the deploy refuses a custom `unsafeLabel` alone) (it answers `safe`, or `unsafe` followed by category codes on the next line). Its licence needs checking before you deploy it: Meta's Llama 3.2, 3.3 and 4 acceptable-use policies withhold the licence rights for their **multimodal** models from individuals domiciled in, and companies with their principal place of business in, the European Union — which covers Llama Guard 4 and Llama Guard 3 Vision. EDDI documents no Llama Guard example for that reason; whether a model's terms allow your deployment is your call, not EDDI's.

### The `judge` type: grading the answer with issue codes

`judge` (output only) asks a model whether the answer is **right**, not just whether it is safe or well-formed — grounded in the retrieved sources, complete, on topic, whatever your prompt asks it to check. It answers in a **closed vocabulary**: the issue codes you declare in `issues`, each with the text that tells the producing model how to fix it. Paired with `reprompt`, a flagged answer is re-asked with exactly your fix texts for the codes the judge found.

```json
"outputGuardrails": [ {
  "type": "judge",
  "action": "reprompt",
  "maxRetries": 1,
  "onExhausted": "deliver-best",
  "parameters": {
    "provider": "gemini",
    "modelName": "gemini-3.7-flash",
    "apiKey": "${vault:gemini-key}",
    "thinkingLevel": "low",
    "promptTemplate": "Sources:\n{context}\n\nQuestion:\n{input}\n\nAnswer:\n{output}\n\nCheck the answer against the sources.",
    "includeRetrievedContext": "true",
    "issues": "{\"UNSUPPORTED_CLAIM\": \"Remove every claim the sources do not state.\", \"MISSING_CITATION\": \"Cite the source of each fact you state.\"}",
    "timeoutMs": "5000",
    "inputPricePer1M": "0.15",
    "outputPricePer1M": "0.60"
  }
} ]
```

The judge is called exactly like a [`classifier`](#the-classifier-type-a-safety-classifier-model) — the same machinery, not a copy of it: `provider` names an EDDI provider type, every parameter that is not the judge's own passes to that provider unchanged (`modelName`, `apiKey`, `baseUrl`, `thinkingLevel`, …), vault and `${vars.…}` references are resolved, `timeoutMs` is bounded by the turn's deadline, the call is priced, and `failMode` decides what "could not decide" does.

| Parameter | Meaning | Default |
|---|---|---|
| `provider` | The EDDI provider type to call. Required — the deploy is refused without it. | — |
| `promptTemplate` | A Qute template saying what to check, rendered with `{input}` (the user message the model answered), `{output}` (the answer) and `{context}` (with `includeRetrievedContext`). Required. The screened text is template **data**, never template source. | — |
| `issues` | A JSON object (as a string) of **issue code → fix text**: 1–20 codes matching `[A-Z][A-Z0-9_]{0,39}`, each with non-blank fix text of at most 1000 characters. Required; the deploy is refused when it is malformed. | — |
| `includeRetrievedContext` | Make this turn's retrieved RAG context (vector store and `httpCallRag`) available as `{context}`, for a groundedness check. | `false` |
| `timeoutMs` | How long the call may take; the provider client is built with the same timeout. Capped at 60000; the turn's [deadline](#turn-deadline) wins when it is closer. | `5000` |
| `failMode` | `closed` or `open` — what *could not decide* does. | `closed` for `block`/`reprompt` |
| `jsonResponseFormat` | `on`, `off` or `auto`: whether the request asks the provider for JSON. `auto` follows the same provider matrix as a task's [`jsonResponseFormat`](#structured-output-json-mode); `on` forces it for a provider or gateway the matrix does not know. | `auto` |
| `inputPricePer1M`, `outputPricePer1M` | Dollar price per million tokens, applied to the token usage the judge reports. A judge without prices is logged at deploy time: its calls would be invisible to cost ceilings. | unpriced (free) |

**The answer format is EDDI's, not yours.** After your rendered template EDDI appends a fixed instruction that lists the declared codes (with their fix texts, so the judge knows what each one means) and asks for exactly `{"issues": ["CODE", ...]}` — an empty list when nothing applies. The request asks for JSON where the provider takes it per request, with the codes as an `enum` where the provider enforces a schema (`openai`, `azure-openai`, `mistral`, `gemini`); nothing is baked into the cached model. The reply is read tolerantly — a Markdown code fence or prose around the object is fine, and a code is matched case-insensitively.

**Safe is a positive finding.** Only a parsed `{"issues": []}` passes. A reply that is not that shape, or names a code you did not declare, is **could not decide** (`judge_unparseable`), as is an unreachable or slow judge (`judge_unavailable`); `failMode` applies, exactly as for a classifier.

**Feedback without injection.** A finding carries reason `judge_failed:<CODE>[,<CODE>…]` — your codes, so they are safe in audit records and metrics — and the reprompt's corrective message is EDDI's frame around **your fix texts** for those codes:

```text
Your previous reply needs these corrections:
- Remove every claim the sources do not state.
Write a new reply that applies them. Reply with the answer only.
```

Nothing the judge wrote other than the codes — no critique, no reasoning — reaches the producing model, and the corrective message contains no text from the judge, the user or the model. The re-ask itself still carries the original request and the rejected answer, as every [`reprompt`](#actions) does; the closed vocabulary keeps the *judge* from adding to them. The judge reads untrusted content (retrieved documents, tool results, user input); its free text would be an injection path into the producer. `repromptMessage` still overrides the corrective message, as for every type.

**Cost and latency.** Each screened answer is one judge call after the model answered, and any action but `warn` makes the task [buffer](#streaming) its answer. Keep `timeoutMs` short and the judge model small; each reprompt is one more model call **and** one more judge call. A model judging its own answers is biased — the deploy logs a note when the judge's provider and model equal the task's. The call is timed by `eddi.guardrail.judge.duration{provider, outcome}` (outcome `pass`, `fail`, `unparseable`, `unavailable` or `timeout`); watch `unparseable` and `unavailable`, which under `failMode: open` switch the check off silently.

### When the retries run out: `onExhausted`

A `reprompt` guardrail whose `maxRetries` are spent — or that cannot re-ask at all (the turn is out of time, the model's circuit is open, a resumed turn has no transcript) — blocks the answer. For a quality check that is often the wrong trade: an answer that is not perfect is still better than "Response withheld". `onExhausted` decides:

| `onExhausted` | Effect |
|---|---|
| `block` (default) | The answer is replaced by the block message, as before. |
| `deliver-best` | The attempt **this guardrail rated best** is delivered: fewest issue codes for a `judge` (a tie goes to the later attempt), the last attempt for a guardrail without codes. It is recorded as a `warn` with reason `<reason>:delivered_best` and the number of reprompts sent as `retryCount`. |

**A quality check giving up does not override the guardrails around it, except itself.** The guardrails *before* the exhausted one already screened the delivered attempt in the pass that rated it (their redactions are in it, their findings recorded, and a blocking finding would have ended that pass). The guardrails *after* it had not, so they screen it once more, with no further reprompts: a `redact` (PII) still rewrites it, a `warn` is still recorded, and a `block` — or a `reprompt` finding, which may not re-ask now — blocks it. Each other guardrail therefore sees the delivered attempt exactly once, and a model-backed one is called once for it. A guardrail that *could not decide* is not "exhausted": its `failMode` applies as always.

`onExhausted: "deliver-best"` is refused at deploy time on any action but `reprompt`, and on input guardrails (an input `reprompt` blocks). On a `classifier` it is allowed but logged as a warning — a safety check that delivers what it flagged defeats its purpose.

### The `expression` type: a deterministic check over a JSON answer

`expression` (output only) parses the answer as JSON and evaluates a boolean expression you write over it. It catches answers that are well-formed but wrong — a total that is not the sum of its lines, an end date before the start date — without calling another model, and reprompts with a fix you wrote.

```json
"outputGuardrails": [ {
  "type": "expression",
  "action": "reprompt",
  "maxRetries": 1,
  "blockMessage": "I could not produce a consistent invoice.",
  "parameters": {
    "expr": "sum(lines[*].amount) == total",
    "issue": "LINE_SUM",
    "fix": "Make total equal the sum of the line amounts, to the cent."
  }
} ]
```

Given the answer `{"total": 31.00, "lines": [{"amount": 10.10}, {"amount": 20.20}]}`, the expression is false: the model is asked again with exactly

```
Your previous reply needs these corrections:
- Make total equal the sum of the line amounts, to the cent.
Write a new reply that applies them. Reply with the answer only.
```

and a corrected answer (`"total": 30.30`) is delivered. With `action: "block"` a wrong total is never delivered. Pair it with a task's [`responseSchema`](#shape-validation-responseschema-and-nonblankfields) for the shape; `expression` checks the content.

| Parameter | Meaning |
|---|---|
| `expr` | The expression (grammar below), at most 2000 characters. Required. |
| `issue` | The issue code reported when the expression is false: upper-case letters, digits and underscores, starting with a letter, at most 40 characters. The audit reason is `expression_failed:<issue>`. Required. |
| `fix` | The sentence a reprompt tells the model, at most 1000 characters. Required. |

All three are checked at deploy time: a missing parameter, a bad issue code or an expression that does not parse is refused with a message naming the problem and its position (`parameter 'expr' is not valid at position 21: '=' is not an operator; compare with '=='`).

**Grammar.** Literals: numbers (`12`, `12.50`), strings in single or double quotes (a backslash escapes only a backslash or a quote), `true`, `false`, `null`. Paths into the answer: `total`, `invoice.total`, `lines[0].amount`, `invoice['due-date']`, and the wildcard `lines[*].amount`, which yields the list of every line's amount (`orders[*].lines[*].amount` flattens). `$` is the answer itself — for an answer that is a JSON array, `count($) > 0` or `sum($[*].amount)`. Operators, from tightest to loosest: `!` and unary `-`; `*` `/` `%`; `+` `-`; `<` `<=` `>` `>=`; `==` `!=`; `&&`; `||`, with parentheses for grouping. Comparisons do not chain (`a < b < c` is refused; write `a < b && b < c`). Functions — no others exist:

| Function | Result |
|---|---|
| `sum(list)`, `count(list)` | The sum of a list of numbers (0 for an empty list); the number of elements |
| `min(list)`, `max(list)`, `min(a, b, …)`, `max(a, b, …)` | The smallest / largest number |
| `len(x)` | Characters of a string, elements of a list, keys of an object |
| `abs(x)`, `round(x, n)` | Absolute value; `x` rounded half-up to `n` decimals (0–20; default 0) |
| `exists(path)` | Whether every step of the path is present — a key holding `null` is present. A path without `[*]` |
| `approx(a, b, tolerance)` | `abs(a - b) <= tolerance` |

**Numbers are decimal, not floating point.** `0.1 + 0.2 == 0.3` is true and money sums are exact; `1.0 == 1.00` is true. Arithmetic keeps 34 significant digits (division rounds half-even there). The answer's numbers are read exactly as written.

**Null and missing values.** A path that does not exist is `null`, as is a JSON `null`; so is a wildcard over something that is not an array, and after a wildcard an element without the rest of the path contributes `null` to the list.

- `x == null` and `x != null` test for it; any other comparison with `null` (`discount > 0` when there is no discount) is **false**.
- Arithmetic, `&&`/`||`/`!`, or a function given `null` cannot decide — `sum(lines[*].amount)` over a line without an amount is not quietly skipped.
- `&&` and `||` short-circuit, so `!exists(discount) || discount <= total` never looks at a missing discount.

**Could not decide.** Safe is a positive finding: only an expression that evaluates to `true` passes. These are *could not decide*, and `failMode` applies (closed — the answer is withheld without a reprompt — by default for `block` and `reprompt`):

- `expression_unparseable` — the answer is not JSON (a Markdown code fence and prose around one object are tolerated), is longer than 262,144 characters, or repeats a key (the check and a downstream reader must not see two different totals);
- `expression_undecided:<cause>` — evaluation failed: `null_operand`, `type_mismatch` (a number compared with a string, `sum` over text), `division_by_zero`, `empty_aggregate` (`min`/`max` of nothing), `bad_argument`, `number_out_of_range` (more than 1000 digits or a scale beyond ±1000), `fan_out`, `step_budget`, or `not_boolean` (the expression produced a number, a string or a list).

**Bounds.** An expression has at most 2000 characters, 32 levels of nesting, 500 parts and 32 segments per path. Evaluating it reads at most 10,000 values per path and takes at most 200,000 steps; past either, it cannot decide. Each distinct expression is compiled once and kept in a bounded cache (512 entries).

**Feedback without injection.** The corrective message is EDDI's fixed frame around your `fix` text, and nothing else: no value from the answer, no computed sum, nothing the user wrote. A model cannot use the check to put words into its own next prompt. `repromptMessage` still overrides the whole message, as for every type.

### Where guardrails run

- **Input** guardrails screen the user's message **before retrieval**: a blocked message issues no RAG query and no `httpCallRag` call, and a redacted one is what the retrieval query sees (the `userInput` value of an `httpCallRag` call; a call template that reads `memory.current.input` itself still gets the text as stored). After the prompt is assembled (RAG context, counterweight, identity masking, history window, attachments), and before the model is built, `preRequest` runs or the circuit breaker is consulted, the **text the user typed** is checked again only if it differs from what the first pass saw (a `prompt` template); otherwise the first result is reused, so a finding is recorded once and a redaction lands on the text the model sees. **Text added for this turn's attachments** (extracts, notes about earlier files) follows the typed text in the same message; each such part is screened as this turn's input too — recorded, able to block, redacted in place. The history builder merges the question of a turn answered by a fallback into the next user message, so text parts *before* the typed text are earlier turns, and are history. The **earlier user messages** in the history window are screened again, silently, by every type except the `classifier`: conversation memory keeps every message as the user sent it, and a message that would be blocked is replaced by `[earlier message withheld by a guardrail]`. A message that *was* blocked on its own turn — by any type — is already withheld by the history builder before this runs (see [A refused message never reaches a model on a later turn](#actions)). The [`classifier`](#the-classifier-type-a-safety-classifier-model) does not re-screen history (one model call per history message on every turn would multiply its cost by the window size), so a classifier added later, or a message it let through, is not caught on a later turn.
- **Output** guardrails run on the answer after [`responseValidation`](#recovery-policies-retry-where-it-helps), in every execution mode — plain chat, streaming, the tool loop, the model cascade and a HITL-resumed tool loop. A reprompt never re-runs a tool: in the tool loop only the final model call is re-asked, over the transcript that already holds the tool results; under the cascade the step whose answer was accepted is re-asked. Each reprompt asks the spend gate and that model's [circuit breaker](#circuit-breaker-skip-a-model-that-keeps-failing) first, like the primary call, and settles its own outcome; a reached spend limit or an open circuit stops the reprompts and the answer is blocked. (A HITL-resumed turn neither consults nor settles a circuit, so neither do its reprompts.) A reprompted answer is screened by the output guardrails again from the first one, but it is **not** run through `responseValidation` a second time — a guardrail that must enforce the answer's shape belongs in `outputGuardrails` (`json-object`) itself.
- An `onError` fallback is the author's own text and is not screened.

### Streaming

Tokens already streamed to a client cannot be taken back. A task with an output guardrail whose action is `block`, `redact` or `reprompt` therefore **buffers** its answer — exactly like a task with a `retry` response-validation policy — and the client receives the screened answer once. `warn`-only output guardrails do not change the text and keep streaming. If live streaming matters more than output screening for a task, use `warn`, or screen at the input side only.

### Audit, events and metrics

Every guardrail that fires produces:

- an audit record under the step key `audit:guardrail:<phase>:<type>` (`{phase, type, action, reason, retryCount, taskId}`), collected into the [audit ledger](audit-ledger.md) entry as `output.guardrails`. The `reason` is EDDI's (e.g. `pii:email`, `prompt_injection_pattern`) — never the screened text;
- an SSE event `guardrail_triggered` with `{type, phase, action, taskId}` (`phase`: `input`/`output`; `action`: `block`/`redact`/`reprompt`/`warn`), before the answer;
- the counter `eddi.guardrail.triggered{phase, type, action}` ([metrics](metrics.md)).

Reprompts are model calls: their tokens are added to the turn's token usage and cost. So is the price of every `classifier` and `judge` call (`audit:cost`). A `deliver-best` delivery is a `warn` record of the exhausted guardrail (reason `<reason>:delivered_best`), followed by the records of the re-screen. When the re-screen blocks the attempt, there is no `delivered_best` record: only the re-screen's records, ending in the block.

### Compatibility

The two lists are optional; existing configurations load unchanged. An older EDDI ignores them (unknown properties are skipped) and runs the agent unguarded — keep that in mind before downgrading.

---

## Built-in Tools

When `enableBuiltInTools: true`, you can use these tools:

| Tool Name           | Description                                     | Whitelist Value  |
| ------------------- | ----------------------------------------------- | ---------------- |
| **Calculator**      | Safe math expressions (sandboxed parser)        | `calculator`     |
| **Date/Time**       | Get current date, time, timezone info           | `datetime`       |
| **Web Search**      | Search the web (includes Wikipedia & News)      | `websearch`      |
| **Data Formatter**  | Format JSON, CSV, XML data                      | `dataformatter`  |
| **Web Scraper**     | Extract content from web pages (SSRF-protected). CSS selectors only — regex selectors are refused, see below | `webscraper`     |
| **Text Summarizer** | Summarize long text                             | `textsummarizer` |
| **PDF Reader**      | Extract text from PDF URLs (SSRF-protected)     | `pdfreader`      |
| **Weather**         | Get weather information                         | `weather`        |
| **Tool Response Paging** | Fetch the next page of a tool response that was truncated by `toolResponseLimits`. Pages are scoped to the conversation that produced them; another conversation's `responseId` is not found | `fetch_page` / `fetch_tool_response_page` |
| **Conversation Recall** | Drill back into turns the [rolling summary](#rolling-conversation-summary) has compressed. Only assembled when `conversationSummary.enabled` is true | `conversationRecall` |

> Because a non-empty `builtInToolsWhitelist` enables **only** the tools it names, a whitelist that includes verbose tools should also include `fetch_page` — otherwise truncated tool responses cannot be paged through.

Every built-in parameter carries a description in the tool schema the model receives (for example
`calculate.expression`: *"Arithmetic expression to evaluate, e.g. 'sqrt(16) + 2^3 * PI'"*), and
optional parameters — `searchWeb.maxResults`, `getCurrentWeather.units`,
`getWeatherForecast.days`, `extractLinks.maxLinks`, `summarizeText.numSentences`, … — are not
marked required. They used to be described by their own names and all marked required.

**Failures.** A built-in that cannot reach its upstream (web search, scraper, weather, PDF
download) reports the failure to the model as `Error: …` and the result is not cached. Any other
unexpected exception inside a tool reaches the model only as
`Error: tool '<name>' failed with an internal error (reference <id>)`; the full exception is in
the server log under that reference, never in the transcript.

### Tool Configuration (Server-Side)

Some tools require API keys or external configuration to function. These are configured via **Environment Variables** or `application.properties` on the EDDI server.

#### Web Search Tool

By default, the tool uses **DuckDuckGo** (HTML scraping), which requires no configuration.

To use **Google Custom Search** (more reliable/structured), configure these properties:

```properties
# In application.properties
eddi.tools.websearch.provider=google
eddi.tools.websearch.google.api-key=YOUR_GOOGLE_API_KEY
eddi.tools.websearch.google.cx=YOUR_CUSTOM_SEARCH_ENGINE_ID
```

**Docker Environment Variables:**

- `EDDI_TOOLS_WEBSEARCH_PROVIDER=google`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=...`
- `EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=...`

#### Web Scraper Tool

`extractWithSelector` evaluates a model-written CSS selector against a page of up to
`eddi.tools.web-scraper.max-response-bytes`. Two defences keep that from pinning the server:

- **Regex selectors are refused** before the page is fetched: `:matches`, `:matchesOwn`,
  `:matchText`, `:matchesWholeText`, `:matchesWholeOwnText` and the `[attr~=regex]` operator. jsoup
  evaluates them as Java regular expressions, and a catastrophic pattern there never finishes.
  Selectors are also capped at 256 characters. Use `:contains(text)` / `:containsOwn(text)`.
- **Parsing is contained.** Parsing the page and evaluating the selector run on a small pool of
  platform threads with a hard deadline, instead of on the virtual thread the tool call arrived
  on (where CPU-bound work holds a carrier thread, and there are only as many carriers as cores).
  A call that finds every worker busy is refused at once; a parse past the deadline is abandoned
  and reported as a failure.

```properties
eddi.tools.web-scraper.parse-threads=2          # concurrent parses
eddi.tools.web-scraper.parse-timeout-ms=10000   # deadline per parse
```

A worker past its deadline cannot be killed (Java has no safe way to), so it keeps running until it
finishes; the pool size is what bounds that cost to at most `parse-threads` cores.

#### Weather Tool

The weather tool uses **OpenWeatherMap**. You must provide an API key:

```properties
# In application.properties
eddi.tools.weather.openweathermap.api-key=YOUR_OWM_API_KEY
```

**Docker Environment Variables:**

- `EDDI_TOOLS_WEATHER_OPENWEATHERMAP_API_KEY=...`

### Example: Selective Tool Enablement

```json
{
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["calculator", "datetime", "websearch"]
}
```

This enables **only** calculator, datetime, and websearch tools.

### Example: Enable All Tools

```json
{
  "enableBuiltInTools": true
}
```

Omitting `builtInToolsWhitelist` enables all available built-in tools.

---

## Custom HTTP Tools

In addition to built-in tools, your agent gets access to the EDDI HTTP calls configured in its own workflow. This allows the agent to interact with your own APIs or third-party services.

### Configuration

Exposure is controlled by `enableHttpCallTools` (default `true`). Every `eddi://ai.labs.httpcalls` step in the agent's workflow is discovered automatically — there is no per-call list to maintain. Set it to `false` to expose none of them.

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "type": "openai",
      "parameters": {
        "apiKey": "...",
        "modelName": "gpt-4o"
      },
      "enableBuiltInTools": true,
      "enableHttpCallTools": true
    }
  ]
}
```

The same applies to MCP calls via `enableMcpCallTools` (also default `true`), which discovers the `mcpcalls` configs in the workflow.

> **Legacy**: the `tools` property (a list of httpcall URIs) still exists, but its entries are **not** resolved — listing a URI there grants no access. Its only remaining effect is to switch the task into agent mode, which `enableBuiltInTools` or `a2aAgents` do as well.

### How it Works

1.  **Configuration**: You add httpcall steps to the agent's workflow, as you would for `ApiCallsTask`.
2.  **Discovery**: Each `ApiCall` in those configurations becomes its own tool, named after the ApiCall's `name`, described by its `description`, and with one string parameter per entry in its `parameters` map. That name is also the key used by `toolPricing`, `toolRateLimits` and `toolCacheScopes`, and the name `toolApprovals` patterns match on (alongside the `http.method:path` form).
3.  **Execution**: When the agent decides to use a tool, it calls that tool by name (e.g. `get_stock_price`) with the arguments the schema declares.
4.  **Security**: The agent can **only** execute the HTTP calls present in its workflow. It cannot make arbitrary HTTP requests to the internet. To narrow the agent's reach, narrow the httpcalls configuration in its workflow — or set `enableHttpCallTools: false`.

---

## Extended Configuration Options

The Langchain task supports advanced pre-request and post-response processing for fine-tuned control over task behavior.

### Complete Configuration Example

```json
{
  "tasks": [
    {
      "id": "advancedTask",
      "type": "openai",
      "description": "Task with pre/post processing",
      "actions": ["process_input"],
      "preRequest": {
        "propertyInstructions": [
          {
            "name": "userContext",
            "valueString": "premium_user",
            "scope": "conversation"
          }
        ]
      },
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "addToOutput": "false"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "lastRespondingAgent",
            "valueString": "{conversationInfo.agentId}",
            "scope": "conversation"
          }
        ],
        "outputBuildInstructions": [
          {
            "pathToTargetArray": "response.suggestions",
            "iterationObjectName": "item",
            "outputType": "text",
            "outputValue": "{item.text}"
          }
        ],
        "qrBuildInstructions": [
          {
            "pathToTargetArray": "response.quickReplies",
            "iterationObjectName": "reply",
            "quickReplyValue": "{reply.text}",
            "quickReplyExpressions": "{reply.action}"
          }
        ]
      }
    }
  ]
}
```

### Configuration Parameters Explained

#### Pre-Request Configuration

- **preRequest.propertyInstructions**: Defines properties to be set before making the request to the LLM API
  - **name**: The property name
  - **valueString**: The value to be assigned (supports templating)
  - **scope**: The scope of the property (`step`, `conversation`, `longTerm`)

#### Post-Response Configuration

- **postResponse.propertyInstructions**: Defines properties to be set based on the LLM response
  - **name**: The property name
  - **valueString**: The value to be assigned (supports templating)
  - **fromObjectPath**: Read the value from the template data instead, e.g. the task's own response object (named by `responseObjectName`, or the task id). With `convertToObject: "true"` on the task that object is a map, and `"fromObjectPath": "aiOutput"` stores the whole object as the property, so `{properties.aiOutputObject.htmlResponseText}` resolves; a dotted path such as `aiOutput.htmlResponseText` stores a single field. The value is data and is never rendered as a template. A path that resolves to nothing stores an empty string.
  - **scope**: The scope of the property

- **postResponse.outputBuildInstructions**: Configures how the response should be transformed into output (alternative to `addToOutput`)
  - **pathToTargetArray**: The path to the array in the response
  - **iterationObjectName**: The name of the object for iterating
  - **outputType**: The type of output to generate
  - **outputValue**: The value to be used for output (supports templating)

- **postResponse.qrBuildInstructions**: Configures quick replies based on the response
  - **pathToTargetArray**: The path to the quick replies array
  - **iterationObjectName**: The name of the object for iterating
  - **quickReplyValue**: The value for the quick reply (supports templating)
  - **quickReplyExpressions**: The expressions for the quick reply

#### Response Metadata

- **responseObjectName**: Name for storing the full response object in memory
- **responseMetadataObjectName**: Name for storing response metadata (token usage, finish reason) in memory

## Conversation Window Management

EDDI provides two modes for controlling how much conversation history is sent to the LLM:

### Step-Count Window (Default)

The default mode uses `conversationHistoryLimit` (or `logSizeLimit` parameter) to include the last N conversation steps. This is simple and backward compatible.

### Token-Aware Window with Anchored Opening

For production workloads where token costs matter, EDDI supports **token-budget windowing** that also **anchors the first N steps** to preserve the opening context.

```
[System prompt]
[Turn 1: user's opening message]        ← anchored (always included)
[Turn 1: agent's opening response]      ← anchored (always included)
[... turns 3-40 omitted ...]            ← gap marker
[Turn 41: user message]                 ← recent window (fills remaining budget)
[Turn 42: agent response]               ← recent window
[Turn 43: user message]                 ← current
```

#### Configuration

| Parameter          | Type    | Description                                                                                    | Default |
| ------------------ | ------- | ---------------------------------------------------------------------------------------------- | ------- |
| `maxContextTokens` | int     | Maximum token budget for conversation history (excluding system prompt). -1 = use step count. | -1      |
| `anchorFirstSteps` | int     | Number of opening conversation steps to always include regardless of window position. **Token-aware windowing only** — it takes effect when `maxContextTokens > 0` and is ignored by the step-count window (`conversationHistoryLimit`). | 2       |

#### Example

```json
{
  "tasks": [
    {
      "actions": ["*"],
      "id": "costAwareAgent",
      "type": "openai",
      "parameters": {
        "apiKey": "your-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a project planner."
      },
      "enableBuiltInTools": true,
      "maxContextTokens": 4000,
      "anchorFirstSteps": 2
    }
  ]
}
```

This agent:
- Uses at most **4000 tokens** of conversation history (excluding the system prompt)
- **Always includes** the first 2 conversation steps (the user's initial requirements)
- Fills the remaining budget with the most recent messages
- Inserts a gap marker between anchored and recent messages when turns are omitted

#### Token Counting

- **OpenAI / Azure OpenAI**: Uses tiktoken-based tokenizer (accurate, model-specific) when the
  model name is one jtokkit knows
- **All other providers**: Uses an approximate tokenizer (characters ÷ 4)

An OpenAI-family model name the tokenizer does not know — every Azure **deployment** name
(`my-gpt4-prod`), a non-OpenAI model behind an OpenAI-compatible endpoint, a model newer than the
bundled tokenizer — is counted with the approximate tokenizer as well, and an INFO line says so
once per model. (It used to fail every turn of such an agent as soon as `maxContextTokens` was set.)

When `maxContextTokens` is -1 (default), the existing `conversationHistoryLimit` step-count behavior applies. **Full backward compatibility is guaranteed.**

### Retry Configuration

> **The whole picture** — failure classes, the order in which the engine recovers, every setting with its
> default, a recipe for an agent behind a 60-second caller, and how to read the trace, metrics and log lines
> afterwards — is in [LLM Turn Resilience](llm-resilience.md). This section and the ones below are the field
> reference.

`retry` on an LLM task bounds how the engine re-attempts a failed model call. Only errors the
engine classifies as retriable are retried — transport faults, rate limits and 5xx responses —
never a malformed request, an authentication failure or a spent quota.

**An LLM task with no `retry` block still retries**: the defaults below apply, so a transient
failure is attempted up to 3 times before the cascade (if any) escalates. To turn retries off set
`"retry": { "maxAttempts": 1 }`.

```json
{
  "retry": {
    "maxAttempts": 3,
    "backoffDelayMs": 1000,
    "backoffMultiplier": 2.0,
    "maxBackoffDelayMs": 10000,
    "honorRetryAfter": true,
    "maxRetryAfterMs": 10000
  }
}
```

| Parameter            | Type    | Description                                                                                  | Default |
| -------------------- | ------- | -------------------------------------------------------------------------------------------- | ------- |
| `maxAttempts`        | int     | Total attempts including the first                                                           | 3       |
| `backoffDelayMs`     | long    | Delay before the second attempt                                                              | 1000    |
| `backoffMultiplier`  | double  | Multiplier applied to the delay after each failure                                           | 2.0     |
| `maxBackoffDelayMs`  | long    | Ceiling on any single delay                                                                  | 10000   |
| `honorRetryAfter`    | boolean | Sleep the delay the provider asked for (when the error carries one) instead of the backoff   | true    |
| `maxRetryAfterMs`    | long    | Longest provider-requested wait accepted; a longer request stops retrying **without sleeping** | 10000   |

The engine clamps these so a config cannot pin a pipeline thread: at most 10 attempts, at most
30 seconds for one backoff (and for `maxRetryAfterMs`), and at most 60 seconds of backoff in total
across the retry sequence. A clamped value is reported once in a WARN.

#### Error classification

Every failure is classified into one class (`FailureClass`); only `TRANSIENT`, `RATE_LIMITED` and
`TIMEOUT` are retried. The class is taken from the HTTP status **and the provider's error body**,
because a status alone cannot tell a rate limit from a spent quota:

| Class              | Retried | Typical signals                                                                                                              |
| ------------------ | ------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `TRANSIENT`        | yes     | HTTP 500/502/503/529; Gemini `INTERNAL`/`UNAVAILABLE`; Anthropic `overloaded_error`/`api_error`; connect failure, DNS         |
| `RATE_LIMITED`     | yes     | HTTP 429; Gemini `RESOURCE_EXHAUSTED` (per-minute quota); OpenAI `rate_limit_exceeded`; Anthropic `rate_limit_error`        |
| `TIMEOUT`          | yes     | read/connect timeout, HTTP 408/504, Gemini `DEADLINE_EXCEEDED`, a cascade step timeout                                       |
| `QUOTA_EXHAUSTED`  | no      | OpenAI `insufficient_quota`; Gemini `RESOURCE_EXHAUSTED` whose `QuotaFailure` names a per-day quota (e.g. `...PerDay...`)    |
| `AUTH`             | no      | HTTP 401/403; Gemini `PERMISSION_DENIED`, `API_KEY_INVALID`; Anthropic `authentication_error`                                 |
| `MODEL_NOT_FOUND`  | no      | HTTP 404, Gemini `NOT_FOUND`, OpenAI `model_not_found`, Anthropic `not_found_error`                                          |
| `CONTEXT_TOO_LONG` | no      | HTTP 400 saying "input token count exceeds", `context_length_exceeded`, "prompt is too long", "maximum context length"     |
| `BAD_REQUEST`      | no      | any other HTTP 400/422                                                                                                       |
| `UNKNOWN`          | no      | anything unrecognised                                                                                                        |

For a rate limit the provider's own delay is read from the body — Gemini `RetryInfo.retryDelay`
(`"13s"`, `"1.5s"`) or the "retry in 13s" / "try again in 250ms" sentence OpenAI and Gemini put in
the message — and slept instead of the configured backoff (`honorRetryAfter`). If it is longer than
`maxRetryAfterMs` the engine does not sleep: retrying stops at once and the failure (still
classified `RATE_LIMITED`) goes up so a [model cascade](model-cascade.md) escalates immediately. An
HTTP `Retry-After` **header** is not used: the langchain4j HTTP exception keeps only the status
code and the body, not the headers.

**EDDI is the only retry loop.** The provider clients are built with `maxRetries(0)` (OpenAI,
Anthropic, Gemini, Vertex AI Gemini, Mistral, Ollama, Azure OpenAI, Bedrock), so `maxAttempts: 3`
means three provider calls, not up to nine. The Hugging Face and OCI GenAI clients expose no such
setting and the streaming clients have none; streaming retries are EDDI's own loop. Helper calls that go straight to a model without a task retry policy (the cascade judge model, the tool-response summariser and the summarisation service) run through the default policy, `RetryConfiguration.executeWithDefaultRetry` (3 attempts, same classification), so every model call has exactly one retry owner.

If a helper call still fails after the default policy, the tool-response summarizer and the judge fall
back, and the shared summarizer throws to its caller.

**When the call still fails**, the turn ends with `conversationState: "ERROR"`. The non-streaming
response (`POST /agents/{conversationId}`) then carries a top-level `error` object with the same
information the streaming endpoint sends as its `task_failed` event — the field is absent on every
successful turn, so existing clients are unaffected:

```json
{
  "conversationState": "ERROR",
  "error": {
    "taskId": "eddi://ai.labs.llm",
    "taskType": "langchain",
    "errorType": "LifecycleException",
    "message": "Task 'eddi://ai.labs.llm' failed: Agent execution failed after 3 attempts: mock upstream failure"
  }
}
```

The message is sanitized before it leaves the server (URLs, stack frames and credentials are
removed). The full per-task digest stays in the turn's `conversationOutputs[…].taskErrors`.

**In a tool loop, the unit of retry is one model request.** A failure on the fifth model call of
a tool-calling turn resends that one request — with every tool result gathered so far — rather
than restarting the turn. Tools that already ran are never executed again by a retry. The
60-second backoff ceiling still applies to the **whole turn**: every model request of one tool
loop draws from the same budget, so a long tool loop cannot sleep for a minute per request. (A
HITL resume continues the turn with a fresh budget.)

When `convertToObject` requests the provider's native JSON mode and the call still fails after
its retries, the engine falls back to a plain request only if the failure could mean "JSON mode
is not supported". A timeout, rate limit or 5xx (500 included) is rethrown instead of being paid for twice. A
self-hosted OpenAI-compatible gateway that answers an unsupported `response_format` with a **5xx**
rather than a 4xx therefore fails the turn instead of falling back; set `jsonResponseFormat: "off"`
on that task (see the provider matrix below) so the format is never sent.

### Turn Deadline

Retries, cascade steps and HTTP calls each have their own timeouts, and they multiply: three
attempts of a 25-second model, a two-step cascade and a retried HTTP call can add up to minutes. A
caller with a 60-second timeout then gives up while the engine is still working, and the late answer
is never seen. A **turn deadline** makes every layer spend from one budget instead.

```json
{
  "turnDeadlineMs": 55000,
  "turnDeadlineReserveMs": 1500
}
```

Both are **agent-level** settings (on the agent configuration, not on an LLM task). Unset
`turnDeadlineMs` — the default — means no deadline: every layer applies only its own timeouts,
exactly as before.

| Setting                 | Description                                                                                                                  | Default |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------- |
| `turnDeadlineMs`        | Wall-clock budget of one turn, counted from the request's arrival (time spent queued behind another turn counts)             | unset   |
| `turnDeadlineReserveMs` | Kept back for the fallback answer and for persisting the turn; no model or HTTP work is started that would eat into it        | 1500    |

**Per request.** A caller can send `X-EDDI-Turn-Deadline-Ms: <milliseconds>` on a conversation
`say`, on the managed-conversation endpoint and on the OpenAI-compatible `/v1` chat endpoint. When
the agent sets `turnDeadlineMs`, the header can only *shorten* it. When the agent sets none, the
header alone enables a deadline, capped at 10 minutes. A missing, non-numeric or non-positive value
is ignored (the request is never rejected over it). Only the `say`/`rerun` turn paths read it; a
HITL resume starts a turn without one.

**What spends from it**

- **Model attempts** (`retry`): the first attempt always starts while any time remains; a *retry* only
  starts when at least 3 seconds plus the reserve are left. Each attempt is bounded by what is left
  after the reserve, and an attempt that overruns is abandoned and its call cancelled. A backoff —
  or a provider `Retry-After` — that would leave no room for the next attempt is **not slept**: the
  loop stops and the failure goes up, so a [model cascade](model-cascade.md) escalates, or the turn
  falls back, while there is still time.
- **Cascade**: `maxTotalDurationMs` is `min(configured, remaining - reserve)`, step timeouts are
  clamped to the remaining budget, and no further step starts when under 3 seconds (plus reserve)
  remain. Time already spent by earlier tasks is already gone from the remaining budget.
- **HTTP calls** ([httpcalls](httpcalls.md)): each call's timeout is clamped to the remaining budget,
  and `retryApiCallInstruction` retries that cannot fit (backoff plus a call of at least
  `min(timeout, 3 s)`) are skipped. The first call is never skipped unless the deadline has already
  passed, in which case the call fails fast with a clear message.

Streaming (SSE) model calls keep their own streaming timeout and are not yet bounded by the turn
deadline.

**Metrics.** `eddi.llm.turn.deadline.exceeded{stage}` counts every time a layer stopped because of
the deadline; `eddi.llm.cancelled{scope}` counts attempts and cascade steps abandoned on timeout.

**Deploy-time warnings.** When an agent is loaded, the engine logs (once, never blocking) a warning
if a task's model `timeout` is longer than its cascade step's `timeoutMs`, if `convertToObject` has no
fallback path (no `responseValidation` fallback and no `onError` fallback), and — for agents that set
`turnDeadlineMs` — if the static worst case (every LLM retry running to its timeout, plus the slowest
httpcall of each httpcalls step with its retries) exceeds the deadline minus the reserve.

### Rolling Conversation Summary

The third windowing strategy compresses older turns into a running summary that is injected into
the system message, and keeps only the most recent turns verbatim. Unlike token-aware windowing,
nothing is dropped outright: the model still sees what happened, in condensed form, and can drill
back into the full text through the `conversationRecall` built-in tool.

```json
{
  "conversationSummary": {
    "enabled": true,
    "recentWindowSteps": 5,
    "maxSummaryTokens": 800,
    "excludePropertiesFromSummary": true,
    "maxRecallTurns": 20
  }
}
```

| Parameter                      | Type    | Description                                                                                     | Default |
| ------------------------------ | ------- | ----------------------------------------------------------------------------------------------- | ------- |
| `enabled`                      | boolean | Master switch. Nothing is summarized while this is false                                         | false   |
| `llmProvider`                  | string  | Provider for the summarization call. Inherits the parent task's provider when unset              | (inherit) |
| `llmModel`                     | string  | Model for the summarization call. Inherits the parent task's model when unset                    | (inherit) |
| `maxSummaryTokens`             | int     | Token ceiling on the generated summary                                                           | 800     |
| `excludePropertiesFromSummary` | boolean | Tell the summarizer to skip facts already captured as persistent properties                      | true    |
| `recentWindowSteps`            | int     | Conversation steps kept verbatim alongside the summary. Everything older is covered by it        | 5       |
| `maxRecallTurns`               | int     | Maximum verbatim turns returned per `conversationRecall` invocation                              | 20      |
| `maxTurnsPerUpdate`            | int     | Most turns folded into the summary by one update. A backlog (summary enabled late, or a summarizer outage) is caught up over several turns instead of one request that outgrows the summarizer's context | 20      |
| `maxCharsPerUpdate`            | int     | Character ceiling on everything sent in one update — the previous summary, its headings and the new turns. The summary's share is reserved first; the batch of new turns is shortened turn by turn to fit the rest, and a single larger turn is cut. The summary is never cut: if it leaves the new turns less than a quarter of the budget, the update is skipped with a WARN (turns past the summary keep reaching the model verbatim) until you raise this. Lowering `maxSummaryTokens` does not help that conversation — the stored summary is not rewritten while updates are skipped — it only keeps later summaries small | 60000   |

Turns are numbered by conversation step, in the summary and in `conversationRecall` alike: turn 0 is
the opening (`CONVERSATION_START`) step and turn 1 the user's first message. A requested range such as
`turns 3-5` is inclusive.

> **Watch the whitelist.** A non-empty `builtInToolsWhitelist` enables only the tools it names, and
> `conversationRecall` is one of them. Enabling the rolling summary on a task whose whitelist does
> not include `conversationRecall` leaves the model unable to drill back into summarized turns — it
> answers from the condensed view with no error and no log line.

### In-Turn Tool Context Budget

`maxContextTokens` and `conversationHistoryLimit` bound the **conversation history** — the
turns already on the record. They do **not** bound the messages a single tool-using turn
accumulates *while it runs*. Inside one turn the agent loop appends the model's tool-call
request and every tool result, iteration after iteration, up to `maxToolIterations`. Verbose
tools (web scrapes, full PDF dumps, raw API bodies) can push that in-turn context past the
model's context window and hard-fail the whole turn with a provider `400` — mid-loop, after
the tool side effects have already happened. Per-tool `toolResponseLimits` help only when they
are configured; they have no default, so an ordinary agent runs unbounded.

`maxToolContextTokens` puts an **aggregate** ceiling on that in-turn tool traffic:

- It counts only tool traffic — every `AiMessage` that carries tool-call requests plus its
  `ToolExecutionResultMessage`s, summed across all iterations of this turn (and across a HITL
  pause, which replays the same transcript). System, user and assistant-prose messages are
  never counted or touched here — that is what `maxContextTokens` governs.
- When the ceiling is exceeded, the **oldest complete tool exchange** — a requesting
  `AiMessage` **together with all of its results** — is dropped before the next model call,
  repeatedly, until the traffic fits. Requests and their results are always evicted together:
  dropping one without the other leaves a dangling `tool_call_id` that itself provokes the
  `400` the budget exists to prevent.
- The **most recent** exchange is never evicted. If it alone exceeds the ceiling the request
  is sent unchanged (the model asked for those results and must see them) and the overrun is
  logged — reach for `toolResponseLimits` or a lower `maxToolIterations` in that case.
- The same token estimator used for conversation windowing is reused, so a budget expressed in
  tokens means the same thing in both halves of the request (tiktoken for OpenAI/Azure,
  characters ÷ 4 elsewhere).

**Default: `60000`.** High enough that no ordinary tool-using turn is ever touched — the guard
is byte-for-byte inert below the ceiling, so agents that work today are unaffected — and low
enough to keep a runaway loop inside a 128k context window once the system prompt, the
conversation history and the model's own completion are added. Set `-1` (or `0`) to disable the
guard and restore the pre-6.1 unbounded behaviour.

**Observability.** Eviction is never silent: it emits a `tool_context_evicted` entry in the
execution trace (with token counts before/after, exchanges and messages dropped, and whether
the result is within budget), increments the `eddi.llm.tool_context.evictions` counter (tagged
`outcome=within_budget|still_over_budget`), and logs a `WARN` (`llm.tool_context.evicted`)
carrying the conversation id and the remediation hint. Because eviction removes tool results the
model can no longer see, treat a steady stream of these as a signal to lower `maxToolIterations`,
set `toolResponseLimits`, or raise `maxToolContextTokens`.

```json
{
  "type": "openai",
  "parameters": { "modelName": "gpt-4o" },
  "enableBuiltInTools": true,
  "builtInToolsWhitelist": ["websearch", "webscraper"],
  "maxToolIterations": 10,
  "maxToolContextTokens": 60000
}
```

---

## API Endpoints

The Langchain task configurations can be managed via REST API endpoints.

### Endpoints Overview

1. **Read JSON Schema**
   - **Endpoint:** `GET /llmstore/llms/jsonSchema`
   - **Description:** Retrieves the JSON schema for validating Langchain configurations

2. **List Langchain Descriptors**
   - **Endpoint:** `GET /llmstore/llms/descriptors`
   - **Description:** Returns a list of all Langchain configurations with optional filters

3. **Read Langchain Configuration**
   - **Endpoint:** `GET /llmstore/llms/{id}`
   - **Description:** Fetches a specific Langchain configuration by its ID

4. **Update Langchain Configuration**
   - **Endpoint:** `PUT /llmstore/llms/{id}`
   - **Description:** Updates an existing Langchain configuration

5. **Create Langchain Configuration**
   - **Endpoint:** `POST /llmstore/llms`
   - **Description:** Creates a new Langchain configuration

6. **Duplicate Langchain Configuration**
   - **Endpoint:** `POST /llmstore/llms/{id}`
   - **Description:** Duplicates an existing Langchain configuration

7. **Delete Langchain Configuration**
   - **Endpoint:** `DELETE /llmstore/llms/{id}`
   - **Description:** Deletes a specific Langchain configuration

8. **Patch Langchain Configuration**
   - **Endpoint:** `PATCH /llmstore/llms/{id}?version=N` (`Content-Type: application/json-patch+json`)
   - **Description:** Applies an RFC 6902 JSON Patch to version `N` and stores a new version, validated exactly like `PUT`. `409` if `N` is not the current version; `400` for a failed `test` operation, a path that does not resolve, or a patched document the model rejects. Send only what changes — a document that holds `${vault:…}` references never has to be echoed back. See [Editing a configuration that holds a reference](secrets-vault.md#editing-a-configuration-that-holds-a-reference-patch-not-echo)

---

## Tool Execution Pipeline

All tool invocations—both built-in tools and custom HTTP call tools—are routed through a unified **Tool Execution Service** that applies enterprise-grade controls:

```
Tool Call ──▶ Rate Limiter ──▶ Cache Check ──▶ Execute Tool (timeout) ──▶ Cost Tracker ──▶ Result
```

### Controls

| Feature           | Description                                                            | Config Key                                                 |
| ----------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------- |
| **Rate Limiting** | Token-bucket per tool, configurable limits                             | `enableRateLimiting`, `defaultRateLimit`, `toolRateLimits` |
| **Smart Caching** | Deduplicates identical tool calls, partitioned per identity, source and agent. HTTP/MCP/A2A tools only on opt-in | `enableToolCaching`, `toolCacheScopes`, `defaultToolCacheScope` |
| **Cost Tracking** | Per-conversation tool-cost accounting, with an enforced-by-default ceiling and automatic stale-data eviction | `enableCostTracking`, `toolPricing`, `maxBudgetPerConversation`, `enforceBudget` |
| **Execution Timeout** | Wall-clock ceiling on a single tool call; on expiry the model gets an error result and the turn continues | `defaultToolTimeoutMs`, `toolTimeoutsMs` |

#### Tool names: dispatch name vs. configuration slug

A built-in tool has **two** names, and knowing which one a setting expects is the
difference between a rule that binds and one that is silently ignored:

- the **slug** — the token you write in `builtInToolsWhitelist` (`websearch`,
  `calculator`, `datetime`, …). This is a property of the *tool*.
- the **dispatch name** — the `@Tool` method the model actually calls
  (`searchWeb`, `searchNews`, `searchWikipedia` all belong to `websearch`). This
  is a property of the individual *operation*.

| Setting                      | Accepted keys                                        |
| ---------------------------- | ---------------------------------------------------- |
| `builtInToolsWhitelist`      | slug only                                            |
| `toolRateLimits`             | slug **or** dispatch name — dispatch name wins       |
| `toolPricing`                | slug **or** dispatch name — dispatch name wins       |
| `toolCacheScopes`            | slug **or** dispatch name — dispatch name wins       |
| `toolTimeoutsMs`             | slug **or** dispatch name — dispatch name wins       |
| `toolApprovals`              | dispatch name, optionally `source:name`-qualified    |
| cache TTL, default price     | slug (resolved automatically)                        |
| `eddi.tool.*` metric `tool` tag | dispatch name                                     |

> **Rate-limit buckets are per dispatch name.** `{"websearch": 30}` sets the
> *limit* for the whole tool but gives `searchWeb`, `searchNews` and
> `searchWikipedia` 30 calls/minute **each**, not 30 between them. Pin a single
> operation by using its dispatch name: `{"searchNews": 5}`.

#### Tool-call caps

`maxToolIterations` bounds **rounds**, and one model response can ask for any number of tool
calls. Two caps bound the calls themselves:

- `maxToolCallsPerIteration` (default 20) — calls executed from one model response. A response
  that asks for 50 has its first 20 executed, in order.
- `maxToolCallsPerTurn` (default 100) — calls executed across the whole turn, counted from the
  turn's trace so a HITL resume continues the count.

Every call past a cap still gets its own tool result, because providers reject a response whose
tool calls are not all answered:

```json
{"status":"NOT_EXECUTED","reason":"at most 20 tool calls are executed per response (maxToolCallsPerIteration); this call was not run. Do not repeat it in this turn — answer with the results you have."}
```

A refused call never reaches the approval gate, so it cannot pause a turn for a call that was not
going to run. Refused calls are always the tail of the response and are answered after the executed
ones, so the tool results arrive in the order the model listed the calls. Each one is traced as `tool_call_capped` and the turn logs one WARN.
`maxToolIterations` itself is clamped to 100. Under a [model cascade](model-cascade.md) each step
runs its own tool loop, so `maxToolCallsPerTurn` applies per cascade step, not across the steps.

#### Repeated failing tool calls

Inside the iteration cap a model can still send the *same* failing call (a 403, a 404, a
schema-rejected body) in every round. The tool repeat guard stops that: a call is refused,
**without being executed**, when an identical call (same tool, same arguments, JSON key order
ignored) already failed and no *other* call has succeeded since. Another success in between lifts
the refusal, because the model may have fixed the precondition (404 on `getUser`, then a successful
`createUser`, then `getUser` again is allowed).

It is **enabled by default**, with the defaults below. Configure it per LLM task; `{"enabled": false}`
restores the previous behaviour exactly:

```json
"toolRepeatGuard": {
  "enabled": true,
  "maxIdenticalFailures": 2,
  "maxIdenticalCalls": -1,
  "maxRefusalsPerTurn": 3,
  "exemptTools": ["get_job_status"]
}
```

| Field | Default | Meaning |
|-------|---------|---------|
| `enabled` | `true` | `false` disables the guard |
| `maxIdenticalFailures` | `2` | Identical *retryable* failures tolerated; the next identical call is refused. A *terminal* failure is refused on the very next identical call |
| `maxIdenticalCalls` | `-1` | Opt-in ceiling on identical *successful* calls (polling is legitimate, so unlimited by default) |
| `maxRefusalsPerTurn` | `3` | Refusals after which the loop stops asking the model and ends the turn with a fixed message. `-1`/`0` = never stop, only refuse |
| `exemptTools` | none | Tools never tracked, by dispatch name or built-in slug |

Outcomes are classified from the tool's raw result (before the provenance envelope): an HTTP result
with `httpCode` 2xx is a success, 408/425/429/5xx are retryable, every other status is terminal;
`Error: ...` strings and `{"error": ...}` objects are retryable, except rejected arguments and an unknown
tool, which are terminal. A call the loop refused itself (`NOT_EXECUTED`, budget, quota, expired deadline)
is never counted as a failure. A refused call receives

```json
{"status":"NOT_EXECUTED","reason":"identical call already failed (HTTP 403) and nothing has changed since; do not repeat it. Report the failure to the user or try a different approach"}
```

and is traced as `tool_repeat_refused` (metric `eddi.tool.repeat.refused`). A refused repeat still counts toward `maxToolCallsPerTurn`, because the model did issue it. The state lives for one run of the
tool loop: a HITL resume starts with a fresh guard, and each model-cascade step has its own. Rule-triggered
`httpcalls` (no LLM) are out of scope, as they cannot loop.

#### Execution timeouts

Every tool call — built-in, http, MCP, A2A, dynamic, memory and recall alike —
runs under a wall-clock ceiling. Without one, a tool that never returns holds the
whole conversation turn open indefinitely.

| Parameter              | Type                  | Description                                                                                  | Default  |
| ---------------------- | --------------------- | -------------------------------------------------------------------------------------------- | -------- |
| `defaultToolTimeoutMs` | int                   | Ceiling, in milliseconds, on a single tool execution                                          | `120000` |
| `toolTimeoutsMs`       | map<string,int>       | Per-tool overrides, keyed on the dispatch name or the canonical slug — dispatch name wins    | —        |

```json
"defaultToolTimeoutMs": 120000,
"toolTimeoutsMs": { "websearch": 15000, "generateQuarterlyReport": -1 }
```

**On expiry the model is told, and the turn carries on.** The call is abandoned
and the model receives
`Error: Execution timed out after <n>ms for tool: <name>` — the same shape as the
rate-limit refusal — so it can apologise, try a different tool, or answer without
one. Nothing is cached and nothing is charged for the abandoned call, and an
`eddi_tool_execution_timeout_total{tool="…"}` counter is incremented. A timeout is
**never retried**: retrying a hang only buys another full wait and another chance
to re-fire a side effect the tool had already begun.

**`-1` (or `0`) means no bound.** Set it on a single tool in `toolTimeoutsMs` to
exempt one deliberately long-running operation without unbounding every other
tool on the task; set it as `defaultToolTimeoutMs` to restore the unbounded
behaviour this field replaced, for the whole task.

The default of two minutes sits far above the transport timeouts the tool sources
set for themselves — `mcp.timeoutMs` and `a2a.timeoutMs` default to 30000 each —
so those keep reporting their own, more specific errors and this ceiling only
fires on a genuine hang.

> **A call waiting for human approval is never on this clock.** A tool call gated
> by [`hitlConfig.toolApprovals`](hitl.md) does not enter the execution step at
> all: the loop pauses before dispatching it, and the approved call is timed from
> the moment it actually starts running on resume. A reviewer who takes an hour
> costs the tool nothing.

> **A timeout abandons the call; it cannot stop it.** The worker is interrupted,
> so a tool blocked in interruptible I/O unwinds immediately — but one stuck in a
> native call keeps running and can still complete its side effect after the model
> was told it failed. For a tool whose side effects must never be doubled, put it
> behind the HITL tool-approval gate or give it `-1`, rather than a short timeout.
>
> Such workers are counted, not assumed away: the gauge
> `eddi_tool_execution_abandoned` reports how many are still running with nobody
> waiting on them. It normally reads zero, so a value that climbs and does not
> come back down is a tool leaking workers — pair it with
> `eddi_tool_execution_timeout_total` to see which one.

#### Budgets

`maxBudgetPerConversation` bounds **tool** cost only — the accumulated per-call
prices of the tools a conversation invokes. LLM token spend is a separate,
run-scoped concern governed by the model cascade's `maxCostPerRun`; the two are
not added together.

Enforcement is the **default** (since 6.6.0): a configured ceiling refuses the
tool call after the one that crosses it. To keep a ceiling advisory, opt out with
`enforceBudget: false` on the task, or `eddi.tools.budget.enforce-by-default=false`
for the whole deployment — cost is still tracked and reported
(`GET /llm/tools/costs`, `eddi.tool.costs`), nothing is refused, and every task
whose ceiling is opted out is named once in a WARN so an advisory ceiling is a
visible decision rather than a silent one. The effective default is reported by
the MCP `get_agent` and `deploy_agent` tools (`toolBudgetEnforcedByDefault`).

Before 6.6.0 enforcement was an opt-in, and the deployment key was not declared,
so every stored ceiling without an explicit `enforceBudget: true` was advisory
(security review 2026-10-15, finding M8). The reason for the opt-in — built-in
tools priced at $0.00 until the canonical-slug fix — has passed; a deployment
that upgrades with ceilings on built-in-only agents will see them bind for the
first time.

The check runs *before* each call and uses `<=`, so the call that crosses the
ceiling still completes and the next one is refused with
`Error: Budget exceeded for conversation <id>`.

Default per-call prices: `webscraper` $0.002, `websearch` $0.001, `pdfreader`
$0.001, `weather` $0.0005; `calculator`, `datetime`, `dataformatter` and
`textsummarizer` are free. Anything not in that table — http, mcp, a2a, dynamic
and the remaining built-ins — costs $0.00 until you price it with `toolPricing`.
Negative `toolPricing` values are clamped to 0.0.

**Prices drive the tenant budget.** Each settled turn charges its priced cost —
LLM tokens at `inputPricePer1M` / `outputPricePer1M` (or cascade step prices) plus
tracked tool cost — to the tenant's `maxMonthlyCostUsd`. Once that budget is spent,
every LLM task serves its fallback answer (`responseValidation.fallbackMessage`, or
the default sentence) instead of calling its model, and records
`llm:spend_refused:<taskId>` on the step. An unpriced model meters $0 in token cost,
but once the tenant budget is spent (by priced tools or other priced models) its
calls are refused too. See [Tenant Quotas](tenant-quotas.md#the-monthly-cost-budget).

#### Per-conversation LLM cost ceiling

`maxCostPerConversationUsd` is an **agent-level** setting (on the agent configuration, next to
`workflows`, not on an LLM task) that bounds what one conversation may spend:

```json
{
  "workflows": ["eddi://ai.labs.workflow/workflowstore/workflows/0123456789abcdef01234567?version=1"],
  "maxCostPerConversationUsd": 0.50
}
```

What counts is the conversation's **priced** spend — the sum of every step's `audit:cost`: LLM
tokens at `inputPricePer1M` / `outputPricePer1M` (or cascade step and judge prices) plus tracked
tool cost. It is checked

- **before each LLM task calls its model** (plain, streaming, tool loop or cascade): once the
  conversation has spent the ceiling, the model is not called; the task serves its fallback answer
  (`responseValidation.fallbackMessage`, or the default sentence) whatever its `onError`, the step
  records `llm:spend_refused:<taskId>` with `reason: conversation_ceiling`, and
  `eddi_llm_spend_refused_total{reason="conversation_ceiling"}` counts it;
- **between the rounds of a tool loop**: the loop adds what it has spent so far (its tokens at the
  task's prices, its tool cost) and, once that reaches the ceiling, stops issuing model calls and
  answers that the conversation reached its cost limit — the tool calls already made have taken
  effect. A cascade's tool loop prices its rounds at the task-level prices for this check; the
  cascade's own step prices apply at the next task-level check;
- **when a tool-call approval resumes the turn**: the same check runs before the approved calls
  execute, so a conversation spent while it waited serves the fallback answer and the approved
  calls do not run; and the resumed loop checks again before its first model request, counting
  what the approved calls charged. What the turn spent *before* the pause counts too: when a tool
  loop pauses, its model rounds so far (at the task's prices, as in the loop check above, and any
  earlier cascade steps at theirs) and its tool cost are added to the step's `audit:cost` and
  `audit:token_usage` right away, so the resume's checks and its final total include them exactly
  once.

The call that crosses the ceiling completes, so a conversation can end slightly above it. Unset
(the default) means unlimited; `0` allows no priced spend; a negative value is treated as
unlimited. An unpriced model adds $0, so a positive ceiling never stops it by itself — but `0`
refuses every model call, priced or not, since the spend so far already equals it. This is
unrelated to the task-level `maxBudgetPerConversation` (tool cost only; see `enforceBudget`
above), which is unchanged.

**Undo does not refund the ceiling.** The spend only grows. An undone step's cost still counts
while it sits in the redo cache (a rerun of the step before it runs against it), a redo moves it
back without changing the total, and when the next `say` discards the redo cache the new step
inherits the discarded spend as `audit:discarded_cost`, which counts like `audit:cost` and is
persisted with the step. A rerun adds to the step's own `audit:cost`. So say → undo → say cannot
reset what a conversation has spent.

#### Tool cache scoping

**What is cached.** Built-in tools, except those bound to one conversation, which
are never cached: any tool object built per turn with that conversation's state
rather than shared as one bean — attachments (`listAttachments`, `readAttachment`),
artifacts, group tasks, dynamic agents, user memory, recall, `discover_tools`. The
rule is derived from the tool class, not from a list, so a new per-conversation
tool is covered without anyone remembering to add it. (`listAttachments` was the one
nobody had added: under the default `user` scope, conversation B of the same user was
answered with conversation A's files for five minutes.) **A failed call is never
cached either** — a built-in that cannot reach its upstream reports a failure, not a
result, so the next call runs again instead of replaying the error for the tool's
TTL (up to an hour for the web scraper). **HTTP-call, MCP and
A2A tools are not cached unless the task names the tool in `toolCacheScopes`.**
Those tools reach systems EDDI does not control and can have side effects — a POST,
an MCP write, a request to another agent — and a cache hit does not execute the
call: before this rule, a second identical order placed within the TTL was answered
from the cache and never placed. Naming a tool in `toolCacheScopes`
(`{"lookupCustomer": "user"}`) is the explicit statement that repeating the call is
safe to skip; do it only for read-only tools.

Cached tool results are partitioned by identity, by the tool's source and by the
agent. The cache key is `scopeTag|src:<source>|agent:<agentId>|toolName:arguments`
— the agent segment is left out only for a built-in on the `global` scope, whose
definition is the same for every agent. Without the source and agent segments, two
agents serving the same user (or every scheduled run, which share the scheduler's
identity) shared entries for any tool name they had in common. The scope tag is
resolved per tool call as `toolCacheScopes[<dispatch name>]` →
`toolCacheScopes[<slug>]` → `defaultToolCacheScope` → `user`:

| Scope          | Tag                                    | A cached result is reused…                     |
| -------------- | -------------------------------------- | ---------------------------------------------- |
| `user`         | `u:<32 hex chars of SHA-256(userId)>`  | only for the same authenticated user (default) |
| `conversation` | `c:<conversationId>`                   | only inside the conversation that produced it  |
| `global`       | `g`                                    | by everyone — opt-in only                      |

Choose `global` **only** for tools whose result depends purely on their
arguments and never on who is asking (pure computation, public reference data).
It is the one setting that permits cross-user reuse.

When `user` scope applies but there is no user id, the entry falls back to the
narrower conversation partition. When neither identity is available the cache is
bypassed entirely for that call — nothing is read and nothing is stored, and the
`eddi.tool.cache.bypassed` counter is incremented.

An unrecognized token never fails the agent load, and it never widens a tool's
audience either. A `toolCacheScopes` entry whose value does not parse
(`"usr"`, `""`, `null`) resolves to `user` — **not** to `defaultToolCacheScope`,
which could be `global` — and is logged at WARN naming the tool and the bad
token. An unrecognized `defaultToolCacheScope` likewise resolves to `user`.

Per-tool TTLs are enforced per entry: a cached result is removed once its own
TTL has elapsed since it was written, independently of the other entries in the
cache. The TTL is resolved from the dispatch name first and the slug second, so
`searchNews` gets the 10-minute `news` TTL while its `searchWeb` sibling
inherits `websearch`'s 30 minutes. `GET /llm/tools/cache/ttl/{toolName}` reports
the TTL that will be applied. Size eviction (10 000 entries) is the secondary
bound.

### Configuration Example

```json
{
  "tasks": [
    {
      "actions": ["help"],
      "type": "openai",
      "enableBuiltInTools": true,
      "enableRateLimiting": true,
      "defaultRateLimit": 100,
      "toolRateLimits": { "websearch": 30, "weather": 50 },
      "defaultToolTimeoutMs": 120000,
      "toolTimeoutsMs": { "websearch": 15000 },
      "enableToolCaching": true,
      "enableCostTracking": true,
      "toolPricing": { "websearch": 0.005 },
      "maxBudgetPerConversation": 5.0,
      "enforceBudget": true,
      "parameters": { "apiKey": "...", "modelName": "gpt-4o" }
    }
  ]
}
```

### Security Hardening

Tools that accept URLs from LLM-generated arguments are protected against **Server-Side Request Forgery (SSRF)**:

- Only `http` and `https` schemes are allowed
- Private/internal IP ranges are blocked (loopback, site-local, link-local)
- Cloud metadata endpoints are blocked (`169.254.169.254`, `metadata.google.internal`)
- Internal hostnames (`.local`, `.internal`, `localhost`) are rejected

The **Calculator** tool uses a sandboxed recursive-descent math parser (`SafeMathParser`) instead of a script engine, eliminating any possibility of code injection.

See the [Security documentation](security.md) for full details.

---

## Monitoring & Observability

EDDI provides built-in metrics for monitoring agent performance:

- Tool execution success/failure rates
- Response latency (P50, P95, P99)
- Cache hit rates
- Cost tracking
- Rate limit violations

See the [Metrics Documentation](metrics.md) for details on configuring Prometheus/Grafana monitoring.

---

## Complete Example: Multi-Capability Agent

```json
{
  "tasks": [
    {
      "actions": ["*"],
      "id": "universalAssistant",
      "type": "openai",
      "description": "Universal AI assistant with multiple capabilities",
      "parameters": {
        "apiKey": "your-openai-api-key",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful AI assistant with access to calculator, web search, and weather tools.",
        "temperature": "0.7",
        "timeout": "30000"
      },
      "enableBuiltInTools": true,
      "builtInToolsWhitelist": [
        "calculator",
        "datetime",
        "websearch",
        "weather"
      ],
      "conversationHistoryLimit": 10
    }
  ]
}
```

This agent can:

- ✅ Perform calculations
- ✅ Get date/time info
- ✅ Search the web
- ✅ Check weather
- ✅ Maintain 10 turns of conversation history

---

## Integration with Behavior Rules

To trigger the Langchain task, configure Behavior Rules to emit the appropriate action:

```json
{
  "behaviorGroups": [
    {
      "name": "Send to LLM",
      "behaviorRules": [
        {
          "name": "User asks question",
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "*",
                "occurrence": "currentStep"
              }
            }
          ],
          "actions": ["send_message"]
        }
      ]
    }
  ]
}
```

Then reference this action in your Langchain task:

```json
{
  "tasks": [
    {
      "actions": ["send_message"],
      "id": "myChat",
      "type": "openai",
      "parameters": {
        "apiKey": "your-api-key",
        "addToOutput": "true"
      }
    }
  ]
}
```

---

## Structured Output (JSON Mode)

When you need the LLM to return a specific JSON structure (e.g., for property extraction, API response formatting, or quick reply generation), use the `convertToObject` parameter with an optional `responseSchema`.

### Three-Layer Enforcement

EDDI uses three complementary mechanisms to ensure reliable JSON output:

| Layer | Mechanism | Coverage |
|---|---|---|
| **1. System Prompt** | Appends `## RESPONSE FORMAT (MANDATORY)` section with schema to every request | All providers |
| **2. Native API** | Sets `ResponseFormatType.JSON` on the outgoing `ChatRequest` — with your `responseSchema` attached as a JSON schema where the provider enforces one (see [Native schema enforcement](#native-schema-enforcement)) | See the matrix below |
| **3. Validation** | A response starting with `{` becomes an object (a map), one starting with `[` a list; plain text, or JSON the model truncated or malformed, is kept as the raw string with a WARN rather than failing the turn. A parsed object is then checked against `responseSchema` / `nonBlankFields` ([Shape validation](#shape-validation-responseschema-and-nonblankfields)) | All providers |

If a provider doesn't support native JSON mode (e.g. Anthropic), EDDI gracefully falls back to prompt-only enforcement.

#### Native JSON mode — provider matrix

Layer 2 is applied **per request**, never baked into the model instance, and it is applied in **all three execution modes**: no-tools (legacy), agent mode (tool-calling) and streaming — including every step of a multi-model cascade, which is evaluated against that step's own provider.

| Provider | No tools (legacy / streaming) | Agent mode (tools present) |
|---|---|---|
| `openai` | ✅ | ✅ |
| `azure-openai` | ✅ | ✅ |
| `mistral` | ✅ | ✅ |
| `gemini`, `gemini-vertex` | ✅ | ❌ — the Gemini API rejects `responseMimeType: application/json` together with `tools` |
| `anthropic`, `bedrock` | ❌ — both reject a JSON format without a schema | ❌ |
| `xai`, `deepseek`, `moonshot`, `qwen`, `zhipu`, `groq` | ✅ | ❌ — no verified tools + JSON support |
| `minimax`, `openrouter` | ❌ | ❌ |
| `openai-compatible` | the endpoint's `jsonMode` (default ❌) | the endpoint's `jsonModeWithTools` (default ❌) |
| `ollama`, `jlama`, `huggingface`, `oracle-genai` | ❌ (not verified — opt in with `jsonResponseFormat: "on"`) | ❌ |

#### Native schema enforcement

When the task has a `responseSchema` that is a **JSON Schema** with an `object` root and at least one property, the request carries it as a `JSON_SCHEMA` response format instead of schemaless JSON — for the providers whose langchain4j binding enforces a schema **per request**:

| Provider | Native schema | Notes |
|---|---|---|
| `openai`, `azure-openai` | ✅ | Sent as `response_format: json_schema` (non-strict, see below) |
| `mistral` | ✅ | |
| `gemini` | ✅ | Same no-tools rule as JSON mode: with tools in the request nothing is sent |
| `gemini-vertex` | ❌ | The Vertex binding only takes a schema on the model builder, which would bake it into a cached model. Schemaless JSON is sent |
| everything else | ❌ | Schemaless JSON where the matrix above allows it; the prompt block carries the schema |

- **Strictness.** langchain4j's `strict: true` is a *model*-builder flag (`strictJsonSchema`), not part of the request, and EDDI never bakes a response format into a cached model. So the schema is sent **non-strict**: a strong hint the provider honours in practice, not a guarantee. The guarantee is EDDI's own validation below. (Strict mode also demands `additionalProperties: false` and every property in `required`; most hand-written schemas are not strict-compatible, which is why non-strict is the safe default.)
- **All or nothing.** A schema EDDI cannot express faithfully — type arrays such as `["string","null"]`, a property without `type`, `anyOf`/`oneOf`/`$ref`, an array without `items`, a non-object root — is not sent natively; the request falls back to schemaless JSON plus the prompt block. Convertible: `object` / `string` (with an all-string `enum`) / `integer` / `number` / `boolean` / `array` + `items`, `required`, `description`, boolean `additionalProperties`. `minLength` has no native counterpart and is enforced by EDDI after the reply arrives.
- **Safety net.** If a provider rejects the request, the no-tools path retries once without any response format, exactly as it did for schemaless JSON mode.
- A `responseSchema` written as an *example object* (`{"answer": "string — ..."}`, as in the example below) is not a JSON Schema: it is used only for the prompt block, and neither validated nor sent natively.

#### Overriding the matrix per task

Set `jsonResponseFormat` on the LLM **task** (not in `parameters`):

| Value | Behaviour |
|---|---|
| `auto` (default) | Use the matrix above, including the tools-aware distinction |
| `on` | Always send the JSON format when `convertToObject=true`, tools included. The escape hatch for a provider or OpenAI-compatible gateway the matrix does not know yet — it also bypasses the Gemini guard, so only use it where you have verified the provider accepts the combination |
| `off` | Never send it; enforcement stays prompt-only |

```json
{
  "id": "classifier",
  "type": "mistral",
  "jsonResponseFormat": "auto",
  "parameters": { "convertToObject": "true" }
}
```

> **Do not set a `responseFormat` model parameter.** It is only read by the OpenAI builder and it bakes JSON mode into a **cached** model that is then reused for tool-calling and streaming requests — the cause of the historical Gemini `400 Function calling with a response mime type: 'application/json' is unsupported`. `convertToObject` alone is enough.

### Basic JSON Mode

```json
{
  "parameters": {
    "convertToObject": "true",
    "addToOutput": "false",
    "systemMessage": "You are a customer support classifier."
  }
}
```

### With Response Schema

For maximum reliability, specify the exact JSON structure you expect:

```json
{
  "parameters": {
    "convertToObject": "true",
    "addToOutput": "false",
    "responseSchema": "{\"htmlResponseText\": \"string — the formatted response\", \"quickReplies\": [\"string — suggested follow-up options\"], \"sentiment\": \"positive|negative|neutral\"}",
    "systemMessage": "You are a customer support agent. Analyze the user's message and respond."
  }
}
```

The schema is injected into the system prompt as a JSON code block so the LLM sees the exact expected format.

### Shape validation: `responseSchema` and `nonBlankFields`

A reply can be perfectly valid JSON and still unusable: the field the output template reads is missing, has the wrong type, or is `""` (which renders as an empty bubble, because `?:` does not fall back on an empty string). After the reply parsed, EDDI checks its **shape** against two optional settings:

- **`responseSchema`**, when it is a JSON Schema. Supported subset: `type` (`object`, `array`, `string`, `number`, `integer`, `boolean`, `null`, or an array of those), `required`, `properties` (recursive), `items`, `enum`, `minLength`, and optionally `additionalProperties: false`. Every other keyword (`pattern`, `$ref`, `oneOf`, `format`, ...) is **ignored** — validation is in-house and never evaluates a regex or fetches a reference against model output. An unparseable or malformed schema (`{"type": "strng"}`, `{"required": "a"}`) logs one `WARN` and **skips validation**; it never fails the turn.
- **`nonBlankFields`** (a task field next to `jsonResponseFormat`, not a parameter): a list of top-level or dotted names (`"htmlResponseText"`, `"answer.text"`) that must hold a non-blank string. The shortcut for "an empty answer is a failure" without writing a schema. Independent of `responseValidation.enabled`.

```json
{
  "id": "answerer",
  "type": "openai",
  "nonBlankFields": ["htmlResponseText"],
  "parameters": {
    "convertToObject": "true",
    "responseSchema": "{\"type\":\"object\",\"required\":[\"htmlResponseText\"],\"properties\":{\"htmlResponseText\":{\"type\":\"string\",\"minLength\":1},\"mood\":{\"type\":\"string\",\"enum\":[\"happy\",\"sad\"]}}}"
  }
}
```

A violation is outcome **`schema_mismatch`** (see the table below). Unlike `invalid`, the reply *did* parse, so the **parsed object is still stored** under `responseObjectName` — templates that worked on a partially valid object keep working, and nothing changes for an agent that does not set either option beyond the extra outcome/metric. The EDDI-generated reason is recorded under `llm:output:reason:<taskId>`:

| Reason | Meaning |
|---|---|
| `schema: $.htmlResponseText required` | A `required` property is absent |
| `schema: $.count type` | Wrong JSON type (an integer-valued `2.0` counts as an integer) |
| `schema: $.mood enum` | Value not in the `enum` |
| `schema: $.htmlResponseText minLength` | String shorter than `minLength` |
| `schema: $ additionalProperties` | An undeclared key with `additionalProperties: false` |
| `nonBlank: $.htmlResponseText` | Missing, not a string, or blank |

Up to three violations are listed, joined by `; `. Reasons contain only the keyword and the path from your schema — **never a model value or a key taken from the reply** — so they are safe to quote back to the model in a corrective re-ask. What to *do* about a mismatch (ignore, retry, escalate, fall back) is the response-validation policy's job, not the parser's.

### Using with Output Configuration

When `convertToObject=true`, the LLM's JSON response is stored in conversation memory as a parsed object. You can then reference its fields in the Output Configuration:

```json
{
  "outputBuildInstructions": [{
    "outputType": "text",
    "outputValue": "{properties.aiOutputObject.htmlResponseText}"
  }],
  "qrBuildInstructions": [{
    "pathToTargetArray": "properties.aiOutputObject.quickReplies",
    "iterationObjectName": "quickReply",
    "quickReplyValue": "{quickReply}",
    "quickReplyExpressions": "trigger(quick_reply)"
  }]
}
```

### How the reply is parsed (never throws)

Models do not always answer with a bare JSON document. Under `convertToObject=true` EDDI normalises the reply with exactly three steps, in order, and nothing else:

1. **Trim** surrounding whitespace.
2. **Strip one surrounding markdown fence** — ```` ``` ```` or ```` ```json ```` (the language tag is case-insensitive).
3. If it still does not parse, **extract the outermost balanced `{...}` or `[...]`** from the text (string- and escape-aware, so braces inside string values and `\"` do not confuse it) and parse that. This handles a prose prefix and/or suffix such as `Sure! Here you go: {...} Hope that helps.` An extracted fragment is accepted only if it is a JSON object with at least one key; a top-level array is accepted only when it is the whole (fence-stripped) reply. So prose like `Pick option [1] or [2].` or `The empty set {} is...` stays the raw string instead of being replaced by a fragment.

Invalid JSON itself is **never "fixed"** — there is no trailing-comma repair or quote swapping. A reply either parses or it does not.

**Invalid output no longer fails the turn.** A reply that is truncated mid-object, prose, or otherwise not JSON used to throw out of the LLM task and fail the whole turn (HTTP 500) after the model call had been paid for. It now keeps the behaviour that plain-text replies always had: the raw string is stored under `responseObjectName`, a WARN is logged (with an EDDI-generated reason such as `truncated JSON`, `not JSON`, `unbalanced braces` — never model output), and the pipeline continues. The same code path serves a conversation that resumes after a human-in-the-loop approval.

Every `convertToObject` reply gets an outcome, recorded on the step under `llm:output:outcome:<taskId>` and counted in the Micrometer counter `eddi.llm.output` (tag `outcome`):

| Outcome | Meaning |
| --- | --- |
| `valid` | Parsed as received (after trimming) |
| `repaired` | Parsed only after fence stripping or extraction |
| `invalid` | Not parseable; the raw string is stored |
| `empty` | Null or blank reply; the raw value is stored |
| `schema_mismatch` | Parsed, but violates `responseSchema` / `nonBlankFields`; the **parsed** object is stored and the reason is under `llm:output:reason:<taskId>` |

A rising `invalid` or `schema_mismatch` rate for one agent usually points at a prompt, schema or model-version problem. Neither key is part of the public conversation snapshot.

### Fallback answers and `onError`

Two things can leave a turn without a model answer: a `responseValidation` policy set to `fallback` (empty, truncated or filtered reply), and a model phase that fails outright (provider error after its retries and any cascade, an unusable model configuration, a validation policy of `error`). Both serve the same configurable fallback.

```json
{
  "convertToObject": "true",
  "onError": { "action": "fallback" },
  "responseValidation": {
    "enabled": true,
    "onEmpty": "fallback",
    "fallbackMessage": "{#if properties.language == 'de'}Entschuldigung, das hat gerade nicht geklappt. Bitte versuche es noch einmal.{#else}Sorry, I could not answer that just now. Please try again.{/if}",
    "fallbackField": "htmlResponseText",
    "fallbackQuickReplies": [ { "value": "Try again", "expressions": "retry_last" } ]
  }
}
```

| Field | Meaning | Default |
| --- | --- | --- |
| `onError.action` | `fallback` absorbs a failure of the task's model phase: the task serves the fallback and returns normally, so the turn completes with HTTP 200 and the conversation is **not** set to `ERROR`. `error` lets it fail the turn. Only that failure is absorbed: a human-in-the-loop tool pause, a conversation cancel, a graceful-shutdown interrupt and a thread interrupt always propagate | `error` |
| `responseValidation.fallbackMessage` | The text served. It is a template (Qute, like every author-written config field) rendered with the task's template data, so `{properties.x}`, `{snippets.x}` and `{#if}` all work. A template that fails to render, or renders blank, falls back to the default sentence | `I'm sorry, I wasn't able to generate a complete response. Please try again.` |
| `responseValidation.fallbackField` | Under `convertToObject`, the fallback is stored under `responseObjectName` as the object `{"<fallbackField>": "<message>"}`, so a `postResponse` output template such as `{properties.aiOutputObject.htmlResponseText}` renders it unchanged. Unset, the fallback stays a plain string | (none) |
| `responseValidation.fallbackQuickReplies` | Quick replies (`value`, `expressions`, both templates) added to the step next to the fallback, exactly as an output set adds them | (none) |

`onError` works without a `responseValidation` block (the defaults above apply), and the fallback fields work without `onError` (they shape the validation `fallback` action).

**What is recorded.** The step gets `llm:fallback:<taskId>` = `true` for any fallback, and, for an absorbed failure, `llm:error:<taskId>` = `{class, message}`: the simple class name of the root cause and its message, with secrets redacted, URLs replaced by `[url]` and the text cut at 200 characters (never a request body or stack trace). Data the failed phase had already written to the step is marked uncommitted (see [Memory Policy](memory-policy.md)), and the audit ledger's model-response field is left empty rather than recording the fallback as something the model said. The Micrometer counter `eddi.llm.recovery` counts each one (`action=fallback`, `outcome=served`, `trigger=onError|validation`).

**The model never sees the fallback.** When the next turn's history is built for the model, the assistant message of a fallback turn is left out; the user's question stays. To keep the roles alternating (some providers reject two user messages in a row) the following user message is merged into it. The conversation log and the UI still show the fallback, and an output item the task itself adds under `addToOutput` carries `"fallback": true`. The rolling summary and the recall tool still see the turn.

**Resume after a human-in-the-loop approval.** The resumed model phase runs under the same `onError` guard: a failure of the final answer serves the fallback instead of failing the turn (the approved tool calls already ran exactly once, so only the answer is replaced). A pause, a cancel and a refused approval still propagate. See [LLM Turn Resilience](llm-resilience.md#after-a-human-approval-resume) for what does and does not apply on resume.

### Recovery policies: retry where it helps

A reply the task cannot use is usually fixed by asking again, and the model that slipped is the cheapest one to ask. `responseValidation` therefore has a `retry` action. The order of recovery is always: **the model answers, the parser repairs what it can locally (fence stripping, extracting the object from surrounding prose — free), the same model is asked again with a corrective message (up to `maxRetries`), then a cascade escalates to the next model (which gets its own repair and re-asks, see [Model Cascade](model-cascade.md#format-recovery-and-escalation)), and after the last model `fallbackAction` applies.** Without a cascade it is the same without the escalation step.

```json
{
  "convertToObject": "true",
  "maxTokens": "2000",
  "responseValidation": {
    "enabled": true,
    "onInvalidJson": "retry",
    "onTruncation": "retry",
    "onEmpty": "retry",
    "onContextTooLong": "retry",
    "maxRetries": 1,
    "fallbackAction": "fallback",
    "fallbackMessage": "Sorry, I could not answer that just now."
  }
}
```

| Field | Meaning | Default |
| --- | --- | --- |
| `onEmpty`, `onTruncation`, `onContentFilter` | now also accept `retry` | `warn` |
| `onInvalidJson` | A `convertToObject` reply that is not valid JSON after the local repair. `retry` re-asks with the corrective message; `warn`/`fallback`/`error` act as for the other policies | `ignore` (the raw string is stored, as before) |
| `onSchemaMismatch` | A reply that parses but breaks the response shape (`responseSchema` or `nonBlankFields`, see above). `retry` re-asks like `onInvalidJson`, with the violation (for example `nonBlank: $.answer` or `schema: $.answer required`) as the corrective message's `{reason}`; `ignore` and `warn` keep the parsed object; `fallback` and `error` act as usual | `ignore` |
| `onContextTooLong` | The provider refused the prompt as too long. `retry` re-sends **once** with the history window halved (half the `maxContextTokens`, or half the `conversationHistoryLimit`; when that is unlimited, half of the conversation). The anchored first steps (`anchorFirstSteps`) are kept. Any other value lets the failure propagate as before | `error` |
| `maxRetries` | Same-model re-asks **per model** (per cascade step), shared by every `retry` policy. Clamped to `0..3`. `0` sends no re-ask (a cascade still escalates on invalid output). A cascade step overrides it with `steps[i].maxFormatRetries`, e.g. `0` for an expensive last-resort model | `1` |
| `truncationRetryFactor` | The truncation re-ask runs **once** with the output-token cap multiplied by this (`maxTokens`, or `maxOutputTokens` for Gemini; Anthropic's built-in default counts). Clamped to `1..4` and never above 32768 tokens. If the cap is not configured (and the provider has no known default) there is nothing to raise and the re-ask is skipped | `2` |
| `correctiveMessage` | The user message of an invalid-JSON re-ask. `{reason}` is replaced **literally** with EDDI's own reason (`not JSON`, `truncated JSON`, `no JSON object`, `unbalanced braces`, `invalid JSON syntax`). It is not a template and never receives model or user text | "Your previous reply could not be used: {reason}. Reply again with only the JSON object described in the instructions." (a neutral sentence for a task that does not return JSON) |
| `maxRetryCostUsd` | Optional dollar cap on what the re-asks of one model may cost in total; a re-ask that would start at or above it is skipped. It is priced from the task's / step's `inputPricePer1M` / `outputPricePer1M`: **without prices it cannot fire**. Per-conversation cost ceilings (`ToolCostTracker`) are not consulted | (none) |
| `minAttemptMs` | A re-ask only starts if the model's remaining time (the cascade step's `timeoutMs`; the turn deadline once there is one) is at least this. Otherwise the cascade escalates at once: serving the user in time beats insisting on the same model | `3000` |
| `fallbackAction` | What happens when the re-asks (and every cascade step) did not help: `fallback` serves the [fallback](#fallback-answers-and-onerror), `error` fails the turn, `warn` keeps the reply | `fallback` |

**What a re-ask sends.** For an invalid-JSON reply: the original messages, then the model's own bad reply as an **assistant** message (cut at 16,000 characters), then the corrective **user** message. Showing the model what to fix is standard corrective prompting; its words stay in the assistant role, and the user-role text is only what you configured plus EDDI's reason, so neither model output nor user input ever reaches a user- or system-role message. Empty, truncated and content-filtered replies are re-asked with the original request unchanged (a truncation with the larger token cap): there is nothing a corrective sentence would add. `onContentFilter: "retry"` is one more sample of the same request; it rarely helps.

**Tools are never re-run.** In tool mode only the **final** model call is re-asked, over a transcript that already holds the tool calls and results. A tool request in the re-asked answer is ignored. Truncation and context-too-long retries are not available in tool mode (the loop reports no finish reason, and shrinking the prompt after tools ran would mean replaying them); empty and invalid-JSON re-asks are.

**Streaming.** A task that may re-ask is **buffered**, not streamed: the tokens of an attempt that is then retried cannot be taken back. The final answer reaches the client once. An SSE event `llm_retry` with `{reason, attempt}` (`reason`: `empty`, `truncated`, `content_filter`, `invalid_json`, `schema_mismatch`, `context_too_long`) is sent before each re-ask so a UI can show "retrying…".

**Cost.** Every attempt's tokens are summed into the turn's token usage and cost, so re-asks show up in the audit ledger and in `maxCostPerRun`. The step records `llm:retry:<taskId>` = `{reasks, unresolved}`. Metrics: `eddi.llm.recovery{action=retry|escalate, outcome, trigger}` (see [metrics](metrics.md)).

**Resume after a human-in-the-loop approval.** A resumed turn re-asks its **final answer** on the same model for an empty, invalid-JSON or schema-mismatch reply (one model call over the resumed transcript; no tool runs again) and applies the `responseValidation` actions. Truncation and context-too-long re-asks are not available there, and the circuit breaker and the cascade are not consulted: the resume continues on the model whose tool loop paused. A resume that recorded no transcript cannot re-ask and goes to `fallbackAction`.

**One log line per recovery.** Each repair, re-ask, escalation, circuit skip and served fallback writes exactly one INFO line, `LLM recovery conversationId=… agentId=… class=… action=… outcome=… attempt=… durationMs=…` (see [LLM Turn Resilience](llm-resilience.md#in-the-log)). A model that keeps failing the same way is taken out by the [circuit breaker](#circuit-breaker-skip-a-model-that-keeps-failing), which also switches off the same-model re-ask of invalid output.

### Circuit breaker: skip a model that keeps failing

Retries and re-asks fix an unlucky turn. They do nothing for a model that **cannot** answer: a revoked key, a retired model id, a prompt it always answers in prose. Every turn then pays a full attempt (and re-asks) to arrive at the same failure. The optional `circuitBreaker` takes such a model out for a while, so a cascade goes straight to its next step and a single-model task takes its `onError` path at once instead of spending the user's deadline.

```json
{
  "circuitBreaker": { "enabled": true, "window": 10, "threshold": 8, "coolDownMs": 60000 },
  "onError": { "action": "fallback" }
}
```

| Field | Meaning | Default |
| --- | --- | --- |
| `enabled` | The breaker is **off by default**: a task without it behaves exactly as before | `false` |
| `window` | How many of the most recent *counted* turns are looked at (1..1000) | `10` |
| `threshold` | How many of them must have failed with the **same class** to open the circuit (clamped to `1..window`) | `8` |
| `coolDownMs` | How long an open circuit is skipped before one probe turn is let through (0..1 hour) | `60000` |

**What counts.** A circuit belongs to one *(agent, agent version, provider, model)*; the model is the name it was actually built with, after `${vars:…}` and templating, so two cascade steps and two agents never share one. Only failures that would fail the same way again are counted:

| Class | Opens the circuit |
| --- | --- |
| `INVALID_OUTPUT` — a `convertToObject` reply that is still not valid JSON, or still breaks the response shape, after its re-asks (looked at directly, so it needs no `responseValidation` policy) | when `threshold` of the last `window` counted turns share it |
| `BAD_REQUEST`, `MODEL_NOT_FOUND` | when `threshold` of the last `window` counted turns share it |
| `AUTH`, `QUOTA_EXHAUSTED` | at once, on the first one |

Transient provider errors, rate limits and timeouts are **never** counted (and neither are context-too-long, empty replies or unclassified errors): they belong to the retry layer, and they neither fill nor drain the window. A reply that a re-ask fixed is a success.

**While open.** The model is not called. A cascade skips to its next step (trace entry `status: circuit_open`, `eddi.llm.cascade.escalations{reason=circuit_open}`, a `cascade_escalation` SSE event with that reason), and a step that is still closed answers as usual. When no step is left (every step open, or a task with a single model) the task fails with a `LifecycleException` whose cause is an `LlmCircuitOpenException` carrying the class that opened the circuit (`getFailureClass()`), or, with [`onError: fallback`](#fallback-answers-and-onerror), serves the fallback without any model call.

**Half-open.** After `coolDownMs`, **one** turn is let through as a probe (concurrent turns keep being skipped). A usable reply closes the circuit; a counted failure re-opens it for another cool-down; a probe that ends in something uncounted (a transient error, a human-in-the-loop pause) is handed on to the next turn. The probe never gets a same-model re-ask: a circuit that is open or half-open for invalid output switches the [re-ask](#recovery-policies-retry-where-it-helps) off, since repeating a known-bad pattern only burns the deadline.

**Scope.** Circuits live in memory on each node and are shared by every conversation of that agent version; they are bounded (10,000) and forgotten after an hour without traffic. A restart resets them to closed. With several nodes each opens on its own traffic.

**Alerting.** Each time a circuit **opens** (or re-opens) EDDI logs one ERROR line (`LLM circuit OPEN …`) with agent, version, provider, model, class and EDDI's reason, never model output. Going half-open and closing are logged at INFO. Metrics: `eddi.llm.circuit{state, class}` (transitions), `eddi.llm.circuit.skipped{class}` (turns that did not call the model) and the gauge `eddi.llm.circuit.open`; the Full Metrics dashboard has panels for them (see [metrics](metrics.md#llm-circuit-breaker-metrics)). A webhook or Slack notification is not built: there is no generic operator-notification hook to reuse yet, so alert from the log line or the metric.


### Debugging

When `convertToObject=true`, the raw LLM response is **always** persisted in conversation memory (key: `langchain:data`) even if JSON parsing fails. This ensures you can inspect what the LLM actually returned via the conversation log.

### Tips

- **Streaming**: Not recommended with JSON mode — the UI would show raw JSON building up. It does work (the streamed request carries the format for supported providers), but pair it with `addToOutput: "false"` and a `postResponse`
- **Provider compatibility**: see the provider matrix above. Unsupported providers rely on prompt-based enforcement
- **Schema specificity**: The more specific your `responseSchema`, the more reliable the output. Use type hints (`"string"`, `"number"`, `"boolean"`) and descriptions

---

## Deprecated parameters

### `includeFirstAgentMessage`

**Deprecated. Still honoured; do not use it in new configurations.**

It exists for one reason: Anthropic used to reject a conversation whose first
message was an assistant turn, so the flag stripped EDDI's opening greeting to
make the history start with a user message.

**That restriction is gone.** The
[Messages API reference](https://platform.claude.com/docs/en/api/messages) no
longer documents a first-message role rule anywhere, and a history beginning with
an assistant turn is accepted.

What remains is a flag whose only documented reason to exist has expired, and
which for years was implemented as *remove the first message* regardless of whose
it was — so an agent with no `ai.labs.output` step, which opens on the **user's**
turn, sent an empty history and Anthropic answered
`invalid_request_error: messages: Field required`. The removal is role-aware now,
so the flag is no longer dangerous; it is merely pointless for the case it was
written for.

**Why deprecated rather than removed.** Agent behaviour lives in JSON stored in
MongoDB and imported from ZIPs — the one backward-compatibility boundary this
codebase has. Silently ignoring a parameter an author set deliberately would be
worse than honouring it: an agent that genuinely wants its greeting withheld
would start sending it, with no diagnostic. So the flag keeps working exactly as
before, and `LlmTask` logs a WARN naming the task the first time each configured
task uses it.

**What to do:** delete it from the task. Keep it only if that agent must really
withhold its opening greeting from the model — which is a presentation choice,
not a provider requirement.

## Common Issues and Troubleshooting

### API Key Issues

- **Problem**: "Invalid API key" errors
- **Solution**: Ensure API keys are valid and have not expired. Renew them before expiry.

### Model Misconfiguration

- **Problem**: "Model not found" errors
- **Solution**: Verify model names match those supported by the provider (e.g., "gpt-4o" for OpenAI, not "gpt4")

### Timeout Issues

- **Problem**: Requests timing out
- **Solution**: Increase the `timeout` parameter value (in milliseconds). Default is often 15000 (15 seconds).
- **Problem**: A *streaming* turn is cut off after ~120s even though `timeout` is larger
- **Solution**: This was the behaviour before the `timeout`/`streamingTimeoutSeconds` unification; the backstop now follows a longer `timeout` automatically. Set `streamingTimeoutSeconds` explicitly if you need a bound that differs from the derived one — see [Timeouts and Streaming](#timeouts-and-streaming).

### Anthropic First Message Error

- **Historical problem**: the Anthropic API rejected conversations starting with an agent message
- **Solution**: Historical. The Messages API no longer documents a "first message must be
  the user's" rule, and an assistant-first history is accepted — so `includeFirstAgentMessage`
  is not the fix for a modern Anthropic failure. Leave it unset.

### `invalid_request_error: messages: Field required` (Anthropic)

- **Problem**: An agent with **no** `ai.labs.output` step — a group member that only answers,
  say — sends an empty message list.
- **Cause**: `includeFirstAgentMessage: "false"` drops the opening greeting. An agent that
  produces no greeting opens on the *user's* turn, so on the first turn there was nothing
  left to send. The removal is role-aware since 6.4.1 and only ever drops an **agent**
  message, so this cannot recur; a local Ollama smoke test will not reproduce it either,
  because Ollama accepts an empty message list.
- **Solution**: Remove `includeFirstAgentMessage` from the task (or set it to `"true"`).

### Tool Not Working

- **Problem**: Agent not using expected tools
- **Solution**:
  - Verify `enableBuiltInTools: true` is set
  - Check `builtInToolsWhitelist` includes the desired tool
  - Ensure the model supports tool calling (e.g., gpt-4o, not gpt-3.5-turbo)

### Response Not Added to Output

- **Problem**: LLM response not visible to user
- **Solution**: Set `addToOutput: "true"` in parameters, or configure `postResponse.outputBuildInstructions`

---

## Tool Execution Context

Understanding how tools execute is critical for designing new built-in tools and avoiding common pitfalls.

### Execution Path

All LLM tools execute **inside a conversation pipeline**. The full execution path is:

```
LlmTask.execute(memory)
  └─→ AgentOrchestrator.buildToolSetup(task, memory)
      └─→ every ToolSourceProvider.contribute(ToolAssemblyContext) — the context carries the memory
  └─→ LLM invokes tool
  └─→ ToolExecutionService.executeToolWrapped()
      └─→ Rate Limiter → Cache Check → Execute → Cost Tracker → Result
```

### Implicit Context

`IConversationMemory` is **always available** when tools execute. Tools that need conversation state (e.g., `userId`, `agentId`, `groupIds`) receive it from their `ToolSourceProvider`, which gets the memory in the `ToolAssemblyContext` at tool-assembly time.

This means:
- **No ThreadLocal** or request-scoped beans needed
- **No `userId` parameter** on LLM tools — the conversation always knows who the user is
- Only external interfaces (MCP, REST) that operate **outside** a conversation need explicit user identification

### LLM Tools vs MCP Tools

| Aspect | LLM Tools (built-in) | MCP Tools |
|---|---|---|
| Execution context | Inside conversation pipeline | Outside conversation |
| User identification | Implicit from `IConversationMemory` | Explicit `userId` parameter |
| Registration | `builtInToolsWhitelist` in langchain config | `McpMemoryTools.java` |
| Audience | The LLM agent itself | External AI agents or admin tooling |

---

## See Also

- [LLM Turn Resilience](llm-resilience.md) - failure taxonomy, recovery order, configuration surface and how to read the trace, metrics and logs

- [Behavior Rules](behavior-rules.md) - Triggering LLM tasks conditionally
- [HTTP Calls](httpcalls.md) - Creating custom HTTP call tools for agents
- [Security](security.md) - SSRF protection, sandboxed evaluation, tool hardening
- [Output Configuration](output-configuration.md) - Formatting agent responses
- [Conversation Memory](conversation-memory.md) - Understanding conversation state
- [Metrics](metrics.md) - Monitoring LLM performance

---

## Summary

The LLM Lifecycle Task provides a flexible, unified interface for integrating LLMs into EDDI agents:

1. ✅ **Simple by Default** - Start with basic chat, add tools when needed
2. ✅ **19 Provider Support** - OpenAI, Anthropic, Google Gemini, Google Vertex AI, Mistral, Azure, Bedrock, Oracle, Ollama, Hugging Face, Jlama + xAI, DeepSeek, Kimi, Qwen, GLM, MiniMax, OpenRouter, Groq (and any other OpenAI-compatible endpoint via `baseUrl`)
3. ✅ **Built-in Tools** - 9 tools available when you enable agent mode
4. ✅ **Tool Execution Pipeline** - Rate limiting, caching, cost tracking for every tool call
5. ✅ **Security Hardened** - SSRF protection, sandboxed math evaluation, input validation
6. ✅ **Fine-Grained Control** - Pre/post processing, context management, templating
7. ✅ **Orchestration Layer** - Conditional invocation, hybrid workflows, state persistence
8. ✅ **Easy Configuration** - Generated for you by the Manager's agent wizard or the Platform Operator

Whether you need simple chat or advanced agent capabilities, the Langchain task provides the foundation for intelligent conversational experiences in EDDI.
