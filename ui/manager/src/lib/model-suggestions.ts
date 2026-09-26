/**
 * Shared LLM model catalog.
 *
 * Extracted from `agent-wizard.tsx` so the Platform Operator activation flow and
 * the agent wizard offer the same suggestions from one source.
 */

/** Popular model suggestions per provider — users can still type any custom model */
export const MODEL_SUGGESTIONS: Record<string, string[]> = {
  anthropic: [
    // Anthropic API uses dashes in version numbers (e.g. sonnet-4-6 = v4.6).
    // claude-sonnet-5 leads because it is the app-wide default model — a
    // datalist's first entry is what an admin sees before typing, so it should
    // match the placeholder they were already shown.
    "claude-sonnet-5",
    "claude-fable-5-1",
    "claude-fable-5",
    "claude-opus-5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-haiku-4-5",
  ],
  openai: [
    "gpt-6-astra",
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.6-luna",
    "gpt-5.5",
    "gpt-5.5-pro",
    "gpt-5.4",
    "gpt-5.4-pro",
    "gpt-5.4-mini",
    "gpt-5.4-nano",
    "gpt-5.4-thinking",
    "gpt-5",
    "gpt-5-mini",
    "gpt-5-nano",
    "o3-mini",
  ],
  gemini: [
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-pro",
    "gemini-3.1-pro-preview",
    "gemini-3.1-pro-preview-customtools",
    "gemini-3.1-flash-lite",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
  ],
  "gemini-vertex": [
    // Gemini models
    "gemini-3.8-flash",
    "gemini-3.7-flash",
    "gemini-3.6-flash",
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-pro",
    "gemini-3.1-pro-preview",
    "gemini-3.1-flash-lite",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    // Model Garden / Third-Party
    "google/gemma3@gemma-3-12b-it",
    "google/gemma2@gemma-2-2b-it",
    // Vertex serves current-generation Claude under the bare first-party id.
    "claude-sonnet-5",
    "claude-opus-5",
    "claude-opus-4-8",
    "claude-sonnet-4-6",
    "claude-haiku-4-5@20251001",
  ],
  ollama: [
    // llama3.3 was only released as 70B — no 8B variant exists on Ollama Hub
    "llama3.3:70b",
    "qwen3:8b",
    "gemma3:4b",
    "phi4:mini",
    "deepseek-r1:8b",
  ],
  // Jlama resolves a model through its own registry, which downloads it from
  // Hugging Face — so a model name here is a HF *repository id* in `owner/name`
  // form, not a friendly label. A bare name (`tinyllama`, `llama-3.2-1b`) has no
  // owner to resolve and fails on the agent's first turn, long after the wizard
  // reported success. Only ids this repository already treats as real are listed:
  // any `owner/name` repo Jlama can load can still be typed by hand.
  jlama: [
    "tjake/Llama-3.2-1B-Instruct-JQ4",
    "tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4",
  ],
  huggingface: [
    "deepseek-ai/DeepSeek-V4",
    "google/gemma-4-assistant",
    "THUDM/GLM-5.1",
    "Qwen/Qwen3.5-7B",
    "meta-llama/Llama-3.2-1B",
  ],
  mistral: [
    "mistral-large-latest",
    "mistral-medium-latest",
    "mistral-small-latest",
    "mistral-small-4",
    "ministral-14b-latest",
    "ministral-8b-latest",
    "ministral-3b-latest",
    "devstral-latest",
    "devstral-small-latest",
    "codestral-latest",
    "magistral-medium-latest",
    "magistral-small-latest",
  ],
  // Azure uses your own deployment names — these are standard Microsoft-managed
  // deployment identifiers for Azure OpenAI Service
  "azure-openai": [
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-5.1",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4-turbo",
  ],
  bedrock: [
    // AWS Bedrock model IDs follow the pattern: provider.model-name-v1:0
    // Anthropic — current-generation ids carry the `anthropic.` prefix with no
    // version suffix.
    "anthropic.claude-sonnet-5",
    "anthropic.claude-opus-5",
    "anthropic.claude-opus-4-8",
    "anthropic.claude-sonnet-4-6",
    "anthropic.claude-haiku-4-5-20251001-v1:0",
    "anthropic.claude-sonnet-4-6-v1:0",
    // Meta Llama
    "meta.llama4-maverick-17b-instruct-v1:0",
    "meta.llama4-scout-17b-instruct-v1:0",
    "meta.llama3-3-70b-instruct-v1:0",
    "meta.llama3-1-405b-instruct-v1:0",
    // Amazon
    "amazon.nova-pro-v1:0",
    "amazon.nova-lite-v1:0",
    // Other
    "minimax.minimax-m2",
  ],
  "oracle-genai": [
    // Cohere
    "cohere.command-latest",
    "cohere.command-plus-latest",
    "cohere.command-r-plus-v2",
    "cohere.command-r-plus",
    // Meta Llama
    "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
    "meta-llama/Llama-4-Scout-17B-16E-Instruct",
    "meta.llama-3.3-70b-instruct",
    "meta.llama-3.1-70b-instruct",
    // OpenAI
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b",
  ],
};

