/**
 * Types and constants for the LLM editor components.
 * Shared between llm-editor.tsx and its sub-components.
 */

import type {
  PropertyInstruction,
  OutputBuildingInstruction,
  QuickRepliesBuildingInstruction,
} from "../apicalls-editor";
import type { ToolApprovalsConfig } from "@/lib/api/hitl";

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
  /** Per-step token pricing overrides (USD per 1M tokens). Must be ≥ 0. */
  inputPricePer1M?: number;
  outputPricePer1M?: number;
}

/** Judge model for the `judge_model` confidence-evaluation strategy. */
export interface CascadeJudgeModel {
  type?: string;
  parameters?: Record<string, string>;
}

/**
 * Overrides for the `heuristic` confidence-evaluation strategy. Every field is
 * optional; an omitted field falls back to the backend's built-in English
 * default. Scores are clamped to [0, 1] by the backend.
 */
export interface CascadeHeuristic {
  lowConfidencePhrases?: string[];
  refusalPhrases?: string[];
  shortLengthThreshold?: number;
  shortScore?: number;
  refusalScore?: number;
  hedgingScore?: number;
  defaultScore?: number;
}

export interface ModelCascadeConfig {
  enabled?: boolean;
  strategy?: string;
  evaluationStrategy?: string;
  enableInAgentMode?: boolean;
  steps?: CascadeStep[];
  /** Judge model config — used when evaluationStrategy is "judge_model". */
  judgeModel?: CascadeJudgeModel;
  /** Overrides for the "heuristic" evaluation strategy. */
  heuristic?: CascadeHeuristic;
  /** Wall-clock ceiling across the whole cascade (ms). Must be > 0 when set. */
  maxTotalDurationMs?: number;
  /** Dollar ceiling for a single run. Must be ≥ 0 when set. */
  maxCostPerRun?: number;
  /** Cascade-level default token pricing (USD per 1M tokens); steps may override. Must be ≥ 0. */
  inputPricePer1M?: number;
  outputPricePer1M?: number;
  /** Return the highest-scoring step's response even if a later step was finally accepted. */
  returnBestAcrossSteps?: boolean;
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

  // Response Validation & Recovery
  /**
   * Response validation policies. When enabled, the engine validates each LLM
   * turn against per-signal policies and applies the configured remediation.
   */
  responseValidation?: ResponseValidation;
  /**
   * Timeout (seconds) for streaming chat completions. Overrides the engine
   * default (120s). Only applies while streaming is active.
   */
  streamingTimeoutSeconds?: number;

  /**
   * Per-task tool-approval override (tool-level HITL). A FULL REPLACE of the
   * agent-level `hitlConfig.toolApprovals` for this task — no field merge.
   */
  toolApprovals?: ToolApprovalsConfig | null;

  /**
   * Token pricing (USD per 1M tokens) for this task's ORDINARY (non-cascade)
   * model calls (N1). `null`/absent = unpriced, contributes $0 to tracked cost.
   * Distinct from `modelCascade`'s own pricing pair — a cascade run is priced by
   * its steps alone; these apply only when the cascade is off (or a call simply
   * isn't cascaded), since cascade steps may target different models than this
   * task's own. Must be ≥ 0 (validated at agent DEPLOY time, not at LLM-config
   * save time — a negative value can be saved and only rejected later).
   */
  inputPricePer1M?: number | null;
  outputPricePer1M?: number | null;
}

/**
 * Remediation action applied by a response-validation policy. Mirrors the
 * backend `LlmConfiguration.ResponseValidation` action strings (case-insensitive
 * on the engine side).
 * - `ignore`   — do nothing
 * - `warn`     — log a warning, store validation metadata, continue
 * - `fallback` — substitute a static fallback message
 * - `error`    — throw a LifecycleException (fails the turn)
 */
export type ResponseValidationAction = "ignore" | "warn" | "fallback" | "error";

/** Ordered action options for response-validation policy selectors. */
export const RESPONSE_VALIDATION_ACTIONS = [
  "ignore",
  "warn",
  "fallback",
  "error",
] as const satisfies readonly ResponseValidationAction[];

/**
 * Response validation policies for LLM outputs — one policy per anomaly signal.
 * Field names match the backend `LlmConfiguration.Task.responseValidation`
 * nested type exactly. Every field is optional; an omitted field falls back to
 * the backend default (onRefusal defaults to `ignore`, all others to `warn`).
 */
export interface ResponseValidation {
  /** Master switch — validation is only applied when enabled. */
  enabled?: boolean;
  /** Action when the LLM returns an empty or null response. */
  onEmpty?: ResponseValidationAction;
  /** Action when the response was truncated (finishReason=LENGTH). */
  onTruncation?: ResponseValidationAction;
  /** Action when the response was blocked by a content filter. */
  onContentFilter?: ResponseValidationAction;
  /** Action when the LLM refused to respond (heuristic detection). */
  onRefusal?: ResponseValidationAction;
  /** Action when a streaming response timed out. */
  onStreamingTimeout?: ResponseValidationAction;
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

/**
 * Built-in tool names the backend recognises in `builtInToolsWhitelist`
 * (see `AgentOrchestrator.collectAllBuiltInTools`).
 *
 * Switching an agent to whitelist mode replaces the "all built-in tools"
 * default with exactly this list, so anything missing here is a capability the
 * user can no longer enable. Three of the last four are additionally
 * self-gating server-side — `readattachment` only appears when the conversation
 * has files, `conversationRecall` only with a rolling summary, `usermemory`
 * only when the agent has persistent memory — so listing them cannot switch on
 * something the agent isn't configured for. `fetch_page` is not gated: it is a
 * plain tool for paging through an oversized tool response, and was simply
 * missing from this list.
 *
 * Dynamic-agent tools (create_sub_agent, converse_with_agent,
 * find_agents_by_capability, teardown_agent) are deliberately absent: they let
 * an agent spawn other agents and belong to the Dynamic Agents configuration,
 * not a generic tool picker.
 */
export const BUILT_IN_TOOLS = [
  "calculator",
  "datetime",
  "websearch",
  "dataformatter",
  "webscraper",
  "textsummarizer",
  "pdfreader",
  "weather",
  "fetch_page",
  "readattachment",
  "conversationRecall",
  "usermemory",
] as const;
