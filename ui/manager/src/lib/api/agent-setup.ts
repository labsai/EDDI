import type { ProviderGroup } from "../llm-provider-catalog";
import { api } from "../api-client";
import { parseVersionFromLocation } from "./location-version";
import { getAgentCurrentVersion } from "./agents";

// ---------- Request types ----------

export interface SetupAgentRequest {
  name: string;
  systemPrompt: string;
  provider?: string;
  model?: string;
  apiKey?: string;
  baseUrl?: string;
  introMessage?: string;
  enableBuiltInTools?: boolean;
  builtInToolsWhitelist?: string;
  enableQuickReplies?: boolean;
  enableSentimentAnalysis?: boolean;
  deploy?: boolean;
  environment?: string;
  /**
   * Name of the vault entry the LLM API key lives under, so several agents can
   * share ONE stored credential. Not surfaced as its own form field: the
   * `SecretKeyPicker` on `apiKey` already produces `${vault:<name>}` (and can
   * create a named entry inline), which the backend reuses without re-vaulting.
   * Typed here because the endpoint accepts it and a caller building a request
   * by hand should see it.
   */
  vaultKeyName?: string;
}

export interface CreateApiAgentRequest {
  /**
   * Backend field name is `agentName` — see the `CreateApiAgentRequest` record in
   * `AgentSetupService`, which rejects a blank one with "Agent name is required".
   * This used to be sent as `name`, which the backend silently dropped.
   */
  agentName: string;
  systemPrompt: string;
  openApiSpec: string;
  provider?: string;
  model?: string;
  apiKey?: string;
  /** Target server of the generated tools. */
  apiBaseUrl?: string;
  /** Base URL of the LLM provider's own server (Ollama) — not the tool target. */
  llmBaseUrl?: string;
  apiAuth?: string;
  endpoints?: string;
  enableQuickReplies?: boolean;
  enableSentimentAnalysis?: boolean;
  deploy?: boolean;
  environment?: string;
  /**
   * The HITL approval gate to install on the created agent, on v1 of its
   * document. Without this the created agent's `hitlConfig` is `null` and the
   * tool-approval gate is inert — every generated write tool runs unreviewed.
   * See `AgentSetupService.createApiAgent` (backend PR "provision the HITL gate
   * through setup-api").
   */
  hitlConfig?: import("./hitl").AgentHitlConfig;
  /**
   * Comma-separated MCP server URLs whose tools are added alongside the ones
   * generated from `openApiSpec`, so one agent can hold both.
   */
  mcpServerUrls?: string;
  /**
   * Tool-loop iteration budget for the generated LLM task. Omitted keeps the
   * engine default (10), which suits ordinary agents. Bounded by the backend
   * (`AgentSetupService.MAX_TOOL_ITERATIONS` — out-of-range is a 400 before any
   * resource is created).
   */
  maxToolIterations?: number;
  /**
   * Name of the vault entry the LLM API key lives under, so several agents can
   * share ONE stored credential. Not surfaced as its own form field: the
   * `SecretKeyPicker` on `apiKey` already produces `${vault:<name>}` (and can
   * create a named entry inline), which the backend reuses without re-vaulting.
   * Typed here because the endpoint accepts it and a caller building a request
   * by hand should see it.
   */
  vaultKeyName?: string;
}

// ---------- Response type ----------

export interface SetupResult {
  action: string;
  agentId: string;
  agentName: string;
  provider: string;
  model: string;
  deployed?: boolean;
  deploymentStatus?: string;
  endpointCount?: number;
  groups?: string[];
  quickRepliesEnabled?: boolean;
  sentimentAnalysisEnabled?: boolean;
  /**
   * Created resource locations plus deploy outcome (`deployWarning`,
   * `deployError`) and `vaultWarning` — the chosen vault key does not exist, or
   * it is granted only to other agents (a new agent cannot be on that list yet,
   * so under grant enforcement its deployment is blocked until the grant is
   * widened). Neither fails the setup; both leave an agent that cannot use its
   * credential, which is why the backend reports them rather than only logging.
   */
  resources?: Record<string, unknown>;
  /**
   * The `${vault:...}` reference the created agent's LLM config points at —
   * whether setup vaulted the key just now or reused an entry that already held
   * it. Hand it to the next agent (as `apiKey` or `vaultKeyName`) to put both on
   * the same credential. Absent when the vault is disabled and the key was
   * stored in plain text: the backend never echoes a plaintext secret back.
   */
  apiKeyVaultReference?: string;
}

// ---------- Provider helpers ----------

