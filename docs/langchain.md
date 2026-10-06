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

- **OpenAI** (ChatGPT, GPT-4, GPT-4o) — also any other OpenAI-compatible endpoint via `baseUrl`
- **OpenAI-compatible providers** with their own type: `xai`, `deepseek`, `moonshot`, `qwen`, `zhipu`, `minimax`, `openrouter`, `groq` ([details](#openai-compatible-providers))
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
| `apiKey`                   | string  | API key for the LLM provider                          | Required          |
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
> **Logging is EDDI's, not the provider's.** `logRequests`/`logResponses` are honoured by EDDI's own model decorators, which truncate the logged request to 200 and the logged response to 500 characters. They are deliberately **not** forwarded to the provider builders: langchain4j's client-level logging writes whole request and response bodies at INFO with no truncation, so switching it on would put full prompts, full conversation history and full model output into the application log. (`logRequestsAndResponses`, which only the Azure OpenAI and Gemini builders accept, is a provider-level escape hatch and *is* still forwarded — use it only where that exposure is acceptable.)
>
> **An unusable `timeout` is ignored, not fatal.** The value is normalised once before it reaches a provider builder: it is trimmed, and dropped entirely when it is blank, non-numeric (`"30s"`) or non-positive (`"0"`, historically "no timeout"), with a WARN naming the model type. Provider builders parse the value with an unguarded `Long.parseLong`, so without this a stored config carrying one of those values would fail on every turn. `" 5000 "` and `"5000"` are the same timeout and share one cached model.
### Output Token Limits

The `maxTokens` parameter controls the **maximum number of output tokens** the LLM can generate per response. This is a ceiling, not a target — the model generates only what it needs and stops. Setting it higher does not increase cost unless the model actually produces more tokens; the `timeout` parameter is the real cost safety net.

> [!WARNING]
> **Anthropic + Extended Thinking**: Models with extended thinking (e.g. `claude-sonnet-5`, `claude-sonnet-4`) count **thinking tokens** toward `maxTokens`. If the limit is too low, thinking can consume the entire budget, leaving **zero tokens for the actual response** — resulting in empty/null output. This is a silent failure: the API returns successfully, token usage shows consumption, but `text()` is `null`.

#### Provider Limits and EDDI Defaults

| Provider | Config Key | Max Output Capability | EDDI Default (if omitted) | Notes |
|----------|------------|----------------------|--------------------------|-------|
| **Anthropic** | `maxTokens` | 8,192 (standard) / 128,000 (with extended thinking) | **16,384** | Required by API. Set higher for thinking models |
| **Gemini** | `maxOutputTokens` | 65,536 | Provider SDK default | Use `maxOutputTokens` (not `maxTokens`) |
| **OpenAI** | `maxTokens` | 4,096–16,384 (model-dependent) | Provider SDK default | |
| **Azure OpenAI** | `maxTokens` | Same as OpenAI | Provider SDK default | |
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
        "includeFirstAgentMessage": "false",
        "addToOutput": "true"
      }
    }
  ]
}
```

> **`includeFirstAgentMessage` is deprecated and no longer needed for Anthropic.** This example keeps
> `"false"` only because countless existing configs carry it. The advice it used to
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
  for the model — it takes room away.

For an air-gapped deployment, pre-seed `modelCachePath` from a machine that has network
access and mount it read-only. Jlama reaches out to Hugging Face whenever the model is
not already in the cache, so an empty cache with no egress fails rather than degrades.

**Note**: Jlama runs models locally in Java without requiring external services like
Ollama. It is CPU inference — there is no GPU path — so it suits small quantized models
(1B–8B) rather than large ones. For a GPU or a larger model, serve it with vLLM or
`llama-server` and point the `openai` provider at it via `baseUrl`.

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
  the default endpoint. An unknown region fails with the list of valid ids. `baseUrl` is still
  checked against the cloud-metadata block list.
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

**Any other endpoint.** Cohere, Together, a corporate gateway and the like still work through
`type: openai` with a `baseUrl`; they simply have no preset.

**Known limitation.** A durable tool-approval pause stores the in-flight transcript, including
each assistant turn's `thinking`, and resumes from it, so DeepSeek and Kimi tool turns survive a
pause. On the degraded resume (the transcript exceeded `eddi.hitl.tool.transcript-max-bytes`) the
gating assistant message is replayed with its thinking, unless that single message exceeds its
64 KB cap: it is then persisted without the thinking, and those two providers can reject the
resumed request with a 400. Raise the cap or approve such calls quickly if you hit it.

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
| `maxToolContextTokens`     | int      | Aggregate token ceiling on the **in-turn** tool-call context (tool requests + tool results across all loop iterations). The oldest complete tool exchange is evicted when exceeded. `-1`/`0` disables. See [In-Turn Tool Context Budget](#in-turn-tool-context-budget). | 60000 |
| **Cost & Performance**     |          |                                                  |                        |
| `maxBudgetPerConversation` | number   | Ceiling on accumulated **tool** cost per conversation, in USD. Records cost; only refuses calls when `enforceBudget` is on | (unlimited) |
| `enforceBudget`            | boolean  | Refuse tool calls once `maxBudgetPerConversation` is passed. **Opt-in** — a ceiling without it is report-only, and is named in a startup WARN | false (`eddi.tools.budget.enforce-by-default`) |
| `toolPricing`              | map      | Per-call tool prices in USD. Keyed on the built-in slug (`{"websearch": 0.005}`) or on a single dispatch name (`{"searchNews": 0.01}`), which takes precedence — so one operation can be priced apart from its siblings | (built-in defaults) |
| `inputPricePer1M` / `outputPricePer1M` | number | Token prices in USD per 1M tokens for this task's **model calls**, feeding the conversation's tracked cost (and any group cost ceiling). Applies to non-cascade calls; a [model cascade](model-cascade.md) prices its steps via its own fields of the same name. Negative values fail deployment | (unpriced — $0) |
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

## Built-in Tools

When `enableBuiltInTools: true`, you can use these tools:

| Tool Name           | Description                                     | Whitelist Value  |
| ------------------- | ----------------------------------------------- | ---------------- |
| **Calculator**      | Safe math expressions (sandboxed parser)        | `calculator`     |
| **Date/Time**       | Get current date, time, timezone info           | `datetime`       |
| **Web Search**      | Search the web (includes Wikipedia & News)      | `websearch`      |
| **Data Formatter**  | Format JSON, CSV, XML data                      | `dataformatter`  |
| **Web Scraper**     | Extract content from web pages (SSRF-protected) | `webscraper`     |
| **Text Summarizer** | Summarize long text                             | `textsummarizer` |
| **PDF Reader**      | Extract text from PDF URLs (SSRF-protected)     | `pdfreader`      |
| **Weather**         | Get weather information                         | `weather`        |
| **Tool Response Paging** | Fetch the next page of a tool response that was truncated by `toolResponseLimits` | `fetch_page` / `fetch_tool_response_page` |
| **Conversation Recall** | Drill back into turns the [rolling summary](#rolling-conversation-summary) has compressed. Only assembled when `conversationSummary.enabled` is true | `conversationRecall` |

> Because a non-empty `builtInToolsWhitelist` enables **only** the tools it names, a whitelist that includes verbose tools should also include `fetch_page` — otherwise truncated tool responses cannot be paged through.

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

- **OpenAI / Azure OpenAI**: Uses tiktoken-based tokenizer (accurate, model-specific)
- **All other providers**: Uses an approximate tokenizer (characters ÷ 4)

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
| **Cost Tracking** | Per-conversation tool-cost accounting, with an opt-in ceiling and automatic stale-data eviction | `enableCostTracking`, `toolPricing`, `maxBudgetPerConversation`, `enforceBudget` |
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

Enforcement is **opt-in**: a configured ceiling records cost but refuses nothing
until you add `enforceBudget: true`. Built-in tools priced at $0.00 until the
canonical-slug fix in this release, so enforcing automatically would make those
ceilings bind for the first time and start aborting tool calls mid-conversation
on upgrade.

That choice has a real cost, which is why the engine warns rather than staying
quiet: http, MCP, A2A and dynamic tools dispatch under their configured name, so
a tool called `websearch` **was** priced and refused before `enforceBudget`
existed. If you relied on such a ceiling, add the flag — every task carrying a
ceiling without it is named once in a startup WARN. Cost is tracked and reported
(`GET /llm/tools/costs`, `eddi.tool.costs`) either way. The deployment-wide
default comes from `eddi.tools.budget.enforce-by-default` (default `false`).

The check runs *before* each call and uses `<=`, so the call that crosses the
ceiling still completes and the next one is refused with
`Error: Budget exceeded for conversation <id>`.

Default per-call prices: `webscraper` $0.002, `websearch` $0.001, `pdfreader`
$0.001, `weather` $0.0005; `calculator`, `datetime`, `dataformatter` and
`textsummarizer` are free. Anything not in that table — http, mcp, a2a, dynamic
and the remaining built-ins — costs $0.00 until you price it with `toolPricing`.
Negative `toolPricing` values are clamped to 0.0.

#### Tool cache scoping

**What is cached.** Built-in tools, except the stateful ones (artifacts, group
tasks, dynamic agents, memory, recall), which are never cached. **HTTP-call, MCP and
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
