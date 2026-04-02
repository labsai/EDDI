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
}

export interface CreateApiAgentRequest {
  name: string;
  systemPrompt: string;
  openApiSpec: string;
  provider?: string;
  model?: string;
  apiKey?: string;
  apiBaseUrl?: string;
  apiAuth?: string;
  endpoints?: string;
  enableQuickReplies?: boolean;
  enableSentimentAnalysis?: boolean;
  deploy?: boolean;
  environment?: string;
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
  resources?: Record<string, unknown>;
}

// ---------- Provider helpers ----------

export const LLM_PROVIDERS = [
  { id: "anthropic", name: "Anthropic", defaultModel: "claude-sonnet-4-6", needsKey: true },
  { id: "openai", name: "OpenAI", defaultModel: "gpt-5.4", needsKey: true },
  { id: "gemini", name: "Google Gemini", defaultModel: "gemini-2.5-flash", needsKey: true },
  { id: "mistral", name: "Mistral AI", defaultModel: "mistral-large-latest", needsKey: true },
  { id: "azure-openai", name: "Azure OpenAI", defaultModel: "gpt-5.4", needsKey: true },
  { id: "bedrock", name: "Amazon Bedrock", defaultModel: "anthropic.claude-sonnet-4-6-v1", needsKey: false },
  { id: "ollama", name: "Ollama (Local)", defaultModel: "llama3.2:3b", needsKey: false },
  { id: "jlama", name: "Jlama (Local)", defaultModel: "tinyllama", needsKey: false },
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
