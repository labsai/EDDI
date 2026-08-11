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
    "claude-fable-5",
    "claude-opus-5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-haiku-4-5",
  ],
  openai: [
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
  jlama: [
    "llama-3.2-1b",
    "tinyllama",
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

/** Whether a provider requires a base URL (local providers) or it's just optional */
export function isBaseUrlRequired(providerId: string): boolean {
  return providerId === "ollama" || providerId === "jlama";
}
