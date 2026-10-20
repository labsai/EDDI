/**
 * Shared LLM model catalog.
 *
 * Extracted from `agent-wizard.tsx` so the Platform Operator activation flow and
 * the agent wizard offer the same suggestions from one source.
 */

import { COMPATIBLE_PROVIDERS, SELF_HOSTED_PROVIDER_ID } from "@/lib/llm-provider-catalog";

/** Popular model suggestions per provider — users can still type any custom model */
export const MODEL_SUGGESTIONS: Record<string, string[]> = {
  // Named OpenAI-compatible providers, from the mirror of the backend catalog.
  ...Object.fromEntries(COMPATIBLE_PROVIDERS.map((p) => [p.id, [...p.suggestions]])),
  // Catalog checked against each provider's model and deprecation pages on
  // 2026-10-17. Ordered newest/flagship first; the provider default is badged in
  // the picker wherever it sits.
  anthropic: [
    // Anthropic API uses dashes in version numbers (e.g. sonnet-4-6 = v4.6).
    // claude-sonnet-5-5 leads because it is the app-wide default model.
    "claude-sonnet-5-5",
    "claude-opus-5-5",
    "claude-haiku-5-5",
    "claude-fable-5-1",
    "claude-opus-5",
    "claude-sonnet-5",
    "claude-fable-5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-haiku-4-5",
  ],
  openai: [
    // gpt-6.1-sol supersedes gpt-6-sol (still served) since 2026-09-29.
    // o3-mini (shut down 2026-10-23), gpt-5.4-nano (deprecated) and the gpt-5
    // family (snapshots retire 2026-12-11) were dropped; gpt-5.4-thinking was
    // never an API model id.
    "gpt-6-astra",
    "gpt-6.1-sol",
    "gpt-6-sol",
    "gpt-6-luna",
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.6-luna",
    "gpt-5.5",
    "gpt-5.5-pro",
    "gpt-5.4",
    "gpt-5.4-pro",
    "gpt-5.4-mini",
  ],
  gemini: [
    // gemini-3.7-flash and gemini-3.5-flash were deprecated on 2026-10-08 (their
    // requests now route to 3.8 and 3.6). There is no Gemini Pro newer than the
    // 3.1 preview, and the API documents no bare "gemini-3.1-pro".
    "gemini-4-argon",
    "gemini-3.8-flash",
    "gemini-3.1-pro-preview",
    "gemini-3.1-pro-preview-customtools",
    "gemini-3.6-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
  ],
  "gemini-vertex": [
    // Vertex retires models on its own schedule: gemini-2.5-* retire there on
    // 2026-10-20 and gemini-3.6-flash on 2026-11-19, while gemini-3.5-flash is
    // not deprecated on Vertex at all.
    "gemini-4-argon",
    "gemini-3.8-flash",
    "gemini-3.1-pro-preview",
    "gemini-3.1-pro-preview-customtools",
    "gemini-3.7-flash",
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.1-flash-lite",
    // Vertex serves current-generation Claude under the bare first-party id.
    "claude-sonnet-5-5",
    "claude-opus-5-5",
    "claude-haiku-5-5",
    "claude-fable-5-1",
    "claude-opus-5",
    "claude-sonnet-5",
    "claude-opus-4-8",
    "claude-sonnet-4-6",
    "claude-haiku-4-5",
    // Model Garden
    "gemma-4-26b-a4b-it-maas",
    "google/gemma3@gemma-3-12b-it",
  ],
  ollama: [
    // Ollama library tags, smallest first. llama3.3 was only released as 70B.
    "qwen3.5:4b",
    "gemma4:e4b",
    "qwen3.5:9b",
    "ministral-3:8b",
    "gpt-oss:20b",
    "gemma4:26b",
    "qwen3.6:35b",
    "llama3.3:70b",
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
    // Repository ids served by HF Inference Providers. The older entries here
    // (DeepSeek-V4, gemma-4-assistant, Qwen3.5-7B) named repositories that do
    // not exist.
    "Qwen/Qwen3.8-27B",
    "openai/gpt-oss-120b",
    "deepseek-ai/DeepSeek-V4-Pro",
    "zai-org/GLM-5.3",
    "moonshotai/Kimi-K3",
    "google/gemma-4-26B-A4B-it",
    "meta-llama/Llama-3.1-8B-Instruct",
  ],
  mistral: [
    // The dated ids are the ones Mistral's model pages document; the -latest
    // aliases still resolve. Devstral was retired and Magistral deprecated by
    // 2026-07-31: reasoning now comes from reasoning_effort on Small and Medium.
    // Mistral Large 4 has been in public preview since 2026-10-06.
    "mistral-large-4",
    "mistral-large-latest",
    "mistral-large-2512",
    "mistral-medium-3-5",
    "mistral-medium-latest",
    "mistral-small-latest",
    "mistral-small-2603",
    "ministral-14b-2512",
    "ministral-14b-latest",
    "ministral-8b-latest",
    "ministral-3b-latest",
    "codestral-2508",
    "codestral-latest",
  ],
  // Azure uses your own deployment names — these are the standard model names
  // on Azure AI Foundry. gpt-4o, gpt-4.1 and gpt-4o-mini are deprecated there
  // (retiring by 2027-04-14), and "gpt-4-turbo" was never a Foundry model name.
  "azure-openai": [
    "gpt-6.1-sol",
    "gpt-6-astra",
    "gpt-6-sol",
    "gpt-6-luna",
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.6-luna",
    "gpt-5.5",
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-5.1",
  ],
  bedrock: [
    // bedrock-runtime serves current models through cross-region inference
    // profiles (`global.` prefix); most bare ids have no in-Region endpoint.
    // Llama 3.x/4 and MiniMax M2 went Legacy on 2026-09-30, Llama 3.1 405B is
    // past end of life.
    // Anthropic
    "global.anthropic.claude-sonnet-5-5",
    "global.anthropic.claude-opus-5-5",
    "global.anthropic.claude-haiku-5-5",
    "global.anthropic.claude-fable-5-1",
    "global.anthropic.claude-opus-5",
    "global.anthropic.claude-sonnet-5",
    "global.anthropic.claude-opus-4-8",
    "global.anthropic.claude-sonnet-4-6",
    "global.anthropic.claude-haiku-4-5-20251001-v1:0",
    // OpenAI
    "global.openai.gpt-6.1-sol",
    "global.openai.gpt-6-astra",
    "global.openai.gpt-6-luna",
    "openai.gpt-oss-120b-1:0",
    "openai.gpt-oss-20b-1:0",
    // Amazon
    "global.amazon.nova-2-lite-v1:0",
    "amazon.nova-pro-v1:0",
    // Other
    "global.moonshotai.kimi-k3",
    "global.xai.grok-4.7",
    "deepseek.v3.2",
    "qwen.qwen3-235b-a22b-2507-v1:0",
    "minimax.minimax-m2.5",
  ],
  "oracle-genai": [
    // OCI ids use a dot after the vendor (`openai.gpt-oss-120b`, not a slash).
    // Grok models are hosted by xAI, so those calls leave OCI.
    // xAI
    "xai.grok-4.7",
    "xai.grok-4.6",
    "xai.grok-4.3",
    "xai.grok-4.20-reasoning",
    // Cohere
    // Command R and R+ are retired on OCI (the last on 2026-07-30); Command A
    // replaced them. OCI has never had "cohere.command-latest".
    "cohere.command-a-03-2025",
    "cohere.command-a-reasoning",
    "cohere.command-a-vision",
    // Google
    "google.gemini-2.5-pro",
    "google.gemini-2.5-flash",
    // Meta Llama — OCI's API names, not the Hugging Face repository names.
    "meta.llama-4-maverick-17b-128e-instruct-fp8",
    "meta.llama-4-scout-17b-16e-instruct",
    "meta.llama-3.3-70b-instruct",
    // OpenAI
    "openai.gpt-oss-120b",
    "openai.gpt-oss-20b",
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
  // openai-compatible: a self-hosted server has no default URL at all.
  return providerId === "ollama" || providerId === SELF_HOSTED_PROVIDER_ID;
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
 * What the team builder may offer: what setup can provision, minus providers
 * that need a base URL the member form has no field for. Ollama stays — the
 * backend falls back to `eddi.ollama.default-base-url` — but a self-hosted
 * OpenAI-compatible endpoint has no default to fall back on.
 */
export function isProvisionableByTeamSetup(providerId: string): boolean {
  return isProvisionableBySetup(providerId) && providerId !== SELF_HOSTED_PROVIDER_ID;
}

/**
 * For provider pickers that have no base URL or model-parameter field of their
 * own (the rolling-summary provider, a group's summarizer, the cascade judge): a
 * self-hosted OpenAI-compatible endpoint cannot work there, because it has no
 * default URL to fall back on. It is still offered when one of `keep` names it —
 * the parent task is itself `openai-compatible` (the summarizer then inherits its
 * baseUrl and key), or the stored value already is, so opening and saving a
 * config never rewrites it.
 */
export function offeredWithoutBaseUrl(providerId: string, ...keep: (string | undefined)[]): boolean {
  return providerId !== SELF_HOSTED_PROVIDER_ID || keep.includes(SELF_HOSTED_PROVIDER_ID);
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

/**
 * Providers whose API key is optional: a self-hosted OpenAI-compatible server
 * checks one only when it was started with one (vLLM `--api-key`, a LiteLLM
 * virtual key). Left blank, the backend sends no Authorization header at all.
 */
export function acceptsOptionalApiKey(providerId: string): boolean {
  return providerId === SELF_HOSTED_PROVIDER_ID;
}

/** One block of the model list: everything, the matches for a query, or the rest. */
export interface ModelSuggestionSection {
  kind: "all" | "matching" | "others";
  models: string[];
}

/** Lower-cased with separators dropped, so `sonnet 5.5` finds `claude-sonnet-5-5`. */
function normalizeModelText(text: string): string {
  return text.toLowerCase().replace(/[\s\-_.:/@]+/g, "");
}

/**
 * Splits suggestions into the ones matching `query` and the rest, both in
 * catalog order (newest first). Every whitespace-separated term of the query
 * must appear in the id, separators ignored.
 *
 * Nothing is ever dropped: a model field pre-filled with the provider default
 * must still show the whole catalog, which is what a native `<datalist>` failed
 * to do. With a blank query there is a single `all` section; with no match
 * there is a single `others` section, so the caller can say so above it.
 */
export function groupModelSuggestions(
  suggestions: readonly string[],
  query: string,
): ModelSuggestionSection[] {
  const unique = [...new Set(suggestions)];
  const terms = query.trim().split(/\s+/).map(normalizeModelText).filter(Boolean);
  if (terms.length === 0) return [{ kind: "all", models: unique }];

  const matching: string[] = [];
  const others: string[] = [];
  for (const model of unique) {
    const haystack = normalizeModelText(model);
    (terms.every((term) => haystack.includes(term)) ? matching : others).push(model);
  }
  const sections: ModelSuggestionSection[] = [];
  if (matching.length > 0) sections.push({ kind: "matching", models: matching });
  if (others.length > 0) sections.push({ kind: "others", models: others });
  return sections;
}
