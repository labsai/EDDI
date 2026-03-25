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
  Server,
} from "lucide-react";
import { ContentEditor } from "./content-editor";

// ─── Types matching LangChainConfiguration backend model ─────────────────────

export interface McpServerConfig {
  url?: string;
  name?: string;
  transport?: string;
  apiKey?: string;
  timeoutMs?: number;
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
  mcpServers?: McpServerConfig[];
  enableBuiltInTools?: boolean;
  enableHttpCallTools?: boolean;
  builtInToolsWhitelist?: string[];
  conversationHistoryLimit?: number;
  retrievalAugmentor?: {
    httpCall?: string;
    embeddingModel?: string;
    embeddingStore?: string;
    maxResults?: number;
    minScore?: number;
  };
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
}

export interface LangchainConfig {
  tasks: LangchainTask[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

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
  children,
}: {
  label: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="mb-1.5 flex items-center gap-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? (
          <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronRight className="h-3 w-3" />
        )}
        {label}
      </button>
      {open && <div className="space-y-2">{children}</div>}
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
    (task.mcpServers && task.mcpServers.length > 0);

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
              language="plaintext"
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
                .filter(([k]) => k !== "systemMessage")
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

              {/* MCP Servers */}
              <div>
                <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <Server className="h-3 w-3" />
                  {t("langchainEditor.mcpServers", "MCP Servers")}
                </label>
                <p className="mb-1.5 text-[10px] text-muted-foreground">
                  {t(
                    "langchainEditor.mcpServersDesc",
                    "External MCP servers whose tools become available to the LLM"
                  )}
                </p>
                <div className="space-y-2">
                  {(task.mcpServers ?? []).map((srv, si) => (
                    <div
                      key={si}
                      className="rounded-lg border border-border bg-secondary/20 p-3 space-y-2"
                      data-testid={`mcp-server-${si}`}
                    >
                      <div className="flex items-center gap-1.5">
                        <input
                          type="url"
                          value={srv.url ?? ""}
                          onChange={(e) => {
                            const servers = [...(task.mcpServers ?? [])];
                            servers[si] = { ...srv, url: e.target.value };
                            onChange({ ...task, mcpServers: servers });
                          }}
                          readOnly={readOnly}
                          placeholder={t(
                            "langchainEditor.mcpUrlPlaceholder",
                            "http://localhost:7070/mcp"
                          )}
                          className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        {!readOnly && (
                          <button
                            type="button"
                            onClick={() =>
                              onChange({
                                ...task,
                                mcpServers: (task.mcpServers ?? []).filter(
                                  (_, j) => j !== si
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
                          value={srv.name ?? ""}
                          onChange={(e) => {
                            const servers = [...(task.mcpServers ?? [])];
                            servers[si] = { ...srv, name: e.target.value };
                            onChange({ ...task, mcpServers: servers });
                          }}
                          readOnly={readOnly}
                          placeholder={t("langchainEditor.mcpName", "Name")}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        <select
                          value={srv.transport ?? "http"}
                          onChange={(e) => {
                            const servers = [...(task.mcpServers ?? [])];
                            servers[si] = {
                              ...srv,
                              transport: e.target.value,
                            };
                            onChange({ ...task, mcpServers: servers });
                          }}
                          disabled={readOnly}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                        >
                          <option value="http">HTTP</option>
                          <option value="sse">SSE</option>
                        </select>
                        <input
                          type="text"
                          value={srv.apiKey ?? ""}
                          onChange={(e) => {
                            const servers = [...(task.mcpServers ?? [])];
                            servers[si] = { ...srv, apiKey: e.target.value };
                            onChange({ ...task, mcpServers: servers });
                          }}
                          readOnly={readOnly}
                          placeholder={t(
                            "langchainEditor.mcpApiKey",
                            "API Key / vault ref"
                          )}
                          className="h-7 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
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
                          mcpServers: [
                            ...(task.mcpServers ?? []),
                            { url: "", transport: "http" },
                          ],
                        })
                      }
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                      data-testid="add-mcp-server"
                    >
                      <Plus className="h-3 w-3" />
                      {t("langchainEditor.addMcpServer", "Add MCP Server")}
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
                      conversationHistoryLimit: parseInt(e.target.value, 10),
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

          {/* Budget & Performance */}
          <Section
            label={t("langchainEditor.budgetPerf", "Budget & Performance")}
            defaultOpen={false}
          >
            <div className="space-y-2">
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
              </div>
              {task.enableRateLimiting && (
                <div className="flex items-center gap-2">
                  <label className="text-xs text-foreground whitespace-nowrap">
                    {t("langchainEditor.defaultRate", "Default Rate (req/min)")}
                  </label>
                  <input
                    type="number"
                    value={task.defaultRateLimit ?? 100}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        defaultRateLimit: parseInt(e.target.value, 10),
                      })
                    }
                    readOnly={readOnly}
                    className="h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              )}
            </div>
          </Section>

          {/* Pre/Post Instructions (JSON preview) */}
          {!!(task.preRequest || task.postResponse) && (
            <Section
              label={t(
                "langchainEditor.prePostInstructions",
                "Pre/Post Instructions"
              )}
              defaultOpen={false}
            >
              {!!task.preRequest && (
                <div>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("langchainEditor.preRequest", "Pre-Request")}
                  </span>
                  <pre className="max-h-32 overflow-auto rounded bg-muted p-2 text-[10px] text-foreground">
                    {JSON.stringify(task.preRequest, null, 2)}
                  </pre>
                </div>
              )}
              {!!task.postResponse && (
                <div>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("langchainEditor.postResponse", "Post-Response")}
                  </span>
                  <pre className="max-h-32 overflow-auto rounded bg-muted p-2 text-[10px] text-foreground">
                    {JSON.stringify(task.postResponse, null, 2)}
                  </pre>
                </div>
              )}
            </Section>
          )}
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
