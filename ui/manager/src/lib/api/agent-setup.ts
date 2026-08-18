import { api } from "../api-client";

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
  /** Base URL of the LLM provider itself (Ollama, Jlama) — not the tool target. */
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
   * `deployError`) and, when the chosen vault key is granted only to specific
   * agents, `vaultGrantWarning` — the new agent cannot be on that list yet, so
   * under grant enforcement its deployment is blocked until the grant is
   * widened.
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
  { id: "anthropic", name: "Anthropic", defaultModel: "claude-sonnet-5", needsKey: true },
  { id: "openai", name: "OpenAI", defaultModel: "gpt-5.4", needsKey: true },
  { id: "gemini", name: "Google Gemini", defaultModel: "gemini-3.5-flash", needsKey: true },
  { id: "gemini-vertex", name: "Google Vertex AI", defaultModel: "gemini-3.5-flash", needsKey: false },
  { id: "mistral", name: "Mistral AI", defaultModel: "mistral-large-latest", needsKey: true },
  { id: "huggingface", name: "HuggingFace", defaultModel: "Qwen/Qwen3.5-7B", needsKey: true },
  { id: "azure-openai", name: "Azure OpenAI", defaultModel: "gpt-5.4", needsKey: true },
  { id: "bedrock", name: "Amazon Bedrock", defaultModel: "anthropic.claude-sonnet-5", needsKey: false },
  { id: "oracle-genai", name: "Oracle GenAI", defaultModel: "cohere.command-r-plus-v2", needsKey: false },
  { id: "ollama", name: "Ollama (Local)", defaultModel: "llama3.3:70b", needsKey: false },
  { id: "jlama", name: "Jlama (Local)", defaultModel: "llama-3.2-1b", needsKey: false },
] as const;

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
