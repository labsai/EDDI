import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  Brain,
  Bot,
  Zap,
  Handshake,
  AlertTriangle,
  Layers,
  ArrowDown,
  ArrowUp,
  RotateCcw,
  Database,
  Gauge,
  DollarSign,
  Cpu,
  FileCode,
} from "lucide-react";
import { ContentEditor } from "./content-editor";

// ─── Types matching LangChainConfiguration backend model ─────────────────────

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

export interface LangchainTask {
  actions?: string[];
  id?: string;
  type?: string;
  description?: string;
  parameters?: Record<string, string>;
  responseObjectName?: string;
  responseMetadataObjectName?: string;
  preRequest?: unknown;
  postResponse?: unknown;
  tools?: string[];
  a2aAgents?: A2AAgentConfig[];
  enableBuiltInTools?: boolean;
  enableHttpCallTools?: boolean;
  enableMcpCallTools?: boolean;
  builtInToolsWhitelist?: string[];
  conversationHistoryLimit?: number;
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
}

export interface KnowledgeBaseReference {
  name?: string;
  maxResults?: number;
  minScore?: number;
  injectionStrategy?: string;
  contextTemplate?: string;
}

export interface LangchainConfig {
  tasks: LangchainTask[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

/** Parameter keys that have dedicated UI controls and should not appear in the generic key-value grid */
const HIDDEN_PARAM_KEYS = new Set(["systemMessage"]);

const MODEL_TYPES = [
  "openai",
  "anthropic",
  "gemini",
  "gemini-vertex",
  "ollama",
  "huggingface",
  "jlama",
] as const;

const BUILT_IN_TOOLS = [
  "calculator",
  "datetime",
  "websearch",
  "dataformatter",
  "webscraper",
  "textsummarizer",
  "pdfreader",
  "weather",
] as const;

// ─── Sub-components ──────────────────────────────────────────────────────────

function ActionTags({
  actions,
  onChange,
  readOnly,
}: {
  actions: string[];
  onChange: (a: string[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");

  const addAction = () => {
    const trimmed = input.trim();
    if (trimmed && !actions.includes(trimmed)) {
      onChange([...actions, trimmed]);
      setInput("");
    }
  };

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap gap-1.5">
        {actions.map((a, i) => (
          <span
            key={i}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
          >
            {a}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(actions.filter((_, j) => j !== i))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                aria-label={`Remove ${a}`}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {actions.length === 0 && (
          <span className="text-xs text-muted-foreground italic">
            {t("langchainEditor.noActions", "No actions")}
          </span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1.5">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addAction();
              }
            }}
            placeholder={t(
              "langchainEditor.actionPlaceholder",
              "e.g. help, chat"
            )}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={addAction}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
          >
            <Plus className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  );
}

function Section({
  label,
  defaultOpen = true,
  icon: Icon,
  accent,
  children,
}: {
  label: string;
  defaultOpen?: boolean;
  icon?: React.ComponentType<{ className?: string }>;
  accent?: string;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? (
          <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronRight className="h-3 w-3" />
        )}
        {Icon && <Icon className={`h-3.5 w-3.5 ${accent ?? ''}`} />}
        {label}
      </button>
      {open && <div className="space-y-2">{children}</div>}
    </div>
  );
}

function SkillsFilterInput({
  skills,
  onChange,
  readOnly,
}: {
  skills: string[];
  onChange: (s: string[]) => void;
  readOnly?: boolean;
}) {
  const [input, setInput] = useState("");

  const addSkill = () => {
    const trimmed = input.trim();
    if (trimmed && !skills.includes(trimmed)) {
      onChange([...skills, trimmed]);
      setInput("");
    }
  };

  return (
    <div className="space-y-1">
      <div className="flex flex-wrap gap-1">
        {skills.map((s, i) => (
          <span
            key={i}
            className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
          >
            {s}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(skills.filter((_, j) => j !== i))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
              >
                <X className="h-2.5 w-2.5" />
              </button>
            )}
          </span>
        ))}
        {skills.length === 0 && (
          <span className="text-[10px] text-muted-foreground italic">All skills</span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addSkill();
              }
            }}
            placeholder="e.g. order-tracking"
            className="h-6 flex-1 rounded border border-input bg-background px-1.5 text-[10px] text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={addSkill}
            className="inline-flex h-6 items-center rounded border border-input px-1.5 text-[10px] text-muted-foreground hover:text-foreground transition-colors"
          >
            <Plus className="h-2.5 w-2.5" />
          </button>
        </div>
      )}
    </div>
  );
}

// ─── TaskEditor ──────────────────────────────────────────────────────────────

