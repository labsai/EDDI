/**
 * Types and constants for the LLM editor components.
 * Shared between llm-editor.tsx and its sub-components.
 */

import type {
  PropertyInstruction,
  OutputBuildingInstruction,
  QuickRepliesBuildingInstruction,
} from "../apicalls-editor";

// ─── Types matching LlmConfiguration backend model ───────────────────────────

export interface A2AAgentConfig {
  url?: string;
  name?: string;
  apiKey?: string;
  timeoutMs?: number;
  skillsFilter?: string[];
}

export interface CascadeStep {
  type?: string;
  parameters?: Record<string, string>;
  confidenceThreshold?: number | null;
  timeoutMs?: number;
}

export interface ModelCascadeConfig {
  enabled?: boolean;
  strategy?: string;
  evaluationStrategy?: string;
  enableInAgentMode?: boolean;
  steps?: CascadeStep[];
}

/** Pre-request instructions — same model as HttpCalls PreRequest on the backend */
export interface LlmPreRequest {
  propertyInstructions?: PropertyInstruction[];
}

/** Post-response instructions — same model as HttpCalls PostResponse on the backend */
export interface LlmPostResponse {
  propertyInstructions?: PropertyInstruction[];
  outputBuildInstructions?: OutputBuildingInstruction[];
  qrBuildInstructions?: QuickRepliesBuildingInstruction[];
}

export interface LlmTask {
  actions?: string[];
  id?: string;
  type?: string;
  description?: string;
  parameters?: Record<string, string>;
  responseObjectName?: string;
  responseMetadataObjectName?: string;
  preRequest?: LlmPreRequest;
  postResponse?: LlmPostResponse;
  tools?: string[];
  a2aAgents?: A2AAgentConfig[];
  enableBuiltInTools?: boolean;
  enableHttpCallTools?: boolean;
  enableMcpCallTools?: boolean;
  builtInToolsWhitelist?: string[];
  conversationHistoryLimit?: number;
  // Token-aware context window (Strategy 1)
  maxContextTokens?: number;
  anchorFirstSteps?: number;
  /** @deprecated Use knowledgeBases, enableWorkflowRag, or httpCallRag instead */
  retrievalAugmentor?: {
    httpCall?: string;
    embeddingModel?: string;
    embeddingStore?: string;
    maxResults?: number;
    minScore?: number;
  };
  // Phase 8c RAG fields
  knowledgeBases?: KnowledgeBaseReference[];
  enableWorkflowRag?: boolean;
  ragDefaults?: {
    maxResults?: number;
    minScore?: number;
    injectionStrategy?: string;
  };
  httpCallRag?: string;
  retry?: {
    maxAttempts?: number;
    backoffDelayMs?: number;
    backoffMultiplier?: number;
    maxBackoffDelayMs?: number;
  };
  maxBudgetPerConversation?: number;
  enableCostTracking?: boolean;
  enableToolCaching?: boolean;
  enableRateLimiting?: boolean;
  defaultRateLimit?: number;
  toolRateLimits?: Record<string, number>;
  enableParallelExecution?: boolean;
  parallelExecutionTimeoutMs?: number;
  maxToolIterations?: number;
  modelCascade?: ModelCascadeConfig;

  // Conversation Summary (Rolling Summary Strategy)
  conversationSummary?: ConversationSummaryConfig;

  // Tool Response Truncation
  toolResponseLimits?: ToolResponseLimitsConfig;

  // Behavioral Counterweight & Identity Masking (Wave 1)
  counterweight?: CounterweightConfig;
  identityMasking?: IdentityMaskingConfig;
}

export interface ConversationSummaryConfig {
  enabled?: boolean;
  llmProvider?: string;
  llmModel?: string;
  maxSummaryTokens?: number;
  excludePropertiesFromSummary?: boolean;
  recentWindowSteps?: number;
  maxRecallTurns?: number;
  summarizationPrompt?: string;
}

export interface ToolResponseLimitsConfig {
  defaultMaxChars?: number;
  perToolLimits?: Record<string, number>;
  /** Truncation strategy: "truncate" (default), "paginate", or "summarize" */
  truncationStrategy?: string;
  /** Model for summarize strategy (falls back to truncate when absent) */
  summarizerModel?: string;
}

/**
 * Behavioral counterweight configuration. Controls engine-level safety
 * injection into LLM system prompts.
 *
 * Levels: "normal" (no injection), "cautious", "strict"
 */
export interface CounterweightConfig {
  enabled?: boolean;
  level?: string;     // "normal" | "cautious" | "strict"
  placement?: string; // "prefix" | "suffix"
  customInstructions?: string[];
}

/**
 * Identity masking rules. Prepended to the system prompt to prevent the agent
 * from revealing its nature, model names, or internal infrastructure.
 */
export interface IdentityMaskingConfig {
  enabled?: boolean;
  rules?: string[];
}

/** @deprecated Use LlmTask instead */
export type LangchainTask = LlmTask;

export interface KnowledgeBaseReference {
  name?: string;
  maxResults?: number;
  minScore?: number;
  injectionStrategy?: string;
  contextTemplate?: string;
}

export interface LlmConfig {
  tasks: LlmTask[];
}

/** @deprecated Use LlmConfig instead */
export type LangchainConfig = LlmConfig;

// ─── Constants ───────────────────────────────────────────────────────────────

/** Parameter keys that have dedicated UI controls and should not appear in the generic key-value grid */
export const HIDDEN_PARAM_KEYS = new Set(["systemMessage"]);

export const MODEL_TYPES = [
  "openai",
  "anthropic",
  "gemini",
  "gemini-vertex",
  "ollama",
  "huggingface",
  "jlama",
  "mistral",
  "azure-openai",
  "bedrock",
  "oracle-genai",
] as const;

export const BUILT_IN_TOOLS = [
  "calculator",
  "datetime",
  "websearch",
  "dataformatter",
  "webscraper",
  "textsummarizer",
  "pdfreader",
  "weather",
] as const;
