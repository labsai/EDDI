# LLM Parameters Reference

> Generated from [`model-parameters.json`](https://github.com/labsai/EDDI/blob/main/src/main/resources/llm/model-parameters.json)
> by `python scripts/gen-llm-parameters-doc.py`. Do not edit by hand.

Every LLM task takes a flat `parameters` map of **strings**. This page lists, per provider, every
parameter EDDI reads, what it does, and which model versions reject it or ignore it. The Manager shows
the same information as typed controls under **Model settings** in the LLM task editor.

```json
{
  "type": "openai",
  "parameters": {
    "modelName": "gpt-5.1",
    "apiKey": "${vault:openai-key}",
    "reasoningEffort": "low",
    "maxCompletionTokens": "4096",
    "stopSequences": "[\"END\"]",
    "customParameters": "{\"web_search_options\": {}}"
  }
}
```

## Value formats

| Type | Written as |
| --- | --- |
| `int`, `double` | `"4096"`, `"0.7"` |
| `boolean` | `"true"` / `"false"` |
| `enum` | one of the listed values (case-insensitive) |
| `stringList` | a JSON array `["END","\n\n"]`, or a comma list `END,STOP` (use the array when an item contains a comma) |
| `json` | a JSON object, e.g. `{"X-Team": "search"}` |

A value that cannot be parsed is ignored with a warning in the log (naming the parameter, never its
value), and the provider default stays in place.

## Model-aware behaviour

Providers change what a model accepts from one generation to the next. Reasoning models reject
`temperature`; newer Claude models reject non-default sampling parameters; some Gemini models ignore
them. EDDI knows this per model id:

- **Rejected**: the provider would answer with an HTTP error. EDDI removes the parameter before the
  request is built and logs one warning naming the parameter, the model and the reason. Set
  `eddi.llm.parameter-catalog.drop-rejected=false` to keep sending it and only warn.
  Where the model renames the parameter (`maxTokens` became `maxCompletionTokens` on OpenAI and Azure
  reasoning models), the value is carried over to the new name unless that is configured too, so an
  output cap is never silently lost.
- **No effect**: harmless but pointless. EDDI keeps it and logs one warning.
- Unknown provider or model id: nothing is restricted (fail open).

The model id is read from the model parameter of the provider (`modelName`, `model`, `modelId` or
`deploymentName`). On Azure OpenAI the deployment name says nothing about the underlying model, so set
`baseModel` (never sent to Azure) to get the same checks.

Anything not listed for a provider can still be sent with `customParameters` (extra top-level body
fields) and `customHeaders` on the providers that offer them.

## Limits worth knowing

- **Mistral**: `reasoning_effort`, `service_tier`, `top_k`, `min_tokens` and `prompt_mode` have no
  langchain4j setter, so they cannot be set from EDDI.
- **Ollama**: `think` is `true`/`false` only; level-based thinking (`low`/`medium`/`high`) is not modelled by langchain4j.
- **Hugging Face**: `baseUrl` is the service root, without `models/`.
- **Jlama**: the worker thread count is process-wide in the library and is not exposed.
- Rules are written only where an official vendor source states the behaviour; where sources conflict or
  say nothing, the parameter simply applies.


## OpenAI (`openai`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |

Model-specific behaviour:

- `temperature` — **Rejected** on models matching `^(o\d|gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.5-pro|gpt-6-astra|gpt-6\.1-sol)`: This reasoning model rejects sampling settings such as temperature and topP.
- `temperature` — **Rejected** on models matching `^gpt-5\.[1-4](?!\d)`: This model accepts sampling settings only while reasoningEffort is none (its default).
- `temperature` — **Rejected** on models matching `^(gpt-5\.[5-9](?!\d)|gpt-6)`: This model accepts sampling settings only when reasoningEffort is set to none.
- `topP` — **Rejected** on models matching `^(o\d|gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.5-pro|gpt-6-astra|gpt-6\.1-sol)`: This reasoning model rejects sampling settings such as temperature and topP.
- `topP` — **Rejected** on models matching `^gpt-5\.[1-4](?!\d)`: This model accepts sampling settings only while reasoningEffort is none (its default).
- `topP` — **Rejected** on models matching `^(gpt-5\.[5-9](?!\d)|gpt-6)`: This model accepts sampling settings only when reasoningEffort is set to none.
- `frequencyPenalty` — **Rejected** on models matching `^(o\d|gpt-5(?!.*chat)|gpt-6)`: Reasoning models reject frequencyPenalty.
- `presencePenalty` — **Rejected** on models matching `^(o\d|gpt-5(?!.*chat)|gpt-6)`: Reasoning models reject presencePenalty.
- `logitBias` — **Rejected** on models matching `^(o\d|gpt-5(?!.*chat)|gpt-6)`: Reasoning models reject logitBias.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` | How much the model thinks before answering; more effort is slower and costs more. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

Model-specific behaviour:

- `reasoningEffort` — **Rejected** on models matching `^(gpt-4o|gpt-4\.1|o1-mini)`: This model has no reasoning effort setting.
- `reasoningEffort` — **Rejected** on models matching `^(gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.5-pro|gpt-6-astra|gpt-6\.1-sol)`: This model does not support reasoningEffort none.
- `reasoningEffort` — **Rejected** on models matching `^(gpt-5\.\d|gpt-6)`: reasoningEffort minimal exists only on the original gpt-5 models.
- `reasoningEffort` — **Rejected** on models matching `^(gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.1(?!\d)(?!-codex-max))`: This model does not support reasoningEffort xhigh.
- `reasoningEffort` — **Rejected** on models matching `^(gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.[1-5](?!\d))`: reasoningEffort max needs gpt-5.6 or gpt-6.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

Model-specific behaviour:

- `maxTokens` — **Rejected** on models matching `^(o\d|gpt-5(?!.*chat)|gpt-6)`: Reasoning models reject maxTokens; EDDI sends its value as maxCompletionTokens instead (unless that is set). The value is sent as `maxCompletionTokens` instead.
- `stopSequences` — **Rejected** on models matching `^(o3(-pro)?|o4-mini)(-\d{4}-\d{2}-\d{2})?$`: o3 and o4-mini do not support stop sequences.

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## Anthropic (`anthropic`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–1; default `1` | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |
| `topK` | int | 0– | Only consider this many of the most likely next words. |

Model-specific behaviour:

- `temperature` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-(5(-1)?|preview)|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: This Claude model accepts only the default temperature of 1.
- `temperature` — **Rejected** on models matching `^claude-(opus-4-6|sonnet-4-6|opus-4-5|sonnet-4-5|haiku-4-5)(-\d{8})?$`: Temperature cannot be changed while thinking is on.
- `topP` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-(5(-1)?|preview)|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: This Claude model rejects temperature and topP sent together.
- `topP` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-(5(-1)?|preview)|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: This Claude model accepts only the default topP of 0.99.
- `topP` — **Rejected** on models matching `^claude-(sonnet-4-5|haiku-4-5)(-\d{8})?$`: Sonnet 4.5 and Haiku 4.5 accept temperature or topP, not both.
- `topK` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-(5(-1)?|preview)|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: This Claude model rejects topK.
- `topK` — **Rejected** on models matching `^claude-(opus-4-6|sonnet-4-6|opus-4-5|sonnet-4-5|haiku-4-5)(-\d{8})?$`: topK cannot be used while thinking is on.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `thinkingType` | enum | `adaptive` / `enabled` / `disabled` / `between_tools` | Whether and how the model thinks before answering; the default depends on the model. |
| `thinkingBudgetTokens` | int | 1024– | How many tokens the model may spend thinking when thinking mode is enabled; at least 1024 and below maxTokens. |
| `thinkingDisplay` | enum | `summarized` / `omitted` | Whether the thinking text is summarized or left out of the response; billing is the same. |
| `effort` | enum | `low` / `medium` / `high` / `xhigh` / `max` | How much work the model puts into an answer; higher is slower and costs more. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

Model-specific behaviour:

- `thinkingType` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-5(-1)?|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: Thinking mode enabled (with a fixed budget) is not supported on this model; use adaptive.
- `thinkingType` — **Rejected** on models matching `^claude-(opus-4-5|haiku-4-5|sonnet-4-5|opus-4-1|opus-4|sonnet-4)(-\d{8})?$`: Adaptive thinking is not supported on this model; use enabled with a budget.
- `thinkingType` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-(5(-1)?|preview)|opus-5-5|sonnet-5-5)(-\d{8})?$`: This model always thinks; disabled is not accepted.
- `thinkingType` — **Rejected** on models matching `^claude-(opus-5|haiku-5-5)(-\d{8})?$`: Thinking can be disabled on this model only at effort high or lower.
- `thinkingType` — **Rejected** on models matching `^claude-(?!sonnet-5-5(-\d{8})?$)`: between_tools exists only on Claude Sonnet 5.5.
- `thinkingType` — **Rejected** on models matching `^claude-sonnet-5-5(-\d{8})?$`: between_tools cannot be combined with effort xhigh or max.
- `thinkingBudgetTokens` — **Rejected** on models matching `^claude-(fable-5(-1)?|mythos-5(-1)?|opus-(5-5|5|4-8|4-7)|sonnet-5(-5)?|haiku-5-5)(-\d{8})?$`: A fixed thinking budget is not supported on this model; use effort.
- `thinkingDisplay` — **Rejected** on models matching `^claude-`: thinkingDisplay cannot be combined with thinking disabled or between_tools.
- `effort` — **Rejected** on models matching `^claude-(sonnet-4-5|haiku-4-5)(-\d{8})?$`: This model has no effort setting.
- `effort` — **Rejected** on models matching `^claude-(opus-4-6|sonnet-4-6|opus-4-5|mythos-preview)(-\d{8})?$`: Effort xhigh is not supported on this model.
- `effort` — **Rejected** on models matching `^claude-opus-4-5(-\d{8})?$`: Effort max is not supported on Claude Opus 4.5.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1–128000; default `16384` | Upper limit on the tokens the model may produce, including its thinking. |
| `stopSequences` | stringList |  | Pieces of text that make the model stop writing when it produces them. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `disableParallelToolUse` | boolean | default `False` | Allow at most one tool call per step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `cacheSystemMessages` | boolean |  | Mark the system prompt for prompt caching so repeated requests are cheaper. |
| `cacheTools` | boolean |  | Mark the tool definitions for prompt caching. |
| `cacheAutomatically` | boolean |  | Let Anthropic place the cache breakpoint automatically. |
| `cacheTtl` | enum | `5m` / `1h`; default `5m` | How long cached content is kept; 1h costs more to write than 5m. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `userId` | string |  | An opaque id of the end user (a hash, never personal data) that helps Anthropic detect abuse. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `version` | string | default `2023-06-01` | The anthropic-version header to send. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `beta` | string |  | Comma-separated beta feature names sent in the anthropic-beta header. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## Google Gemini (`gemini`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable; Gemini 3 works best at 1. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |
| `topK` | int | 1– | Only consider this many of the most likely next words. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `frequencyPenalty` | double |  | Positive values discourage the model from repeating the same words. Not every Gemini model accepts it; if the API answers with an error, remove it. |
| `presencePenalty` | double |  | Positive values encourage the model to move on to new topics. Not every Gemini model accepts it; if the API answers with an error, remove it. |

Model-specific behaviour:

- `temperature` — **No effect** on models matching `^(models/)?gemini-(3\.([6-9]|[1-9]\d)|[4-9])[-.]`: Gemini 3.6 Flash and newer ignore temperature.
- `topP` — **No effect** on models matching `^(models/)?gemini-(3\.([6-9]|[1-9]\d)|[4-9])[-.]`: Gemini 3.6 Flash and newer ignore topP.
- `topK` — **No effect** on models matching `^(models/)?gemini-(3\.([6-9]|[1-9]\d)|[4-9])[-.]`: Gemini 3.6 Flash and newer ignore topK.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `thinkingLevel` | enum | `minimal` / `low` / `medium` / `high` | How hard Gemini 3 and later models think before answering. |
| `thinkingBudget` | int | -1–32768 | Token budget for thinking on Gemini 2.5 models; -1 lets the model decide and 0 turns thinking off where allowed. |
| `returnThinking` | boolean | default `True` | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean | default `True` | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

Model-specific behaviour:

- `thinkingLevel` — **Rejected** on models matching `^(models/)?gemini-2\.5-`: Gemini 2.5 models take thinkingBudget, not thinkingLevel.
- `thinkingLevel` — **Rejected** on models matching `^(models/)?gemini-([4-9][-.]|3\.8-flash)`: This model does not support thinking level minimal.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxOutputTokens` | int | 1– | Upper limit on the tokens the model may produce, including its thinking. |
| `stopSequences` | stringList |  | Up to five pieces of text that make the model stop writing when it produces them. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `allowCodeExecution` | boolean |  | Let the model write and run Python code to work out answers. |
| `allowGoogleSearch` | boolean |  | Let the model ground its answers in Google Search results. |
| `allowGoogleMaps` | boolean |  | Let the model ground its answers in Google Maps data. |
| `allowUrlContext` | boolean |  | Let the model read web pages whose links appear in the conversation. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `cachedContentName` | string |  | Name of an explicit context cache to reuse, such as cachedContents/abc123. |

Model-specific behaviour:

- `cachedContentName` — **Rejected** on models matching `^(models/)?gemini-[4-9][-.]`: Gemini 4 and later support only implicit caching.

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `safetySettings` | json |  | Blocking thresholds per harm category, as JSON such as {"HARASSMENT":"BLOCK_ONLY_HIGH"}. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `mediaResolution` | enum | `low` / `medium` / `high` | How much detail images and video are read with; higher uses more tokens. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Google Vertex AI Gemini (`gemini-vertex`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |
| `topK` | int | 1– | Only consider this many of the most likely next words. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxOutputTokens` | int | 1– | Upper limit on the tokens the model may produce. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `useGoogleSearch` | boolean |  | Let the model ground its answers in Google Search results. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `safetySettings` | json |  | Blocking thresholds per harm category, as JSON such as {"HARASSMENT":"BLOCK_ONLY_HIGH"}. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `projectId` | string |  | The Google Cloud project that runs the requests. |
| `location` | string |  | The Google Cloud region, such as us-central1 or global. |
| `publisher` | string |  | Legacy name for location; used only when location is not set. |
| `apiEndpoint` | string |  | Override the Vertex AI host, for example for a private endpoint. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `labels` | json |  | Billing and reporting labels, as a JSON object of lower-case keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Azure OpenAI (`azure-openai`)


### Model

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `baseModel` | string |  | The model your deployment runs, such as gpt-4.1; EDDI uses it only to know which settings that model accepts. |

### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |

Model-specific behaviour:

- `temperature` — **Rejected** on models matching `^(o\d|codex-mini|gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.5-pro)`: This Azure reasoning model rejects sampling settings such as temperature and topP.
- `temperature` — **Rejected** on models matching `^gpt-5\.[1-4](?!\d)`: This Azure model accepts sampling settings only while reasoningEffort is none (its default).
- `temperature` — **Rejected** on models matching `^(gpt-5\.[5-9](?!\d))`: This Azure model accepts sampling settings only when reasoningEffort is set to none.
- `topP` — **Rejected** on models matching `^(o\d|codex-mini|gpt-5(-mini|-nano|-pro)?(-\d{4}-\d{2}-\d{2})?$|gpt-5\.5-pro)`: This Azure reasoning model rejects sampling settings such as temperature and topP.
- `topP` — **Rejected** on models matching `^gpt-5\.[1-4](?!\d)`: This Azure model accepts sampling settings only while reasoningEffort is none (its default).
- `topP` — **Rejected** on models matching `^(gpt-5\.[5-9](?!\d))`: This Azure model accepts sampling settings only when reasoningEffort is set to none.
- `frequencyPenalty` — **Rejected** on models matching `^(o\d|codex|gpt-5(?!.*chat)|gpt-6-(sol|luna)|gpt-6\.1)`: Azure reasoning models reject frequencyPenalty.
- `presencePenalty` — **Rejected** on models matching `^(o\d|codex|gpt-5(?!.*chat)|gpt-6-(sol|luna)|gpt-6\.1)`: Azure reasoning models reject presencePenalty.
- `logitBias` — **Rejected** on models matching `^(o\d|codex|gpt-5(?!.*chat)|gpt-6)`: Azure reasoning models reject logitBias.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` | How much the model thinks before answering; more effort is slower and costs more. |

Model-specific behaviour:

- `reasoningEffort` — **Rejected** on models matching `^(gpt-4|o1-mini)`: This model has no reasoning effort setting.
- `reasoningEffort` — **Rejected** on models matching `^gpt-5(-mini|-nano|-pro|-codex)?$`: reasoningEffort none needs gpt-5.1 or later.
- `reasoningEffort` — **Rejected** on models matching `^(gpt-5\.\d|gpt-6|gpt-5-codex|gpt-5-pro)`: reasoningEffort minimal exists only on gpt-5, gpt-5-mini and gpt-5-nano.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

Model-specific behaviour:

- `maxTokens` — **Rejected** on models matching `^(o\d|codex|gpt-5(?!.*chat)|gpt-6)`: Reasoning models reject maxTokens; EDDI sends its value as maxCompletionTokens instead (unless that is set). The value is sent as `maxCompletionTokens` instead.

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | An identifier of the end user for abuse monitoring. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `endpoint` | string |  | Your Azure OpenAI resource URL, such as https://my-resource.openai.azure.com/. |
| `serviceVersion` | string |  | The Azure OpenAI api-version to call. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Mistral AI (`mistral`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0– | Higher values make answers more varied; Mistral recommends 0 to 0.7. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `seed` | int | 0– | Ask for repeatable answers to identical requests. |
| `frequencyPenalty` | double |  | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double |  | Positive values encourage the model to use a wider vocabulary. |

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 0– | Upper limit on the tokens the model may produce. |
| `stopSequences` | stringList |  | Pieces of text that make the model stop writing when it produces them. |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `safePrompt` | boolean | default `False` | Add Mistral's safety instructions in front of the conversation. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Ollama (`ollama`)


### Model

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `numCtx` | int | 1– | How many tokens of conversation the model can see; longer prompts are cut silently and larger values use more memory. |

### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–; default `0.8` | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1; default `0.9` | Only consider the most likely words whose probabilities add up to this share. |
| `topK` | int | 0–; default `40` | Only consider this many of the most likely next words. |
| `minP` | double | 0–1; default `0` | Drop words whose probability is below this share of the most likely word's. |
| `repeatPenalty` | double | 0–; default `1` | Values above 1 discourage repetition; 1 turns it off. |
| `repeatLastN` | int | -1–; default `64` | How many recent tokens the repeat penalty looks at; 0 turns it off and -1 uses the whole context. |
| `seed` | int |  | Fixed random seed for repeatable answers. |

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `think` | boolean |  | Turn reasoning on or off for thinking models; leave empty to let the model decide. |
| `returnThinking` | boolean | default `False` | Keep the model's reasoning text from the response instead of discarding it. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | -1–; default `-1` | Upper limit on the tokens the model may produce; -1 means no limit. |
| `stopSequences` | stringList |  | Pieces of text that make the model stop writing when it produces them. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `truncate` | boolean |  | Cut the oldest history when the prompt does not fit the context window instead of failing. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Amazon Bedrock (`bedrock`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–1 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |

Model-specific behaviour:

- `temperature` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(fable-5|mythos|opus-(5|4-8|4-7)|sonnet-5|haiku-5-5)`: This Claude model accepts only the default temperature of 1.
- `topP` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(fable-5|mythos|opus-(5|4-8|4-7)|sonnet-5|haiku-5-5)`: This Claude model rejects temperature and topP sent together.
- `topP` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(fable-5|mythos|opus-(5|4-8|4-7)|sonnet-5|haiku-5-5)`: This Claude model accepts only the default topP of 0.99.
- `topP` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(sonnet-4-5|haiku-4-5)`: Sonnet 4.5 and Haiku 4.5 accept temperature or topP, not both.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningBudgetTokens` | int | 1024– | Turn on Claude's extended thinking with this many thinking tokens (at least 1024). |
| `reasoningEffort` | enum | `low` / `medium` / `high` / `xhigh` / `max` | Turn on Claude's adaptive thinking at this effort level. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

Model-specific behaviour:

- `reasoningBudgetTokens` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(fable-5|mythos-5|opus-(5|4-8|4-7)|sonnet-5|haiku-5-5)`: A fixed thinking budget is not supported on this Claude model; use reasoningEffort.
- `reasoningEffort` — **Rejected** on models matching `^((global|us|eu|jp|au|apac)\.)?anthropic\.claude-(opus-4-5|haiku-4-5|sonnet-4-5|opus-4-1|opus-4|sonnet-4)(-\d{8})?(-v\d+(:\d+)?)?$`: Adaptive thinking is not supported on this Claude model; use reasoningBudgetTokens.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Upper limit on the tokens the model may produce. |
| `stopSequences` | stringList |  | Pieces of text that make the model stop writing when it produces them. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCachePlacement` | enum | `AFTER_SYSTEM` / `AFTER_USER_MESSAGE` / `AFTER_LAST_USER_MESSAGE` / `AFTER_TOOLS` | Where to place a prompt cache checkpoint so repeated requests are cheaper. |
| `promptCacheTtl` | enum | `5m` / `1h`; default `5m` | How long the cache checkpoint is kept; 1h works only on models that support it. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `guardrailId` | string |  | The Bedrock guardrail to apply to every request. |
| `guardrailVersion` | string |  | The guardrail version to apply, a number or DRAFT. |
| `guardrailStreamMode` | enum | `SYNC` / `ASYNC` | Whether streamed answers wait for the guardrail (sync) or are checked in the background (async). |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `region` | string |  | The AWS region to call, such as us-east-1. |
| `serviceTier` | enum | `PRIORITY` / `DEFAULT` / `FLEX` / `RESERVED` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `additionalModelRequestFields` | json |  | Extra model-specific request fields passed through unchanged, as a JSON object. |
| `requestMetadata` | json |  | Up to 16 string tags for filtering invocation logs, as a JSON object. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |

## Hugging Face (`huggingface`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0– | Higher values make answers more varied, lower values more predictable. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxNewTokens` | int | 1– | Upper limit on the tokens the model may produce. |
| `returnFullText` | boolean |  | Include the prompt in front of the generated answer. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `waitForModel` | boolean |  | Wait for a cold model to load instead of failing right away. |

## Jlama (`jlama`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0– | Higher values make answers more varied, lower values more predictable. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Upper limit on the tokens the model may produce. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `modelCachePath` | string |  | Folder where downloaded models are stored. |
| `workingDirectory` | string |  | Folder for the model's working files. |
| `quantizeModelAtRuntime` | boolean |  | Shrink the model in memory when it loads, trading some quality for speed. |
| `workingQuantizedType` | string |  | Number format used while running, such as I8 or F32. |

## Oracle Cloud Generative AI (`oracle-genai`)


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0– | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share. |
| `topK` | int |  | Only consider this many of the most likely next words. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `frequencyPenalty` | double |  | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double |  | Positive values encourage the model to move on to new topics. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Upper limit on the tokens the model may produce. |
| `stopSequences` | stringList |  | Pieces of text that make the model stop writing when it produces them. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `compartmentId` | string |  | The OCI compartment that runs the requests. |
| `configProfile` | string |  | The profile in the OCI config file whose credentials are used. |
| `region` | string |  | The OCI region to call, such as us-chicago-1. |
| `servingType` | enum | `ON_DEMAND` / `DEDICATED` | Use the shared on-demand model or a dedicated AI cluster. |

## xAI Grok (`xai`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage repetition; reasoning models reject it. |
| `presencePenalty` | double | -2–2 | Positive values encourage new topics; reasoning models reject it. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Not supported by the xAI API. |

Model-specific behaviour:

- `frequencyPenalty` — **Rejected** on models matching `^(grok-4\.(7|6|5)($|-)|grok-4\.20-0309-reasoning$)`: xAI reasoning models reject frequencyPenalty.
- `frequencyPenalty` — **Rejected** on models matching `^grok-4\.3($|-)`: xAI grok-4.3 while it reasons (reasoningEffort other than none) reject frequencyPenalty.
- `presencePenalty` — **Rejected** on models matching `^(grok-4\.(7|6|5)($|-)|grok-4\.20-0309-reasoning$)`: xAI reasoning models reject presencePenalty.
- `presencePenalty` — **Rejected** on models matching `^grok-4\.3($|-)`: xAI grok-4.3 while it reasons (reasoningEffort other than none) reject presencePenalty.
- `logitBias` — **No effect** on models matching `^grok-`: The xAI API does not support logitBias.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `low` / `medium` / `high` / `xhigh` | How much the model thinks before answering; grok-4.3 also accepts none. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing; reasoning models reject them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

Model-specific behaviour:

- `stopSequences` — **Rejected** on models matching `^(grok-4\.(7|6|5)($|-)|grok-4\.20-0309-reasoning$)`: xAI reasoning models reject stop sequences.
- `stopSequences` — **Rejected** on models matching `^grok-4\.3($|-)`: xAI grok-4.3 while it reasons (reasoningEffort other than none) reject stop sequences.

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `default` / `priority` / `fast` | Processing tier; fast and priority are the same. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## DeepSeek (`deepseek`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2; default `1` | Higher values make answers more varied; has no effect while thinking is on. |
| `topP` | double | 0–1; default `1` | Takes effect only in thinking mode, between 0.95 and 1. |
| `frequencyPenalty` | double |  | No longer supported by DeepSeek. |
| `presencePenalty` | double |  | No longer supported by DeepSeek. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |

Model-specific behaviour:

- `temperature` — **No effect** on models matching `^deepseek-`: DeepSeek ignores temperature in thinking mode (the default).
- `topP` — **No effect** on models matching `^deepseek-`: DeepSeek ignores topP when thinking is disabled.
- `frequencyPenalty` — **No effect** on models matching `^deepseek-`: DeepSeek no longer supports frequencyPenalty.
- `presencePenalty` — **No effect** on models matching `^deepseek-`: DeepSeek no longer supports presencePenalty.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `low` / `high` / `max`; default `high` | How much the model thinks; none turns thinking off. |
| `returnThinking` | boolean | default `True` | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean | default `True` | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `thinkingType` → `thinking.type` | enum | `enabled` / `disabled`; default `enabled` | Turn thinking mode on or off; it is on by default. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## Moonshot Kimi (`moonshot`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | default `1` | Fixed by Kimi: 1 for kimi-k3 and kimi-k2.7-code; on kimi-k2.6, 1 while thinking and 0.6 without. |
| `topP` | double | default `0.95` | Fixed at 0.95 by Kimi. |
| `frequencyPenalty` | double | default `0` | Fixed at 0 by Kimi. |
| `presencePenalty` | double | default `0` | Fixed at 0 by Kimi. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |

Model-specific behaviour:

- `temperature` — **Rejected** on models matching `^kimi-(k3|k2\.7-code)`: This Kimi model accepts only temperature 1.
- `temperature` — **Rejected** on models matching `^kimi-k2\.6$`: kimi-k2.6 accepts only temperature 0.6 when thinking is disabled.
- `temperature` — **Rejected** on models matching `^kimi-k2\.6$`: kimi-k2.6 accepts only temperature 1 while thinking.
- `topP` — **Rejected** on models matching `^kimi-(k3|k2\.6|k2\.7-code)`: Kimi accepts only topP 0.95.
- `frequencyPenalty` — **Rejected** on models matching `^kimi-(k3|k2\.6|k2\.7-code)`: Kimi accepts only frequencyPenalty 0.
- `presencePenalty` — **Rejected** on models matching `^kimi-(k3|k2\.6|k2\.7-code)`: Kimi accepts only presencePenalty 0.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `low` / `high` / `max` | How much kimi-k3 thinks; the kimi-k2 models use thinking instead. |
| `returnThinking` | boolean | default `True` | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean | default `True` | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `thinkingType` → `thinking.type` | enum | `enabled` / `disabled` | Turn thinking on or off on kimi-k2.6; kimi-k3 and kimi-k2.7-code always think. |

Model-specific behaviour:

- `thinkingType` — **Rejected** on models matching `^kimi-k3`: kimi-k3 does not take a thinking setting.
- `thinkingType` — **Rejected** on models matching `^kimi-k2\.7-code`: kimi-k2.7-code always thinks; disabled is not accepted.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to five short pieces of text that make the model stop writing. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## Alibaba Qwen (`qwen`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied; change this or topP, not both. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |
| `topK` → `top_k` | int | 0–100; default `20` | Only consider this many of the most likely next words; above 100 turns it off. |
| `repetitionPenalty` → `repetition_penalty` | double | 0– | Values above 1 discourage repetition; 1 means no penalty. |

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` | How much qwen3.8 models think; none turns thinking off. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `enableThinking` → `enable_thinking` | boolean | default `True` | Turn the model's thinking on or off; it is on by default. |
| `thinkingBudget` → `thinking_budget` | int | 1– | Most tokens the model may spend thinking; do not combine with reasoningEffort. |
| `preserveThinking` → `preserve_thinking` | boolean |  | Let the model read its reasoning from earlier turns; that reasoning is billed as input. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |
| `enableSearch` → `enable_search` | boolean | default `False` | Let the model search the web; this can use more tokens. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## Z.ai GLM (Zhipu) (`zhipu`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–1; default `1` | Higher values make answers more varied; the range is 0 to 1 and it has no effect when sampling is off. |
| `topP` | double | 0.01–1; default `0.95` | Only consider the most likely words whose probabilities add up to this share; no effect when sampling is off. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |
| `doSample` → `do_sample` | boolean | default `True` | Turn random sampling off to always pick the most likely word; temperature and topP then have no effect. |

Model-specific behaviour:

- `temperature` — **No effect** on models matching `^glm-`: Temperature has no effect while doSample is false.
- `topP` — **No effect** on models matching `^glm-`: topP has no effect while doSample is false.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` | How much GLM-5.2 and GLM-5.3 think while thinking is on. |
| `returnThinking` | boolean | default `True` | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean | default `True` | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `thinkingType` → `thinking.type` | enum | `enabled` / `disabled`; default `enabled` | Turn thinking on or off (GLM-4.6 and later); it is on by default. |
| `clearThinking` → `thinking.clear_thinking` | boolean | default `True` | Set to false to keep earlier reasoning in context; it must then be sent back unchanged. |

Model-specific behaviour:

- `reasoningEffort` — **No effect** on models matching `^glm-(4\.\d+|5|5\.1)$`: This GLM model has no reasoning effort setting.
- `reasoningEffort` — **No effect** on models matching `^glm-`: reasoningEffort has no effect while thinking is disabled.
- `thinkingType` — **No effect** on models matching `^glm-5\.3(-flash)?$`: glm-5.3 and glm-5.3-flash always think; disabled is ignored.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Text that makes the model stop writing; Z.ai currently honours only one. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with JSON. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

Model-specific behaviour:

- `responseFormat` — **No effect** on models matching `^glm-5\.3-flashx?$`: glm-5.3-flash and glm-5.3-flashx do not take a response format.

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## MiniMax (`minimax`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2; default `1` | Higher values make answers more varied; values outside 0 to 2 are an error. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double |  | Ignored by MiniMax. |
| `presencePenalty` | double |  | Ignored by MiniMax. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Ignored by MiniMax. |

Model-specific behaviour:

- `frequencyPenalty` — **No effect** on models matching `^MiniMax-`: MiniMax ignores frequencyPenalty.
- `presencePenalty` — **No effect** on models matching `^MiniMax-`: MiniMax ignores presencePenalty.
- `logitBias` — **No effect** on models matching `^MiniMax-`: MiniMax ignores logitBias.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `low` / `medium` / `high` / `xhigh` / `max` | How much MiniMax-M3.1-Flash-Preview thinks. |
| `returnThinking` | boolean | default `True` | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean | default `True` | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `reasoningSplit` → `reasoning_split` | boolean | default `True` | Return the model's reasoning in its own field instead of inside the answer text. |
| `thinkingType` → `thinking.type` | enum | `adaptive` / `disabled` | Leave empty to let the model think; disabled skips thinking on MiniMax-M3. |

Model-specific behaviour:

- `reasoningEffort` — **Rejected** on models matching `^MiniMax-M3\.1-Flash-Preview$`: MiniMax-M3.1-Flash-Preview rejects reasoningEffort none.
- `thinkingType` — **Rejected** on models matching `^MiniMax-M3\.1-Flash-Preview$`: MiniMax-M3.1-Flash-Preview always thinks.
- `thinkingType` — **No effect** on models matching `^MiniMax-M2`: MiniMax M2 models ignore a disabled thinking setting.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `standard` / `priority`; default `standard` | priority gets faster admission at a higher price. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

## OpenRouter (`openrouter`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Model

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `models` → `models` | stringList |  | Models to try in order when the main one fails or is unavailable. |
| `route` → `route` | enum | `fallback` | Routing strategy for the fallback models. |

### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |
| `topK` → `top_k` | int | 0– | Only consider this many of the most likely next words. |
| `minP` → `min_p` | double | 0–1 | Drop words whose probability is below this share of the most likely word's. |
| `repetitionPenalty` → `repetition_penalty` | double | 0–2; default `1` | Values above 1 discourage repetition. |

Model-specific behaviour:

- `topK` — **No effect** on models matching `^openai/`: OpenAI models do not support topK.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` → `reasoning.effort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` | How much the routed model thinks; none turns reasoning off. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `reasoningMaxTokens` → `reasoning.max_tokens` | int | 1024– | Token budget for reasoning on models that take one; use this or reasoningEffort, not both. |
| `reasoningEnabled` → `reasoning.enabled` | boolean |  | Turn reasoning on or off; true means medium effort. |
| `reasoningExclude` → `reasoning.exclude` | boolean | default `False` | The model still reasons (and is billed for it) but the reasoning is left out of the response. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` / `xhigh` / `max` | How long and detailed the answer should be. |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow the model to call several tools in one step. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |
| `plugins` → `plugins` | json |  | OpenRouter plugins such as web search, as a JSON array like [{"id":"web"}]. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |
| `providerDataCollection` → `provider.data_collection` | enum | `allow` / `deny`; default `allow` | Set to deny to skip providers that may store your data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |
| `providerOrder` → `provider.order` | stringList |  | Provider names to try in this order. |
| `providerOnly` → `provider.only` | stringList |  | Route only to these providers. |
| `providerIgnore` → `provider.ignore` | stringList |  | Never route to these providers. |
| `providerAllowFallbacks` → `provider.allow_fallbacks` | boolean | default `True` | Let OpenRouter use other providers when the preferred ones fail. |
| `providerRequireParameters` → `provider.require_parameters` | boolean | default `False` | Use only providers that support every setting in the request, instead of silently ignoring unsupported ones. |
| `providerSort` → `provider.sort` | enum | `price` / `throughput` / `latency` | Prefer the cheapest, fastest or lowest-latency provider. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |
| `appReferer` → header `HTTP-Referer` | string |  | Your app's URL, sent as the HTTP-Referer header so OpenRouter can attribute usage to your app. |
| `appTitle` → header `X-Title` | string |  | Your app's display name on OpenRouter; needs the app URL too. |

## Groq (`groq`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double |  | Not supported by any Groq model yet. |
| `presencePenalty` | double |  | Not supported by any Groq model yet. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Not supported by any Groq model yet. |

Model-specific behaviour:

- `frequencyPenalty` — **No effect** on models matching `^.*`: No Groq model supports frequencyPenalty yet.
- `presencePenalty` — **No effect** on models matching `^.*`: No Groq model supports presencePenalty yet.
- `logitBias` — **No effect** on models matching `^.*`: No Groq model supports logitBias yet.

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `default` / `low` / `medium` / `high` | How much the model thinks; gpt-oss takes low, medium or high. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some providers require during tool use. |
| `reasoningFormat` → `reasoning_format` | enum | `parsed` / `raw` / `hidden` | Return reasoning in its own field (parsed), inside the text (raw) or not at all (hidden). |
| `includeReasoning` → `include_reasoning` | boolean | default `True` | Return the gpt-oss model's reasoning with the answer. |

Model-specific behaviour:

- `reasoningEffort` — **Rejected** on models matching `^openai/gpt-oss-(20b|120b)$`: gpt-oss accepts only low, medium or high.
- `reasoningEffort` — **Rejected** on models matching `^qwen/qwen3\.8-27b$`: qwen3.8-27b accepts only none, default, low, medium or high.
- `reasoningFormat` — **Rejected** on models matching `^openai/gpt-oss`: gpt-oss models take includeReasoning instead of reasoningFormat.
- `includeReasoning` — **Rejected** on models matching `^(?!openai/gpt-oss)`: includeReasoning cannot be combined with reasoningFormat (gpt-oss models take includeReasoning only, so reasoningFormat is the one dropped there).
- `includeReasoning` — **Rejected** on models matching `^(?!openai/gpt-oss)`: includeReasoning works only with gpt-oss models; use reasoningFormat.

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Legacy cap on the answer length; reasoning models need maxCompletionTokens instead. |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Set to json to make the model answer with a JSON object. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean | default `True` | Allow several tool calls in one step; gpt-oss models do not support it. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

Model-specific behaviour:

- `parallelToolCalls` — **No effect** on models matching `^openai/gpt-oss`: gpt-oss models on Groq do not make parallel tool calls.

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `on_demand` / `flex` / `performance`; default `on_demand` | flex has higher limits but fails fast when busy; performance is for enterprise accounts. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Not supported by Groq. |
| `metadata` | json |  | Not supported by Groq. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra top-level request body fields, as a JSON object; these win over every other setting. |

Model-specific behaviour:

- `store` — **No effect** on models matching `^.*`: Groq does not support store.
- `metadata` — **No effect** on models matching `^.*`: Groq does not support metadata.

## Self-hosted (OpenAI-compatible) (`openai-compatible`)

OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, plus the vendor fields below. Model-version rules listed here are specific to this provider.


### Model

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `jsonMode` | boolean | default `False` | Turn on if the server accepts response_format json_object; JSON agents then get API-level JSON on requests without tools. |
| `jsonModeWithTools` | boolean | default `False` | Turn on if the server also accepts response_format together with tool definitions in one request. |
| `vision` | boolean | default `False` | Turn on if the served model accepts images; image attachments are then sent to it whatever its name. |

### Sampling

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `temperature` | double | 0–2 | Higher values make answers more varied, lower values more predictable. |
| `topP` | double | 0–1 | Only consider the most likely words whose probabilities add up to this share; change this or temperature, not both. |
| `frequencyPenalty` | double | -2–2 | Positive values discourage the model from repeating the same words. |
| `presencePenalty` | double | -2–2 | Positive values encourage the model to move on to new topics. |
| `seed` | int |  | Ask for repeatable answers to identical requests; best effort only. |
| `logitBias` | json |  | Make specific tokens more or less likely, as a JSON object of token id to a bias between -100 and 100. |

### Reasoning and thinking

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `reasoningEffort` | enum | `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` | How much the model thinks before answering; more effort is slower and costs more. |
| `returnThinking` | boolean |  | Keep the model's reasoning text from the response instead of discarding it. |
| `sendThinking` | boolean |  | Send earlier reasoning back to the model on the next request, which some servers require during tool use. |

### Output

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `maxTokens` | int | 1– | Upper limit on the tokens the model may produce (max_tokens). |
| `maxCompletionTokens` | int | 1– | Upper limit on the tokens the model may produce, including its hidden reasoning. |
| `stopSequences` | stringList |  | Up to four pieces of text that make the model stop writing when it produces them. |
| `responseFormat` | enum | `text` / `json` | Do not use: it fixes JSON mode on the cached model, so tool-calling and streaming requests get it too. For JSON answers set convertToObject to true on the task (jsonResponseFormat overrides it per task) and turn jsonMode on if the server supports JSON mode. |
| `verbosity` | enum | `low` / `medium` / `high` | How long and detailed the answer should be (GPT-5 and later). |
| `strictJsonSchema` | boolean |  | Make structured JSON answers follow the requested schema exactly. |

### Tools

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `parallelToolCalls` | boolean |  | Allow the model to call several tools in one step, if the server supports it. |
| `strictTools` | boolean |  | Make tool call arguments follow the tool's schema exactly. |

### Caching

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `promptCacheKey` | string |  | Requests that share this key are more likely to reuse cached prompt prefixes. |
| `promptCacheRetention` | enum | `in_memory` / `24h` | How long a cached prompt prefix is kept. |

### Safety

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `user` | string |  | Legacy identifier of the end user; prefer safetyIdentifier and promptCacheKey. |
| `safetyIdentifier` | string |  | A stable, hashed id of the end user that helps OpenAI detect abuse; never send personal data. |

### Connection

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `serviceTier` | enum | `auto` / `default` / `flex` / `priority`; default `auto` | Processing tier: flex is cheaper but slower, priority is faster but costs more. |
| `organizationId` | string |  | The OpenAI organization to bill when the key belongs to several. |
| `projectId` | string |  | The OpenAI project to use when the key belongs to several. |
| `serverName` | string | default `openai-compatible` | Names the server in traces and metrics, e.g. vllm or llama-server; up to 32 characters of a-z, 0-9, dot, dash and underscore. |

### Advanced

| Parameter | Type | Values | Description |
| --- | --- | --- | --- |
| `store` | boolean |  | Keep the request and answer in your OpenAI account for evaluations and distillation. |
| `metadata` | json |  | Up to 16 tags stored with the completion, as a JSON object of string keys and values. |
| `customHeaders` | json |  | Extra HTTP headers sent with every request, as a JSON object of header name to value. |
| `customQueryParams` | json |  | Extra URL query parameters, as a JSON object of name to value. |
| `customParameters` | json |  | Extra fields for the request body as a JSON object, e.g. vLLM's chat_template_kwargs or guided_json; must be a JSON object. |