export const LLM_PROVIDERS = [
  { id: "anthropic", name: "Anthropic", defaultModel: "claude-sonnet-5-5", needsKey: true, group: "frontier" },
  { id: "openai", name: "OpenAI", defaultModel: "gpt-5.4", needsKey: true, group: "frontier" },
  { id: "gemini", name: "Google Gemini", defaultModel: "gemini-3.5-flash", needsKey: true, group: "frontier" },
  { id: "mistral", name: "Mistral AI", defaultModel: "mistral-large-latest", needsKey: true, group: "frontier" },
  // Named OpenAI-compatible providers. Endpoints, regions and suggestions live in
  // `llm-provider-catalog.ts`, which mirrors the backend catalog.
  { id: "xai", name: "xAI Grok", defaultModel: "grok-4.7", needsKey: true, group: "compatible" },
  { id: "deepseek", name: "DeepSeek", defaultModel: "deepseek-flash", needsKey: true, group: "compatible" },
  { id: "moonshot", name: "Moonshot Kimi", defaultModel: "kimi-k3", needsKey: true, group: "compatible" },
  { id: "qwen", name: "Alibaba Qwen", defaultModel: "qwen3.7-plus", needsKey: true, group: "compatible" },
  { id: "zhipu", name: "Z.ai GLM (Zhipu)", defaultModel: "glm-5.3", needsKey: true, group: "compatible" },
  { id: "minimax", name: "MiniMax", defaultModel: "MiniMax-M3", needsKey: true, group: "compatible" },
  { id: "openrouter", name: "OpenRouter", defaultModel: "openrouter/auto", needsKey: true, group: "compatible" },
  { id: "groq", name: "Groq", defaultModel: "openai/gpt-oss-120b", needsKey: true, group: "compatible" },
  { id: "gemini-vertex", name: "Google Vertex AI", defaultModel: "gemini-3.5-flash", needsKey: false, group: "cloud" },
  { id: "azure-openai", name: "Azure OpenAI", defaultModel: "gpt-5.4", needsKey: true, group: "cloud" },
  { id: "bedrock", name: "Amazon Bedrock", defaultModel: "global.anthropic.claude-sonnet-5-5", needsKey: false, group: "cloud" },
  { id: "oracle-genai", name: "Oracle GenAI", defaultModel: "cohere.command-a-03-2025", needsKey: false, group: "cloud" },
  { id: "huggingface", name: "HuggingFace", defaultModel: "Qwen/Qwen3.5-7B", needsKey: true, group: "cloud" },
  { id: "ollama", name: "Ollama (Local)", defaultModel: "llama3.3:70b", needsKey: false, group: "local" },
  // Jlama loads from Hugging Face, so the default has to be a real `owner/name`
  // repo id — it is shown as the model placeholder and seeds the operator
  // activation form. See MODEL_SUGGESTIONS.jlama.
  { id: "jlama", name: "Jlama (In-Process)", defaultModel: "tjake/Llama-3.2-1B-Instruct-JQ4", needsKey: false, group: "local" },
] as const satisfies readonly {
  id: string;
  name: string;
  defaultModel: string;
  needsKey: boolean;
  group: ProviderGroup;
}[];

/** Display order of the provider groups, with the i18n key of each heading. */
export const LLM_PROVIDER_GROUPS: readonly { id: ProviderGroup; labelKey: string; fallback: string }[] = [
  { id: "frontier", labelKey: "llmProviders.group.frontier", fallback: "Model labs" },
  { id: "compatible", labelKey: "llmProviders.group.compatible", fallback: "OpenAI-compatible providers" },
  { id: "cloud", labelKey: "llmProviders.group.cloud", fallback: "Cloud platforms" },
  { id: "local", labelKey: "llmProviders.group.local", fallback: "Local / self-hosted" },
];

export type ProviderId = (typeof LLM_PROVIDERS)[number]["id"];

export function getProviderConfig(id: string) {
  return LLM_PROVIDERS.find((p) => p.id === id);
}

// ---------- API functions ----------

export function setupAgent(request: SetupAgentRequest): Promise<SetupResult> {
  return api.post<SetupResult>("/administration/agents/setup", request);
}

export function createApiAgent(
  request: CreateApiAgentRequest,
): Promise<SetupResult> {
  return api.post<SetupResult>("/administration/agents/setup-api", request);
}

/**
 * The version a setup created, to deploy it by. The setup answer names the
 * agent's location (`…/agents/{id}?version=N`); when it does not, the store's
 * current version stands in, and failing that version 1 — which is what a new
 * agent is.
 */
export async function resolveSetupVersion(result: SetupResult): Promise<number> {
  const location = (result.resources as { agentLocation?: unknown } | undefined)?.agentLocation;
  const parsed = parseVersionFromLocation(typeof location === "string" ? location : null);
  if (parsed != null) return parsed;
  return getAgentCurrentVersion(result.agentId).catch(() => 1);
}