function TaskEditor({
  task,
  onChange,
  onRemove,
  readOnly,
}: {
  task: LangchainTask;
  onChange: (t: LangchainTask) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  const isAgent =
    (task.tools && task.tools.length > 0) ||
    task.enableBuiltInTools ||
    (task.a2aAgents && task.a2aAgents.length > 0);

  const updateParam = (key: string, value: string) => {
    onChange({
      ...task,
      parameters: { ...task.parameters, [key]: value },
    });
  };

  return (
    <div
      className="rounded-xl border border-border bg-card shadow-sm"
      data-testid="langchain-task-editor"
    >
      {/* Task header */}
      <div className="flex items-center gap-2 p-3">
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors"
        >
          {expanded ? (
            <ChevronDown className="h-4 w-4" />
          ) : (
            <ChevronRight className="h-4 w-4" />
          )}
        </button>
        {/* Mode badge */}
        <span
          className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-bold tracking-wider ${
            isAgent
              ? "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400"
              : "bg-sky-100 text-sky-700 dark:bg-sky-900/30 dark:text-sky-400"
          }`}
          data-testid="mode-badge"
        >
          {isAgent ? (
            <>
              <Bot className="h-3 w-3" /> Agent
            </>
          ) : (
            <>
              <Brain className="h-3 w-3" /> Chat
            </>
          )}
        </span>
        {/* Model type dropdown */}
        <select
          value={task.type ?? "openai"}
          onChange={(e) => onChange({ ...task, type: e.target.value })}
          disabled={readOnly}
          className="h-8 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid="model-type-select"
        >
          {MODEL_TYPES.map((mt) => (
            <option key={mt} value={mt}>
              {mt}
            </option>
          ))}
        </select>
        <input
          type="text"
          value={task.id ?? ""}
          onChange={(e) => onChange({ ...task, id: e.target.value })}
          readOnly={readOnly}
          placeholder={t("langchainEditor.taskId", "Task ID")}
          className="h-8 w-32 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        <span className="flex-1" />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("langchainEditor.removeTask", "Remove Task")}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>

      {expanded && (
        <div className="space-y-4 border-t px-4 py-3">
          {/* Description */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("langchainEditor.description", "Description")}
            </label>
            <input
              type="text"
              value={task.description ?? ""}
              onChange={(e) =>
                onChange({ ...task, description: e.target.value })
              }
              readOnly={readOnly}
              placeholder={t(
                "langchainEditor.descriptionPlaceholder",
                "What this task does"
              )}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>

          {/* Actions */}
          <Section label={t("langchainEditor.triggerActions", "Trigger Actions")}>
            <ActionTags
              actions={task.actions ?? []}
              onChange={(a) => onChange({ ...task, actions: a })}
              readOnly={readOnly}
            />
          </Section>

          {/* System Prompt */}
          <Section
            label={t("langchainEditor.systemPrompt", "System Prompt")}
          >
            <ContentEditor
              value={task.parameters?.systemMessage ?? ""}
              onChange={(v) => updateParam("systemMessage", v)}
              readOnly={readOnly}
              language="prompt"
              label={t("langchainEditor.systemPrompt", "System Prompt")}
              placeholder={t(
                "langchainEditor.systemPromptPlaceholder",
                "You are a helpful assistant..."
              )}
              testId="system-prompt"
            />
          </Section>

          {/* Model Parameters */}
          <Section
            label={t("langchainEditor.modelParams", "Model Parameters")}
            defaultOpen={false}
          >
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(task.parameters ?? {})
                .filter(([k]) => !HIDDEN_PARAM_KEYS.has(k))
                .map(([k, v]) => (
                  <div key={k} className="flex items-center gap-1.5">
                    <input
                      type="text"
                      value={k}
                      readOnly
                      className="h-7 w-28 rounded border border-input bg-muted px-2 text-xs text-foreground"
                    />
                    <input
                      type="text"
                      value={v}
                      onChange={(e) => updateParam(k, e.target.value)}
                      readOnly={readOnly}
                      className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                    />
                    {!readOnly && (
                      <button
                        type="button"
                        onClick={() => {
                          const next = { ...task.parameters };
                          delete next[k];
                          onChange({ ...task, parameters: next });
                        }}
                        className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    )}
                  </div>
                ))}
            </div>
            {!readOnly && (
              <button
                type="button"
                onClick={() => {
                  const nextKey = `param${
                    Object.keys(task.parameters ?? {}).length
                  }`;
                  onChange({
                    ...task,
                    parameters: { ...task.parameters, [nextKey]: "" },
                  });
                }}
                className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                <Plus className="h-3 w-3" />
                {t("langchainEditor.addParam", "Add Parameter")}
              </button>
            )}
          </Section>

          {/* Agent Mode */}
          <Section
            label={t("langchainEditor.agentMode", "Agent Mode")}
            defaultOpen={!!isAgent}
          >
            <div className="space-y-3">
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={task.enableBuiltInTools ?? false}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      enableBuiltInTools: e.target.checked,
                    })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="enable-builtin-tools"
                />
                {t(
                  "langchainEditor.enableBuiltInTools",
                  "Enable Built-in Tools"
                )}
              </label>

              {task.enableBuiltInTools && (
                <div>
                  <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t(
                      "langchainEditor.toolWhitelist",
                      "Tool Whitelist (empty = all)"
                    )}
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {BUILT_IN_TOOLS.map((tool) => (
                      <label
                        key={tool}
                        className="inline-flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs text-foreground transition-colors hover:bg-secondary"
                      >
                        <input
                          type="checkbox"
                          checked={
                            task.builtInToolsWhitelist?.includes(tool) ?? false
                          }
                          onChange={(e) => {
                            const wl = task.builtInToolsWhitelist ?? [];
                            onChange({
                              ...task,
                              builtInToolsWhitelist: e.target.checked
                                ? [...wl, tool]
                                : wl.filter((t) => t !== tool),
                            });
                          }}
                          disabled={readOnly}
                          className="h-3 w-3 accent-primary"
                        />
                        {tool}
                      </label>
                    ))}
                  </div>
                </div>
              )}

              {/* Auto-discover HTTP Call Tools */}
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={task.enableHttpCallTools ?? true}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      enableHttpCallTools: e.target.checked,
                    })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="enable-httpcall-tools"
                />
                {t(
                  "langchainEditor.enableHttpCallTools",
                  "Auto-Discover HTTP Call Tools"
                )}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5 -mt-2">
                {t(
                  "langchainEditor.enableHttpCallToolsDesc",
                  "Automatically expose all httpcall extensions from the workflow as LLM tools"
                )}
              </p>

              {/* Auto-discover MCP Call Tools */}
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={task.enableMcpCallTools ?? true}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      enableMcpCallTools: e.target.checked,
                    })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="enable-mcpcall-tools"
                />
                {t(
                  "langchainEditor.enableMcpCallTools",
                  "Auto-Discover MCP Call Tools"
                )}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5 -mt-2">
                {t(
                  "langchainEditor.enableMcpCallToolsDesc",
                  "Automatically expose all mcpcalls extensions from the workflow as LLM tools"
                )}
              </p>

              {/* Explicit HTTP Call Tool URIs */}
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("langchainEditor.httpCallTools", "HTTP Call Tool URIs")}
                </label>
                <p className="mb-1.5 text-[10px] text-muted-foreground">
                  {t(
                    "langchainEditor.httpCallToolsHint",
                    "Explicit httpcall URIs (in addition to auto-discovered ones)"
                  )}
                </p>
                <div className="space-y-1.5">
                  {(task.tools ?? []).map((uri, i) => (
                    <div key={i} className="flex items-center gap-1.5">
                      <input
                        type="text"
                        value={uri}
                        onChange={(e) => {
                          const tools = [...(task.tools ?? [])];
                          tools[i] = e.target.value;
                          onChange({ ...task, tools });
                        }}
                        readOnly={readOnly}
                        placeholder="eddi://ai.labs.apicalls/..."
                        className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                      />
                      {!readOnly && (
                        <button
                          type="button"
                          onClick={() =>
                            onChange({
                              ...task,
                              tools: (task.tools ?? []).filter(
                                (_, j) => j !== i
                              ),
                            })
                          }
                          className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      )}
                    </div>
                  ))}
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() =>
                        onChange({
                          ...task,
                          tools: [...(task.tools ?? []), ""],
                        })
                      }
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                    >
                      <Plus className="h-3 w-3" />
                      {t("langchainEditor.addTool", "Add Tool URI")}
                    </button>
                  )}
                </div>
              </div>

              {/* A2A Agents */}
              <div>
                <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <Handshake className="h-3 w-3" />
                  {t("langchainEditor.a2aAgents", "A2A Agents")}
                </label>
                <p className="mb-1.5 text-[10px] text-muted-foreground">
                  {t(
                    "langchainEditor.a2aAgentsDesc",
                    "Remote A2A-compatible agents whose skills become LLM tools"
                  )}
                </p>
                <div className="space-y-2">
                  {(task.a2aAgents ?? []).map((agent, ai) => (
                    <div
                      key={ai}
                      className="rounded-lg border border-border bg-secondary/20 p-3 space-y-2"
                      data-testid={`a2a-agent-${ai}`}
                    >
                      <div className="flex items-center gap-1.5">
                        <input
                          type="url"
                          value={agent.url ?? ""}
                          onChange={(e) => {
                            const agents = [...(task.a2aAgents ?? [])];
                            agents[ai] = { ...agent, url: e.target.value };
                            onChange({ ...task, a2aAgents: agents });
                          }}
                          readOnly={readOnly}
                          placeholder={t(
                            "langchainEditor.a2aUrlPlaceholder",
                            "https://remote.example.com/a2a/agents/..."
                          )}
                          dir="ltr"
                          className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        {!readOnly && (
                          <button
                            type="button"
                            onClick={() =>
                              onChange({
                                ...task,
                                a2aAgents: (task.a2aAgents ?? []).filter(
                                  (_, j) => j !== ai
                                ),
                              })
                            }
                            className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
                        )}
                      </div>
                      <div className="grid grid-cols-3 gap-2">
                        <input
                          type="text"
                          value={agent.name ?? ""}
                          onChange={(e) => {
                            const agents = [...(task.a2aAgents ?? [])];
                            agents[ai] = { ...agent, name: e.target.value };
                            onChange({ ...task, a2aAgents: agents });
                          }}
                          readOnly={readOnly}
                          placeholder={t("langchainEditor.a2aName", "Display Name")}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        <div className="relative">
                          <input
                            type="text"
                            value={agent.apiKey ?? ""}
                            onChange={(e) => {
                              const agents = [...(task.a2aAgents ?? [])];
                              agents[ai] = { ...agent, apiKey: e.target.value };
                              onChange({ ...task, a2aAgents: agents });
                            }}
                            readOnly={readOnly}
                            placeholder={t(
                              "langchainEditor.a2aApiKey",
                              "${vault:my-a2a-key}"
                            )}
                            dir="ltr"
                            className="h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                          />
                          {agent.apiKey && !agent.apiKey.startsWith("${vault:") && (
                            <div className="absolute inset-e-1.5 top-1/2 -translate-y-1/2" title="Use ${vault:...} for security">
                              <AlertTriangle className="h-3 w-3 text-amber-500" />
                            </div>
                          )}
                        </div>
                        <input
                          type="number"
                          value={agent.timeoutMs ?? 30000}
                          onChange={(e) => {
                            const agents = [...(task.a2aAgents ?? [])];
                            agents[ai] = {
                              ...agent,
                              timeoutMs: parseInt(e.target.value, 10) || 30000,
                            };
                            onChange({ ...task, a2aAgents: agents });
                          }}
                          readOnly={readOnly}
                          placeholder={t("langchainEditor.a2aTimeout", "Timeout (ms)")}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      {/* Skills filter */}
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.a2aSkillsFilter", "Skills Filter (empty = all)")}
                        </label>
                        <SkillsFilterInput
                          skills={agent.skillsFilter ?? []}
                          onChange={(sf) => {
                            const agents = [...(task.a2aAgents ?? [])];
                            agents[ai] = { ...agent, skillsFilter: sf.length > 0 ? sf : undefined };
                            onChange({ ...task, a2aAgents: agents });
                          }}
                          readOnly={readOnly}
                        />
                      </div>
                    </div>
                  ))}
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() =>
                        onChange({
                          ...task,
                          a2aAgents: [
                            ...(task.a2aAgents ?? []),
                            { url: "", timeoutMs: 30000 },
                          ],
                        })
                      }
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                      data-testid="add-a2a-agent"
                    >
                      <Plus className="h-3 w-3" />
                      {t("langchainEditor.addA2aAgent", "Add A2A Agent")}
                    </button>
                  )}
                </div>
              </div>

              {/* History limit */}
              <div className="flex items-center gap-2">
                <label className="text-xs text-foreground whitespace-nowrap">
                  {t("langchainEditor.historyLimit", "History Limit")}
                </label>
                <input
                  type="number"
                  value={task.conversationHistoryLimit ?? 10}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      conversationHistoryLimit: parseInt(e.target.value, 10) || 0,
                    })
                  }
                  readOnly={readOnly}
                  className="h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
                <span className="text-[10px] text-muted-foreground">
                  {t("langchainEditor.historyLimitHint", "(-1 = unlimited)")}
                </span>
              </div>
            </div>
          </Section>

          {/* ══════ Budget & Costs ══════ */}
          <Section
            label={t("langchainEditor.budgetCosts", "Budget & Costs")}
            icon={DollarSign}
            accent="text-amber-500"
            defaultOpen={false}
          >
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <label className="text-xs text-foreground whitespace-nowrap">
                  {t("langchainEditor.maxBudget", "Max Budget ($)")}
                </label>
                <input
                  type="number"
                  step="0.1"
                  value={task.maxBudgetPerConversation ?? ""}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      maxBudgetPerConversation: e.target.value
                        ? parseFloat(e.target.value)
                        : undefined,
                    })
                  }
                  readOnly={readOnly}
                  placeholder="1.00"
                  className="h-7 w-24 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
              <div className="flex flex-wrap gap-4">
                <label className="inline-flex items-center gap-2 text-xs text-foreground">
                  <input
                    type="checkbox"
                    checked={task.enableCostTracking ?? true}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        enableCostTracking: e.target.checked,
                      })
                    }
                    disabled={readOnly}
                    className="h-3.5 w-3.5 rounded border-input accent-primary"
                  />
                  {t("langchainEditor.costTracking", "Cost Tracking")}
                </label>
                <label className="inline-flex items-center gap-2 text-xs text-foreground">
                  <input
                    type="checkbox"
                    checked={task.enableToolCaching ?? true}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        enableToolCaching: e.target.checked,
                      })
                    }
                    disabled={readOnly}
                    className="h-3.5 w-3.5 rounded border-input accent-primary"
                  />
                  {t("langchainEditor.toolCaching", "Tool Caching")}
                </label>
              </div>
            </div>
          </Section>

          {/* ══════ Execution ══════ */}
          <Section
            label={t("langchainEditor.execution", "Execution")}
            icon={Cpu}
            accent="text-sky-500"
            defaultOpen={false}
          >
            <div className="space-y-3">
              {/* Parallel execution */}
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={task.enableParallelExecution ?? false}
                  onChange={(e) =>
                    onChange({ ...task, enableParallelExecution: e.target.checked })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="enable-parallel-execution"
                />
                {t("langchainEditor.parallelExecution", "Parallel Tool Execution")}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5 -mt-2">
                {t("langchainEditor.parallelExecutionDesc", "Run independent tool calls concurrently instead of sequentially")}
              </p>
              {task.enableParallelExecution && (
                <div className="flex items-center gap-2 ps-5">
                  <label className="text-xs text-foreground whitespace-nowrap">
                    {t("langchainEditor.parallelTimeout", "Timeout (ms)")}
                  </label>
                  <input
                    type="number"
                    value={task.parallelExecutionTimeoutMs ?? 30000}
                    onChange={(e) =>
                      onChange({ ...task, parallelExecutionTimeoutMs: parseInt(e.target.value, 10) || 30000 })
                    }
                    readOnly={readOnly}
                    className="h-7 w-24 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              )}

              <div className="flex items-center gap-2">
                <label className="text-xs text-foreground whitespace-nowrap">
                  {t("langchainEditor.maxToolIterations", "Max Tool Iterations")}
                </label>
                <input
                  type="number"
                  value={task.maxToolIterations ?? ""}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      maxToolIterations: e.target.value ? parseInt(e.target.value, 10) : undefined,
                    })
                  }
                  readOnly={readOnly}
                  placeholder="10"
                  className="h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
                <span className="text-[10px] text-muted-foreground">
                  {t("langchainEditor.maxToolIterationsHint", "(default 10)")}
                </span>
              </div>

              {/* Rate limiting */}
              <div className="border-t border-border pt-3 space-y-2">
                <label className="inline-flex items-center gap-2 text-xs text-foreground">
                  <input
                    type="checkbox"
                    checked={task.enableRateLimiting ?? true}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        enableRateLimiting: e.target.checked,
                      })
                    }
                    disabled={readOnly}
                    className="h-3.5 w-3.5 rounded border-input accent-primary"
                  />
                  {t("langchainEditor.rateLimiting", "Rate Limiting")}
                </label>
                {task.enableRateLimiting && (
                  <>
                    <div className="flex items-center gap-2 ps-5">
                      <label className="text-xs text-foreground whitespace-nowrap">
                        {t("langchainEditor.defaultRate", "Default Rate (req/min)")}
                      </label>
                      <input
                        type="number"
                        value={task.defaultRateLimit ?? 100}
                        onChange={(e) =>
                          onChange({
                            ...task,
                            defaultRateLimit: parseInt(e.target.value, 10) || 100,
                          })
                        }
                        readOnly={readOnly}
                        className="h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                      />
                    </div>

                    {/* Per-tool rate limits — array-based to prevent key collision */}
                    <div className="ps-5">
                      <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                        <Gauge className="h-3 w-3" />
                        {t("langchainEditor.toolRateLimits", "Per-Tool Rate Limits")}
                      </label>
                      <p className="mb-1.5 text-[10px] text-muted-foreground">
                        {t("langchainEditor.toolRateLimitsDesc", "Override the default rate for specific tools (calls/min)")}
                      </p>
                      <div className="space-y-1.5">
                        {Object.entries(task.toolRateLimits ?? {}).map(([tool, rate], i) => (
                          <div key={`rate-${i}`} className="flex items-center gap-1.5">
                            <input
                              type="text"
                              value={tool}
                              onChange={(e) => {
                                // Convert to array, update by index, convert back — safe for renames
                                const entries = Object.entries(task.toolRateLimits ?? {});
                                entries[i] = [e.target.value, rate];
                                // Deduplicate: last entry with same key wins
                                const deduped = new Map(entries);
                                onChange({ ...task, toolRateLimits: Object.fromEntries(deduped) });
                              }}
                              readOnly={readOnly}
                              placeholder={t("langchainEditor.toolName", "Tool name")}
                              className="h-7 w-40 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                            />
                            <input
                              type="number"
                              value={rate}
                              onChange={(e) => {
                                const entries = Object.entries(task.toolRateLimits ?? {});
                                entries[i] = [tool, parseInt(e.target.value, 10) || 0];
                                onChange({ ...task, toolRateLimits: Object.fromEntries(entries) });
                              }}
                              readOnly={readOnly}
                              placeholder="100"
                              className="h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                            />
                            <span className="text-[10px] text-muted-foreground">/min</span>
                            {!readOnly && (
                              <button
                                type="button"
                                onClick={() => {
                                  const entries = Object.entries(task.toolRateLimits ?? {}).filter((_, j) => j !== i);
                                  onChange({ ...task, toolRateLimits: entries.length > 0 ? Object.fromEntries(entries) : undefined });
                                }}
                                className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                              >
                                <X className="h-3 w-3" />
                              </button>
                            )}
                          </div>
                        ))}
                        {!readOnly && (
                          <button
                            type="button"
                            onClick={() => {
                              // Generate unique key to prevent collision
                              const existing = Object.keys(task.toolRateLimits ?? {});
                              let key = "tool";
                              let n = 1;
                              while (existing.includes(key)) { key = `tool${n++}`; }
                              onChange({
                                ...task,
                                toolRateLimits: { ...(task.toolRateLimits ?? {}), [key]: 100 },
                              });
                            }}
                            className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                          >
                            <Plus className="h-3 w-3" />
                            {t("langchainEditor.addToolRate", "Add Tool Rate")}
                          </button>
                        )}
                      </div>
                    </div>
                  </>
                )}
              </div>
            </div>
          </Section>

          {/* ══════ Model Cascade ══════ */}
          <Section
            label={t("langchainEditor.cascade", "Model Cascade")}
            icon={Layers}
            accent="text-purple-500"
            defaultOpen={!!(task.modelCascade?.enabled)}
          >
            <div className="space-y-3" data-testid="cascade-section">
              {/* Explain what cascade does */}
              <p className="text-[10px] text-muted-foreground leading-relaxed">
                {t("langchainEditor.cascadeDesc", "Try a cheap/fast model first. If confidence is too low, automatically escalate to a more powerful (and expensive) model. Saves costs without sacrificing quality.")}
              </p>

              {/* Enable toggle */}
              <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
                <input
                  type="checkbox"
                  checked={task.modelCascade?.enabled ?? false}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      modelCascade: {
                        ...task.modelCascade,
                        enabled: e.target.checked,
                        strategy: task.modelCascade?.strategy ?? "cascade",
                        evaluationStrategy: task.modelCascade?.evaluationStrategy ?? "structured_output",
                        enableInAgentMode: task.modelCascade?.enableInAgentMode ?? true,
                        steps: task.modelCascade?.steps ?? [],
                      },
                    })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid="cascade-enable"
                />
                <Layers className="h-3.5 w-3.5 text-primary" />
                {t("langchainEditor.cascadeEnable", "Enable Model Cascade")}
              </label>

              {task.modelCascade?.enabled && (
                <div className="space-y-3 rounded-lg border border-primary/20 bg-primary/5 p-3">
                  {/* Strategy + Evaluation */}
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                        {t("langchainEditor.cascadeStrategy", "Strategy")}
                      </label>
                      <select
                        value={task.modelCascade.strategy ?? "cascade"}
                        onChange={(e) =>
                          onChange({
                            ...task,
                            modelCascade: { ...task.modelCascade!, strategy: e.target.value },
                          })
                        }
                        disabled={readOnly}
                        className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                      >
                        <option value="cascade">{t("langchainEditor.strategyCascade", "Sequential Escalation")}</option>
                        <option value="parallel">{t("langchainEditor.strategyParallel", "Parallel (future)")}</option>
                      </select>
                      <p className="mt-0.5 text-[10px] text-muted-foreground">
                        {t("langchainEditor.cascadeStrategyHint", "Sequential tries cheap first, escalates on low confidence")}
                      </p>
                    </div>
                    <div>
                      <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                        {t("langchainEditor.cascadeEvalStrategy", "Confidence Evaluation")}
                      </label>
                      <select
                        value={task.modelCascade.evaluationStrategy ?? "structured_output"}
                        onChange={(e) =>
                          onChange({
                            ...task,
                            modelCascade: { ...task.modelCascade!, evaluationStrategy: e.target.value },
                          })
                        }
                        disabled={readOnly}
                        className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                      >
                        <option value="structured_output">{t("langchainEditor.evalStructured", "Structured Output (JSON)")}</option>
                        <option value="heuristic">{t("langchainEditor.evalHeuristic", "Heuristic (hedging detection)")}</option>
                        <option value="judge_model">{t("langchainEditor.evalJudge", "Judge Model (secondary LLM)")}</option>
                        <option value="none">{t("langchainEditor.evalNone", "None (always accept)")}</option>
                      </select>
                      <p className="mt-0.5 text-[10px] text-muted-foreground">
                        {t("langchainEditor.cascadeEvalHint", "How to determine if a response is good enough")}
                      </p>
                    </div>
                  </div>

                  {/* Enable in agent mode */}
                  <label className="inline-flex items-center gap-2 text-xs text-foreground">
                    <input
                      type="checkbox"
                      checked={task.modelCascade.enableInAgentMode ?? true}
                      onChange={(e) =>
                        onChange({
                          ...task,
                          modelCascade: { ...task.modelCascade!, enableInAgentMode: e.target.checked },
                        })
                      }
                      disabled={readOnly}
                      className="h-3.5 w-3.5 rounded border-input accent-primary"
                    />
                    {t("langchainEditor.cascadeInAgent", "Also use cascade in Agent Mode (with tools)")}
                  </label>

                  {/* Steps */}
                  <div>
                    <label className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                      <ArrowDown className="h-3 w-3" />
                      {t("langchainEditor.cascadeSteps", "Cascade Steps (cheap → expensive)")}
                    </label>
                    <p className="mb-2 text-[10px] text-muted-foreground">
                      {t("langchainEditor.cascadeStepsDesc", "Order matters: first step tried first. Last step is always accepted (set confidence to empty).")}
                    </p>

                    <div className="space-y-2">
                      {(task.modelCascade.steps ?? []).map((step, si) => (
                        <div
                          key={si}
                          className="rounded-lg border border-border bg-card p-3 space-y-2"
                          data-testid={`cascade-step-${si}`}
                        >
                          <div className="flex items-center gap-2">
                            <span className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary">
                              {si + 1}
                            </span>
                            <select
                              value={step.type ?? "openai"}
                              onChange={(e) => {
                                const steps = [...(task.modelCascade!.steps ?? [])];
                                steps[si] = { ...step, type: e.target.value };
                                onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                              }}
                              disabled={readOnly}
                              className="h-7 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                            >
                              {MODEL_TYPES.map((mt) => (
                                <option key={mt} value={mt}>{mt}</option>
                              ))}
                            </select>
                            <input
                              type="text"
                              value={step.parameters?.model ?? ""}
                              onChange={(e) => {
                                const steps = [...(task.modelCascade!.steps ?? [])];
                                steps[si] = { ...step, parameters: { ...(step.parameters ?? {}), model: e.target.value } };
                                onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                              }}
                              readOnly={readOnly}
                              placeholder={t("langchainEditor.cascadeModelName", "e.g. gpt-4o-mini")}
                              className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                            />
                            {!readOnly && (
                              <div className="flex items-center gap-0.5">
                                <button
                                  type="button"
                                  disabled={si === 0}
                                  onClick={() => {
                                    const steps = [...(task.modelCascade!.steps ?? [])];
                                    const temp = steps[si];
                                    steps[si] = steps[si - 1]!;
                                    steps[si - 1] = temp!;
                                    onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                                  }}
                                  className="rounded p-1 text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
                                  title={t("langchainEditor.moveUp", "Move up")}
                                >
                                  <ArrowUp className="h-3.5 w-3.5" />
                                </button>
                                <button
                                  type="button"
                                  disabled={si === (task.modelCascade!.steps ?? []).length - 1}
                                  onClick={() => {
                                    const steps = [...(task.modelCascade!.steps ?? [])];
                                    const temp = steps[si];
                                    steps[si] = steps[si + 1]!;
                                    steps[si + 1] = temp!;
                                    onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                                  }}
                                  className="rounded p-1 text-muted-foreground hover:text-foreground transition-colors disabled:opacity-30"
                                  title={t("langchainEditor.moveDown", "Move down")}
                                >
                                  <ArrowDown className="h-3.5 w-3.5" />
                                </button>
                                <button
                                  type="button"
                                  onClick={() => {
                                    const steps = (task.modelCascade!.steps ?? []).filter((_, j) => j !== si);
                                    onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                                  }}
                                  className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                                >
                                  <Trash2 className="h-3.5 w-3.5" />
                                </button>
                              </div>
                            )}
                          </div>
                          <div className="grid grid-cols-2 gap-2 ps-7">
                            <div>
                              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                                {t("langchainEditor.cascadeConfidence", "Min. Confidence (0–1)")}
                              </label>
                              <input
                                type="text"
                                inputMode="decimal"
                                pattern="[0-9]*(\.[0-9]+)?"
                                value={step.confidenceThreshold ?? ""}
                                onChange={(e) => {
                                  const steps = [...(task.modelCascade!.steps ?? [])];
                                  const val = e.target.value;
                                  steps[si] = {
                                    ...step,
                                    confidenceThreshold: val === "" ? null : (parseFloat(val) || 0),
                                  };
                                  onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                                }}
                                readOnly={readOnly}
                                placeholder={t("langchainEditor.cascadeConfidencePlaceholder", "empty = always accept")}
                                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                              />
                            </div>
                            <div>
                              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                                {t("langchainEditor.cascadeTimeout", "Timeout (ms)")}
                              </label>
                              <input
                                type="number"
                                value={step.timeoutMs ?? 30000}
                                onChange={(e) => {
                                  const steps = [...(task.modelCascade!.steps ?? [])];
                                  steps[si] = { ...step, timeoutMs: parseInt(e.target.value, 10) || 30000 };
                                  onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                                }}
                                readOnly={readOnly}
                                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                              />
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>

                    {!readOnly && (
                      <button
                        type="button"
                        onClick={() => {
                          const steps = [...(task.modelCascade?.steps ?? []), { type: "openai", parameters: { model: "" }, confidenceThreshold: 0.7, timeoutMs: 30000 }];
                          onChange({ ...task, modelCascade: { ...task.modelCascade!, steps } });
                        }}
                        className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-dashed border-primary/40 px-3 py-1.5 text-xs font-medium text-primary/70 transition-colors hover:border-primary hover:text-primary"
                        data-testid="add-cascade-step"
                      >
                        <Plus className="h-3.5 w-3.5" />
                        {t("langchainEditor.addCascadeStep", "Add Cascade Step")}
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          </Section>

          {/* ══════ Retry Configuration ══════ */}
          <Section
            label={t("langchainEditor.retryConfig", "Retry Configuration")}
            icon={RotateCcw}
            accent="text-orange-500"
            defaultOpen={false}
          >
            <div className="space-y-2" data-testid="retry-section">
              <p className="text-[10px] text-muted-foreground">
                {t("langchainEditor.retryDesc", "Configure automatic retries for failed LLM API calls with exponential backoff.")}
              </p>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    <RotateCcw className="inline h-3 w-3 me-1" />
                    {t("langchainEditor.retryMaxAttempts", "Max Attempts")}
                  </label>
                  <input
                    type="number"
                    value={task.retry?.maxAttempts ?? 3}
                    onChange={(e) =>
                      onChange({ ...task, retry: { ...task.retry, maxAttempts: parseInt(e.target.value, 10) || 1 } })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("langchainEditor.retryDelay", "Initial Delay (ms)")}
                  </label>
                  <input
                    type="number"
                    value={task.retry?.backoffDelayMs ?? 1000}
                    onChange={(e) =>
                      onChange({ ...task, retry: { ...task.retry, backoffDelayMs: parseInt(e.target.value, 10) || 0 } })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("langchainEditor.retryMultiplier", "Backoff Multiplier")}
                  </label>
                  <input
                    type="number"
                    step="0.1"
                    value={task.retry?.backoffMultiplier ?? 2.0}
                    onChange={(e) =>
                      onChange({ ...task, retry: { ...task.retry, backoffMultiplier: parseFloat(e.target.value) || 1.0 } })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("langchainEditor.retryMaxDelay", "Max Delay (ms)")}
                  </label>
                  <input
                    type="number"
                    value={task.retry?.maxBackoffDelayMs ?? 10000}
                    onChange={(e) =>
                      onChange({ ...task, retry: { ...task.retry, maxBackoffDelayMs: parseInt(e.target.value, 10) || 0 } })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              </div>
            </div>
          </Section>

          {/* ══════ RAG Configuration (Phase 8c) ══════ */}
          <Section
            label={t("langchainEditor.ragConfig", "RAG (Knowledge Retrieval)")}
            icon={Database}
            accent="text-emerald-500"
            defaultOpen={false}
          >
            <div className="space-y-4" data-testid="rag-section">
              <p className="text-[10px] text-muted-foreground">
                {t("langchainEditor.ragDesc", "Augment LLM responses with relevant documents from knowledge bases. Three modes can be combined.")}
              </p>

              {/* ── Mode 1: Explicit Knowledge Bases ── */}
              <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-foreground">
                    {t("langchainEditor.knowledgeBases", "Knowledge Bases")}
                  </span>
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() => onChange({
                        ...task,
                        knowledgeBases: [
                          ...(task.knowledgeBases ?? []),
                          { name: "", maxResults: 5, minScore: 0.6 },
                        ],
                      })}
                      className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                      data-testid="add-kb-ref"
                    >
                      <Plus className="h-3 w-3" />
                      {t("langchainEditor.addKnowledgeBase", "Add KB Reference")}
                    </button>
                  )}
                </div>
                <p className="text-[10px] text-muted-foreground">
                  {t("langchainEditor.knowledgeBasesHint", "Explicitly reference knowledge bases by name. Each name must match a RagConfiguration in the workflow.")}
                </p>
                {(task.knowledgeBases ?? []).map((kb, kbIdx) => (
                  <div key={kbIdx} className="rounded-md border border-border bg-background p-2.5 space-y-2">
                    <div className="flex items-center gap-2">
                      <input
                        type="text"
                        value={kb.name ?? ""}
                        onChange={(e) => {
                          const updated = [...(task.knowledgeBases ?? [])];
                          updated[kbIdx] = { ...kb, name: e.target.value || undefined };
                          onChange({ ...task, knowledgeBases: updated });
                        }}
                        readOnly={readOnly}
                        placeholder={t("langchainEditor.kbNamePlaceholder", "e.g. product-docs")}
                        className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        data-testid={`kb-name-${kbIdx}`}
                      />
                      {!readOnly && (
                        <button
                          type="button"
                          onClick={() => {
                            const updated = (task.knowledgeBases ?? []).filter((_, i) => i !== kbIdx);
                            onChange({ ...task, knowledgeBases: updated.length > 0 ? updated : undefined });
                          }}
                          className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                        >
                          <X className="h-3 w-3" />
                        </button>
                      )}
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.ragMaxResults", "Max Results")}
                        </label>
                        <input
                          type="number"
                          value={kb.maxResults ?? ""}
                          onChange={(e) => {
                            const updated = [...(task.knowledgeBases ?? [])];
                            updated[kbIdx] = { ...kb, maxResults: e.target.value ? parseInt(e.target.value, 10) : undefined };
                            onChange({ ...task, knowledgeBases: updated });
                          }}
                          readOnly={readOnly}
                          placeholder="5"
                          className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.ragMinScore", "Min Score")}
                        </label>
                        <input
                          type="number"
                          step="0.05"
                          min="0"
                          max="1"
                          value={kb.minScore ?? ""}
                          onChange={(e) => {
                            const updated = [...(task.knowledgeBases ?? [])];
                            updated[kbIdx] = { ...kb, minScore: e.target.value ? parseFloat(e.target.value) : undefined };
                            onChange({ ...task, knowledgeBases: updated });
                          }}
                          readOnly={readOnly}
                          placeholder="0.6"
                          className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.injectionStrategy", "Injection")}
                        </label>
                        <select
                          value={kb.injectionStrategy ?? "system_message"}
                          onChange={(e) => {
                            const updated = [...(task.knowledgeBases ?? [])];
                            updated[kbIdx] = { ...kb, injectionStrategy: e.target.value };
                            onChange({ ...task, knowledgeBases: updated });
                          }}
                          disabled={readOnly}
                          className="h-7 w-full rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                        >
                          <option value="system_message">System Message</option>
                          <option value="user_message">User Message</option>
                        </select>
                      </div>
                    </div>
                  </div>
                ))}
                {(task.knowledgeBases ?? []).length === 0 && (
                  <p className="text-[10px] text-muted-foreground/60 italic">
                    {t("langchainEditor.noKnowledgeBases", "No knowledge bases referenced")}
                  </p>
                )}
              </div>

              {/* ── Mode 2: Auto-Discovery ── */}
              <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={task.enableWorkflowRag ?? false}
                    onChange={(e) => onChange({ ...task, enableWorkflowRag: e.target.checked || undefined })}
                    disabled={readOnly}
                    className="rounded"
                    data-testid="enable-workflow-rag"
                  />
                  <span className="text-xs font-semibold text-foreground">
                    {t("langchainEditor.enableWorkflowRag", "Auto-Discover Workflow RAG")}
                  </span>
                </label>
                <p className="text-[10px] text-muted-foreground">
                  {t("langchainEditor.workflowRagHint", "Automatically discovers all RAG steps from the workflow. Only used when no explicit knowledge bases are listed above.")}
                </p>
                {task.enableWorkflowRag && (
                  <div className="ms-6 space-y-2 border-s-2 border-primary/20 ps-3">
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                      {t("langchainEditor.ragDefaults", "Default Retrieval Parameters")}
                    </span>
                    <div className="grid grid-cols-3 gap-2">
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.ragMaxResults", "Max Results")}
                        </label>
                        <input
                          type="number"
                          value={task.ragDefaults?.maxResults ?? ""}
                          onChange={(e) =>
                            onChange({ ...task, ragDefaults: { ...task.ragDefaults, maxResults: e.target.value ? parseInt(e.target.value, 10) : undefined } })
                          }
                          readOnly={readOnly}
                          placeholder="5"
                          className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.ragMinScore", "Min Score")}
                        </label>
                        <input
                          type="number"
                          step="0.05"
                          min="0"
                          max="1"
                          value={task.ragDefaults?.minScore ?? ""}
                          onChange={(e) =>
                            onChange({ ...task, ragDefaults: { ...task.ragDefaults, minScore: e.target.value ? parseFloat(e.target.value) : undefined } })
                          }
                          readOnly={readOnly}
                          placeholder="0.6"
                          className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("langchainEditor.injectionStrategy", "Injection")}
                        </label>
                        <select
                          value={task.ragDefaults?.injectionStrategy ?? "system_message"}
                          onChange={(e) =>
                            onChange({ ...task, ragDefaults: { ...task.ragDefaults, injectionStrategy: e.target.value } })
                          }
                          disabled={readOnly}
                          className="h-7 w-full rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                        >
                          <option value="system_message">System Message</option>
                          <option value="user_message">User Message</option>
                        </select>
                      </div>
                    </div>
                  </div>
                )}
              </div>

              {/* ── Mode 3: httpCall RAG (zero-infra) ── */}
              <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
                <span className="text-xs font-semibold text-foreground">
                  {t("langchainEditor.httpCallRag", "httpCall RAG (Zero Infrastructure)")}
                </span>
                <p className="text-[10px] text-muted-foreground">
                  {t("langchainEditor.httpCallRagHint", "Execute a named httpCall before the LLM call. The response is injected as context — no vector store needed.")}
                </p>
                <input
                  type="text"
                  value={task.httpCallRag ?? ""}
                  onChange={(e) => onChange({ ...task, httpCallRag: e.target.value || undefined })}
                  readOnly={readOnly}
                  placeholder={t("langchainEditor.httpCallRagPlaceholder", "e.g. search_docs, query_wiki")}
                  dir="ltr"
                  className="h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  data-testid="httpcall-rag"
                />
              </div>

              {/* ── Legacy (backward compat, collapsed) ── */}
              {task.retrievalAugmentor && (
                <div className="rounded-md border border-amber-500/30 bg-amber-500/5 p-3 space-y-2">
                  <div className="flex items-center gap-1.5">
                    <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
                    <span className="text-xs font-semibold text-amber-600 dark:text-amber-400">
                      {t("langchainEditor.legacyRag", "Legacy RAG (deprecated)")}
                    </span>
                  </div>
                  <p className="text-[10px] text-muted-foreground">
                    {t("langchainEditor.legacyRagHint", "This configuration uses the deprecated retrievalAugmentor format. Migrate to the modes above.")}
                  </p>
                  <div className="grid grid-cols-2 gap-2 opacity-60">
                    <div className="col-span-2">
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">httpCall</label>
                      <input type="text" value={task.retrievalAugmentor.httpCall ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">embeddingModel</label>
                      <input type="text" value={task.retrievalAugmentor.embeddingModel ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 text-xs" />
                    </div>
                    <div>
                      <label className="mb-0.5 block text-[10px] text-muted-foreground">embeddingStore</label>
                      <input type="text" value={task.retrievalAugmentor.embeddingStore ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 text-xs" />
                    </div>
                  </div>
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() => onChange({ ...task, retrievalAugmentor: undefined })}
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-[10px] text-amber-600 dark:text-amber-400 hover:bg-amber-500/10 transition-colors"
                    >
                      <Trash2 className="h-3 w-3" />
                      {t("langchainEditor.removeLegacyRag", "Remove Legacy Config")}
                    </button>
                  )}
                </div>
              )}
            </div>
          </Section>

          {/* ══════ Pre/Post Instructions ══════ */}
          <Section
            label={t(
              "langchainEditor.prePostInstructions",
              "Pre/Post Instructions"
            )}
            icon={FileCode}
            accent="text-teal-500"
            defaultOpen={!!(task.preRequest || task.postResponse)}
          >
            <div className="space-y-3" data-testid="pre-post-section">
              <p className="text-[10px] text-muted-foreground leading-relaxed">
                {t(
                  "langchainEditor.prePostDesc",
                  "Configure JSON instructions that run before each request or after each response."
                )}
              </p>

              {/* Pre-Request */}
              <div>
                <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("langchainEditor.preRequest", "Pre-Request")}
                </span>
                <ContentEditor
                  value={
                    task.preRequest
                      ? (typeof task.preRequest === "string"
                          ? task.preRequest
                          : JSON.stringify(task.preRequest, null, 2))
                      : ""
                  }
                  onChange={(v) => {
                    try {
                      onChange({ ...task, preRequest: v ? JSON.parse(v) : undefined });
                    } catch {
                      // Keep raw string while user is typing
                      onChange({ ...task, preRequest: v || undefined });
                    }
                  }}
                  readOnly={readOnly}
                  language="json"
                  label={t("langchainEditor.preRequest", "Pre-Request")}
                  placeholder={t(
                    "langchainEditor.preRequestPlaceholder",
                    '{"propertyInstructions": [...]}'
                  )}
                  testId="pre-request-editor"
                  minLines={3}
                  maxLines={12}
                />
              </div>

              {/* Post-Response */}
              <div>
                <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("langchainEditor.postResponse", "Post-Response")}
                </span>
                <ContentEditor
                  value={
                    task.postResponse
                      ? (typeof task.postResponse === "string"
                          ? task.postResponse
                          : JSON.stringify(task.postResponse, null, 2))
                      : ""
                  }
                  onChange={(v) => {
                    try {
                      onChange({ ...task, postResponse: v ? JSON.parse(v) : undefined });
                    } catch {
                      onChange({ ...task, postResponse: v || undefined });
                    }
                  }}
                  readOnly={readOnly}
                  language="json"
                  label={t("langchainEditor.postResponse", "Post-Response")}
                  placeholder={t(
                    "langchainEditor.postResponsePlaceholder",
                    '{"propertyInstructions": [], "outputBuildInstructions": [...]}'
                  )}
                  testId="post-response-editor"
                  minLines={3}
                  maxLines={12}
                />
              </div>
            </div>
          </Section>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export interface LangchainEditorProps {
  data: LangchainConfig;
  onChange: (data: LangchainConfig) => void;
  readOnly?: boolean;
}

export function LangchainEditor({
  data,
  onChange,
  readOnly,
}: LangchainEditorProps) {
  const { t } = useTranslation();

  const addTask = useCallback(() => {
    const newTask: LangchainTask = {
      actions: [],
      type: "openai",
      parameters: { systemMessage: "" },
    };
    onChange({ ...data, tasks: [...(data.tasks ?? []), newTask] });
  }, [data, onChange]);

  return (
    <div className="space-y-6" data-testid="langchain-editor">
      {/* Tasks list */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Zap className="h-4 w-4 text-primary" />
            {t("langchainEditor.tasks", "LangChain Tasks")}
          </h3>
          {!readOnly && (
            <button
              type="button"
              onClick={addTask}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-task-btn"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("langchainEditor.addTask", "Add Task")}
            </button>
          )}
        </div>

        {(data.tasks ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("langchainEditor.noTasks", "No LangChain tasks configured")}
          </div>
        )}

        {(data.tasks ?? []).map((task, ti) => (
          <TaskEditor
            key={ti}
            task={task}
            onChange={(updated) => {
              const tasks = [...(data.tasks ?? [])];
              tasks[ti] = updated;
              onChange({ ...data, tasks });
            }}
            onRemove={() =>
              onChange({
                ...data,
                tasks: (data.tasks ?? []).filter((_, j) => j !== ti),
              })
            }
            readOnly={readOnly}
          />
        ))}
      </div>
    </div>
  );
}