/**
 * Providers that run the model inside the EDDI JVM rather than talking to a
 * model server, so there is no endpoint to address and a base URL is not merely
 * optional — it is meaningless.
 *
 * Jlama is the only one. `JlamaLanguageModelBuilder.recognisedParameters()` does
 * not include `baseUrl`, and `AgentSetupService` drops it before the builder is
 * even reached, so anything entered vanishes without a trace the user can see.
 */
const IN_PROCESS_PROVIDERS = new Set(["jlama"]);

/**
 * Whether a base URL field should be offered at all.
 *
 * False only for in-process providers. Everything else can legitimately be
 * pointed at a proxy or a private deployment, even when it does not need to be.
 */
export function supportsBaseUrl(providerId: string): boolean {
  return !IN_PROCESS_PROVIDERS.has(providerId);
}

/**
 * Whether a provider requires a base URL — true for a local provider that talks
 * to a model *server* the deployment has to name.
 *
 * Jlama used to be on this list and must not come back: it has no server, so
 * requiring a URL blocked operator activation behind a field whose value was
 * then thrown away.
 */
export function isBaseUrlRequired(providerId: string): boolean {
  return providerId === "ollama";
}

/**
 * Providers the setup endpoints (`/administration/agents/setup` and
 * `setup-api`) cannot turn into a working agent, and so must not offer.
 *
 * `gemini-vertex` needs a GCP `projectId` and `location` (langchain4j refuses to
 * build the model without either), and neither setup request has a field for
 * them. Offered anyway, it produced an agent that deployed and then failed on its
 * first message. A Vertex agent is created with another provider and switched to
 * `gemini-vertex` in the LLM editor, where both parameters can be set.
 */
const NOT_PROVISIONABLE_BY_SETUP = new Set(["gemini-vertex"]);

/** Whether the setup endpoints can provision a working agent on this provider. */
export function isProvisionableBySetup(providerId: string): boolean {
  return !NOT_PROVISIONABLE_BY_SETUP.has(providerId);
}

/**
 * `providerId` when the setup flows offer it, otherwise `fallback`. A stored
 * value such as an operator configured on `gemini-vertex` before it was hidden
 * would otherwise render a provider select with no matching option, showing
 * one provider while the form holds another.
 */
export function provisionableProviderOr(providerId: string, fallback: string): string {
  return isProvisionableBySetup(providerId) ? providerId : fallback;
}

/**
 * Providers that take an OPTIONAL credential in the key slot. Jlama downloads
 * its weights from Hugging Face, and a gated or private repository needs a
 * token; `AgentSetupService` writes the setup request's `apiKey` to the Jlama
 * builder's `authToken`. The provider needs no key otherwise, so the field is
 * offered but never required.
 */
const OPTIONAL_TOKEN_PROVIDERS = new Set(["jlama"]);

/** Whether a provider accepts an optional token in place of the API key. */
export function acceptsOptionalToken(providerId: string): boolean {
  return OPTIONAL_TOKEN_PROVIDERS.has(providerId);
}
