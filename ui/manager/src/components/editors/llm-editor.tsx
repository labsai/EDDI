import { useState, useCallback } from "react";
import { PromptPreview } from "./prompt-preview";
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
  Gauge,
  DollarSign,
  Cpu,
  FileCode,
  ArrowRightLeft,
  FileOutput,
  MessageCircle,
  Scissors,
  Info,
  Wrench,
  Check,
  ListFilter,
  Eye,
} from "lucide-react";
import { ContentEditor } from "./content-editor";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import {
  PropertyInstructionsEditor,
  OutputBuildInstructionsEditor,
  QrBuildInstructionsEditor,
} from "./apicalls-editor";
import { EditorSection } from "./editor-section";
import { cn } from "@/lib/utils";
import { TaskCascadeSection } from "./llm/task-cascade-section";
import { TaskMemorySection } from "./llm/task-memory-section";
import { TaskRagSection } from "./llm/task-rag-section";

// Re-export types so existing imports still work
export type {
  A2AAgentConfig,
  CascadeStep,
  ModelCascadeConfig,
  LangchainTask,
  LlmTask,
  KnowledgeBaseReference,
  LangchainConfig,
  LlmConfig,
  LlmPreRequest,
  LlmPostResponse,
  ConversationSummaryConfig,
  ToolResponseLimitsConfig,
} from "./llm/types";

import {
  type LlmTask as LangchainTask,
  type LlmConfig as LangchainConfig,
  type LlmPostResponse,
  HIDDEN_PARAM_KEYS,
  MODEL_TYPES,
  BUILT_IN_TOOLS,
} from "./llm/types";

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
        {actions.map((a) => (
          <span
            key={a}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
          >
            {a}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(actions.filter((x) => x !== a))}
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
            {t("llmEditor.noActions", "No actions")}
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
              "llmEditor.actionPlaceholder",
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
        {skills.map((s) => (
          <span
            key={s}
            className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary"
          >
            {s}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(skills.filter((x) => x !== s))}
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
  const [showPreview, setShowPreview] = useState(false);

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
      data-testid="llm-task-editor"
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
          placeholder={t("llmEditor.taskId", "Task ID")}
          className="h-8 w-32 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        <span className="flex-1" />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("llmEditor.removeTask", "Remove Task")}
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
              {t("llmEditor.description", "Description")}
            </label>
            <input
              type="text"
              value={task.description ?? ""}
              onChange={(e) =>
                onChange({ ...task, description: e.target.value })
              }
              readOnly={readOnly}
              placeholder={t(
                "llmEditor.descriptionPlaceholder",
                "What this task does"
              )}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>

          {/* Actions */}
          <EditorSection label={t("llmEditor.triggerActions", "Trigger Actions")}>
            <ActionTags
              actions={task.actions ?? []}
              onChange={(a) => onChange({ ...task, actions: a })}
              readOnly={readOnly}
            />
          </EditorSection>

          {/* System Prompt */}
          <EditorSection
            label={t("llmEditor.systemPrompt", "System Prompt")}
          >
            {/* Preview toggle */}
            <div className="mb-2 flex items-center justify-end">
              <button
                type="button"
                onClick={() => setShowPreview(!showPreview)}
                className={cn(
                  "inline-flex items-center gap-1 rounded-md px-2 py-1 text-[10px] font-medium transition-all",
                  showPreview
                    ? "bg-primary/15 text-primary border border-primary/30"
                    : "text-muted-foreground border border-transparent hover:text-foreground hover:border-border"
                )}
                title={showPreview
                  ? t("promptPreview.switchToEdit", "Switch to Edit")
                  : t("promptPreview.switchToPreview", "Preview resolved prompt")
                }
                data-testid="system-prompt-preview-toggle"
              >
                <Eye className="h-3 w-3" />
                {showPreview
                  ? t("promptPreview.edit", "Edit")
                  : t("promptPreview.preview", "Preview")}
              </button>
            </div>

            {showPreview ? (
              <PromptPreview
                template={task.parameters?.systemMessage ?? ""}
              />
            ) : (
              <ContentEditor
                value={task.parameters?.systemMessage ?? ""}
                onChange={(v) => updateParam("systemMessage", v)}
                readOnly={readOnly}
                language="prompt"
                label={t("llmEditor.systemPrompt", "System Prompt")}
                placeholder={t(
                  "llmEditor.systemPromptPlaceholder",
                  "You are a helpful assistant..."
                )}
                testId="system-prompt"
              />
            )}
          </EditorSection>

          {/* Model Parameters */}
          <EditorSection
            label={t("llmEditor.modelParams", "Model Parameters")}
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
                {t("llmEditor.addParam", "Add Parameter")}
              </button>
            )}
          </EditorSection>

          {/* Agent Mode */}
          <EditorSection
            label={t("llmEditor.agentMode", "Agent Mode")}
            defaultOpen={!!isAgent}
          >
            <div className="space-y-4">
              {/* ─── Built-in Tools ─── */}
              <div className="rounded-lg border border-border bg-secondary/10 p-3 space-y-3">
                <div className="flex items-center justify-between">
                  <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
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
                    <Wrench className="h-3.5 w-3.5 text-muted-foreground" />
                    {t(
                      "llmEditor.enableBuiltInTools",
                      "Enable Built-in Tools"
                    )}
                  </label>
                </div>

                {task.enableBuiltInTools && (
                  <div className="space-y-2.5 ps-5">
                    {/* All vs Select Specific toggle */}
                    <div className="flex items-center gap-1 rounded-lg border border-border bg-background p-0.5" role="radiogroup" aria-label={t("llmEditor.enableBuiltInTools", "Enable Built-in Tools")} data-testid="tool-selection-mode">
                      <button
                        type="button"
                        role="radio"
                        aria-checked={!task.builtInToolsWhitelist || task.builtInToolsWhitelist.length === 0}
                        onClick={() => {
                          if (!readOnly) {
                            onChange({
                              ...task,
                              builtInToolsWhitelist: undefined,
                            });
                          }
                        }}
                        disabled={readOnly}
                        className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                          !task.builtInToolsWhitelist || task.builtInToolsWhitelist.length === 0
                            ? "bg-primary text-primary-foreground shadow-sm"
                            : "text-muted-foreground hover:text-foreground"
                        }`}
                        data-testid="tool-mode-all"
                      >
                        <Check className="h-3 w-3" />
                        {t("llmEditor.allTools", "All Tools")}
                      </button>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={!!(task.builtInToolsWhitelist && task.builtInToolsWhitelist.length > 0)}
                        onClick={() => {
                          if (!readOnly && (!task.builtInToolsWhitelist || task.builtInToolsWhitelist.length === 0)) {
                            onChange({
                              ...task,
                              builtInToolsWhitelist: [...BUILT_IN_TOOLS],
                            });
                          }
                        }}
                        disabled={readOnly}
                        className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-all ${
                          task.builtInToolsWhitelist && task.builtInToolsWhitelist.length > 0
                            ? "bg-primary text-primary-foreground shadow-sm"
                            : "text-muted-foreground hover:text-foreground"
                        }`}
                        data-testid="tool-mode-specific"
                      >
                        <ListFilter className="h-3 w-3" />
                        {t("llmEditor.selectSpecific", "Select Specific")}
                      </button>
                    </div>

                    {/* Info callout for All mode */}
                    {(!task.builtInToolsWhitelist || task.builtInToolsWhitelist.length === 0) && (
                      <div className="flex items-start gap-2 rounded-md bg-sky-500/10 px-3 py-2 text-[11px] text-sky-700 dark:text-sky-400" data-testid="all-tools-info">
                        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                        <span>
                          {t(
                            "llmEditor.allToolsInfo",
                            "All {{count}} built-in tools are available to the LLM. Switch to \"Select Specific\" to restrict which tools are exposed.",
                            { count: BUILT_IN_TOOLS.length }
                          )}
                        </span>
                      </div>
                    )}

                    {/* Tool chips (visible in Select Specific mode) */}
                    {task.builtInToolsWhitelist && task.builtInToolsWhitelist.length > 0 && (
                      <div className="space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                            {t("llmEditor.availableTools", "Available Tools")}
                          </span>
                          <span className="text-[10px] text-muted-foreground">
                            {t("llmEditor.toolCount", "{{selected}} of {{total}} selected", {
                              selected: task.builtInToolsWhitelist.length,
                              total: BUILT_IN_TOOLS.length,
                            })}
                          </span>
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {BUILT_IN_TOOLS.map((tool) => {
                            const selected = task.builtInToolsWhitelist?.includes(tool) ?? false;
                            return (
                              <button
                                key={tool}
                                type="button"
                                aria-pressed={selected}
                                onClick={() => {
                                  if (readOnly) return;
                                  const wl = task.builtInToolsWhitelist ?? [];
                                  const next = selected
                                    ? wl.filter((item) => item !== tool)
                                    : [...wl, tool];
                                  onChange({
                                    ...task,
                                    builtInToolsWhitelist: next.length > 0 ? next : undefined,
                                  });
                                }}
                                disabled={readOnly}
                                className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium transition-all ${
                                  selected
                                    ? "bg-primary/15 text-primary border border-primary/30 shadow-sm"
                                    : "bg-secondary/50 text-muted-foreground border border-transparent hover:border-border hover:text-foreground"
                                }`}
                                data-testid={`tool-chip-${tool}`}
                              >
                                {selected && <Check className="h-3 w-3" />}
                                {tool}
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* ─── Auto-discover Tool Sources ─── */}
              <div className="rounded-lg border border-border bg-secondary/10 p-3 space-y-3">
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.toolSources", "Workflow Tool Sources")}
                </label>

                {/* Info callout about defaults — shown only when both sources are enabled */}
                {(task.enableHttpCallTools ?? true) && (task.enableMcpCallTools ?? true) && (
                  <div className="flex items-start gap-2 rounded-md bg-amber-500/10 px-3 py-2 text-[11px] text-amber-700 dark:text-amber-400" data-testid="tool-sources-info">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>
                      {t(
                        "llmEditor.toolSourcesInfo",
                        "Both sources are enabled by default \u2014 all workflow HTTP calls and MCP calls are automatically exposed as LLM tools. Disable a source to exclude it."
                      )}
                    </span>
                  </div>
                )}

                {/* Auto-discover HTTP Call Tools */}
                <div className={`flex items-start gap-2 rounded-md border px-3 py-2 transition-colors ${
                  (task.enableHttpCallTools ?? true)
                    ? "border-emerald-500/30 bg-emerald-500/5"
                    : "border-border bg-secondary/20"
                }`}>
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
                    className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary shrink-0"
                    data-testid="enable-httpcall-tools"
                  />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-medium text-foreground">
                        {t(
                          "llmEditor.enableHttpCallTools",
                          "Auto-Discover HTTP Call Tools"
                        )}
                      </span>
                      {(task.enableHttpCallTools ?? true) && (
                        <span className="inline-flex items-center gap-0.5 rounded-full bg-emerald-500/15 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400">
                          {t("llmEditor.included", "Included")}
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 text-[10px] text-muted-foreground">
                      {t(
                        "llmEditor.enableHttpCallToolsDesc",
                        "Automatically expose all httpcall extensions from the workflow as LLM tools"
                      )}
                    </p>
                  </div>
                </div>

                {/* Auto-discover MCP Call Tools */}
                <div className={`flex items-start gap-2 rounded-md border px-3 py-2 transition-colors ${
                  (task.enableMcpCallTools ?? true)
                    ? "border-emerald-500/30 bg-emerald-500/5"
                    : "border-border bg-secondary/20"
                }`}>
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
                    className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary shrink-0"
                    data-testid="enable-mcpcall-tools"
                  />
                  <div className="flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-medium text-foreground">
                        {t(
                          "llmEditor.enableMcpCallTools",
                          "Auto-Discover MCP Call Tools"
                        )}
                      </span>
                      {(task.enableMcpCallTools ?? true) && (
                        <span className="inline-flex items-center gap-0.5 rounded-full bg-emerald-500/15 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-emerald-600 dark:text-emerald-400">
                          {t("llmEditor.included", "Included")}
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 text-[10px] text-muted-foreground">
                      {t(
                        "llmEditor.enableMcpCallToolsDesc",
                        "Automatically expose all mcpcalls extensions from the workflow as LLM tools"
                      )}
                    </p>
                  </div>
                </div>
              </div>

              {/* Explicit HTTP Call Tool URIs */}
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.httpCallTools", "HTTP Call Tool URIs")}
                </label>
                <p className="mb-1.5 text-[10px] text-muted-foreground">
                  {t(
                    "llmEditor.httpCallToolsHint",
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
                      {t("llmEditor.addTool", "Add Tool URI")}
                    </button>
                  )}
                </div>
              </div>

              {/* A2A Agents */}
              <div>
                <label className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <Handshake className="h-3 w-3" />
                  {t("llmEditor.a2aAgents", "A2A Agents")}
                </label>
                <p className="mb-1.5 text-[10px] text-muted-foreground">
                  {t(
                    "llmEditor.a2aAgentsDesc",
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
                            "llmEditor.a2aUrlPlaceholder",
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
                          placeholder={t("llmEditor.a2aName", "Display Name")}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        <SecretKeyPicker
                          value={agent.apiKey ?? ""}
                          onChange={(v) => {
                            const agents = [...(task.a2aAgents ?? [])];
                            agents[ai] = { ...agent, apiKey: v };
                            onChange({ ...task, a2aAgents: agents });
                          }}
                          readOnly={readOnly}
                          placeholder={t("llmEditor.a2aApiKey", "${vault:my-a2a-key}")}
                          testId={`a2a-apikey-${ai}`}
                        />
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
                          placeholder={t("llmEditor.a2aTimeout", "Timeout (ms)")}
                          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                      </div>
                      {/* Skills filter */}
                      <div>
                        <label className="mb-0.5 block text-[10px] text-muted-foreground">
                          {t("llmEditor.a2aSkillsFilter", "Skills Filter (empty = all)")}
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
                      {t("llmEditor.addA2aAgent", "Add A2A Agent")}
                    </button>
                  )}
                </div>
              </div>

              {/* History limit */}
              <div className="flex items-center gap-2">
                <label className="text-xs text-foreground whitespace-nowrap">
                  {t("llmEditor.historyLimit", "History Limit")}
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
                  {t("llmEditor.historyLimitHint", "(-1 = unlimited)")}
                </span>
              </div>
            </div>
          </EditorSection>

          {/* ══════ Budget & Costs ══════ */}
          <EditorSection
            label={t("llmEditor.budgetCosts", "Budget & Costs")}
            icon={DollarSign}
            accent="text-amber-500"
            defaultOpen={false}
          >
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <label className="text-xs text-foreground whitespace-nowrap">
                  {t("llmEditor.maxBudget", "Max Budget ($)")}
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
                  {t("llmEditor.costTracking", "Cost Tracking")}
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
                  {t("llmEditor.toolCaching", "Tool Caching")}
                </label>
              </div>
            </div>
          </EditorSection>

          {/* ══════ Execution ══════ */}
          <EditorSection
            label={t("llmEditor.execution", "Execution")}
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
                {t("llmEditor.parallelExecution", "Parallel Tool Execution")}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5 -mt-2">
                {t("llmEditor.parallelExecutionDesc", "Run independent tool calls concurrently instead of sequentially")}
              </p>
              {task.enableParallelExecution && (
                <div className="flex items-center gap-2 ps-5">
                  <label className="text-xs text-foreground whitespace-nowrap">
                    {t("llmEditor.parallelTimeout", "Timeout (ms)")}
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
                  {t("llmEditor.maxToolIterations", "Max Tool Iterations")}
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
                  {t("llmEditor.maxToolIterationsHint", "(default 10)")}
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
                  {t("llmEditor.rateLimiting", "Rate Limiting")}
                </label>
                {task.enableRateLimiting && (
                  <>
                    <div className="flex items-center gap-2 ps-5">
                      <label className="text-xs text-foreground whitespace-nowrap">
                        {t("llmEditor.defaultRate", "Default Rate (req/min)")}
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
                        {t("llmEditor.toolRateLimits", "Per-Tool Rate Limits")}
                      </label>
                      <p className="mb-1.5 text-[10px] text-muted-foreground">
                        {t("llmEditor.toolRateLimitsDesc", "Override the default rate for specific tools (calls/min)")}
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
                              placeholder={t("llmEditor.toolName", "Tool name")}
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
                            {t("llmEditor.addToolRate", "Add Tool Rate")}
                          </button>
                        )}
                      </div>
                    </div>
                  </>
                )}
              </div>

              {/* ── Tool Response Limits ── */}
              <div className="border-t border-border pt-3 space-y-2">
                <label className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <Scissors className="h-3 w-3" />
                  {t("llmEditor.toolResponseLimits", "Tool Response Limits")}
                </label>
                <p className="text-[10px] text-muted-foreground">
                  {t("llmEditor.toolResponseLimitsDesc", "Truncate verbose tool outputs before re-injection into the LLM context window. Prevents context bloat.")}
                </p>
                <div className="flex items-center gap-2">
                  <label className="text-xs text-foreground whitespace-nowrap">
                    {t("llmEditor.defaultMaxChars", "Default Max Chars")}
                  </label>
                  <input
                    type="number"
                    value={task.toolResponseLimits?.defaultMaxChars ?? ""}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        toolResponseLimits: {
                          ...task.toolResponseLimits,
                          defaultMaxChars: e.target.value
                            ? parseInt(e.target.value, 10)
                            : undefined,
                        },
                      })
                    }
                    readOnly={readOnly}
                    placeholder="50000"
                    className="h-7 w-28 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                    data-testid="tool-response-default-chars"
                  />
                  <span className="text-[10px] text-muted-foreground">
                    {t("llmEditor.defaultMaxCharsHint", "(~12k tokens at 50000)")}
                  </span>
                </div>

                {/* Per-tool limits */}
                <div>
                  <label className="mb-1 block text-[10px] text-muted-foreground">
                    {t("llmEditor.perToolLimits", "Per-Tool Overrides")}
                  </label>
                  <div className="space-y-1.5">
                    {Object.entries(task.toolResponseLimits?.perToolLimits ?? {}).map(([tool, limit], i) => (
                      <div key={`trl-${i}`} className="flex items-center gap-1.5">
                        <input
                          type="text"
                          value={tool}
                          onChange={(e) => {
                            const entries = Object.entries(task.toolResponseLimits?.perToolLimits ?? {});
                            entries[i] = [e.target.value, limit];
                            const deduped = new Map(entries);
                            onChange({
                              ...task,
                              toolResponseLimits: {
                                ...task.toolResponseLimits,
                                perToolLimits: Object.fromEntries(deduped),
                              },
                            });
                          }}
                          readOnly={readOnly}
                          placeholder={t("llmEditor.toolName", "Tool name")}
                          className="h-7 w-40 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        <input
                          type="number"
                          value={limit}
                          onChange={(e) => {
                            const entries = Object.entries(task.toolResponseLimits?.perToolLimits ?? {});
                            entries[i] = [tool, parseInt(e.target.value, 10) || 0];
                            onChange({
                              ...task,
                              toolResponseLimits: {
                                ...task.toolResponseLimits,
                                perToolLimits: Object.fromEntries(entries),
                              },
                            });
                          }}
                          readOnly={readOnly}
                          placeholder="50000"
                          className="h-7 w-24 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        />
                        <span className="text-[10px] text-muted-foreground">chars</span>
                        {!readOnly && (
                          <button
                            type="button"
                            onClick={() => {
                              const entries = Object.entries(task.toolResponseLimits?.perToolLimits ?? {}).filter((_, j) => j !== i);
                              onChange({
                                ...task,
                                toolResponseLimits: {
                                  ...task.toolResponseLimits,
                                  perToolLimits: entries.length > 0 ? Object.fromEntries(entries) : undefined,
                                },
                              });
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
                          const existing = Object.keys(task.toolResponseLimits?.perToolLimits ?? {});
                          let key = "tool";
                          let n = 1;
                          while (existing.includes(key)) { key = `tool${n++}`; }
                          onChange({
                            ...task,
                            toolResponseLimits: {
                              ...task.toolResponseLimits,
                              perToolLimits: { ...(task.toolResponseLimits?.perToolLimits ?? {}), [key]: 50000 },
                            },
                          });
                        }}
                        className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                      >
                        <Plus className="h-3 w-3" />
                        {t("llmEditor.addToolLimit", "Add Tool Limit")}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </EditorSection>

          <TaskCascadeSection task={task} onChange={onChange} readOnly={readOnly} />


          <TaskMemorySection task={task} onChange={onChange} readOnly={readOnly} />


          <TaskRagSection task={task} onChange={onChange} readOnly={readOnly} />


          {/* ══════ Pre/Post Instructions ══════ */}
          <EditorSection
            label={t(
              "llmEditor.prePostInstructions",
              "Pre/Post Instructions"
            )}
            icon={FileCode}
            accent="text-teal-500"
            defaultOpen={!!(task.preRequest?.propertyInstructions?.length || task.postResponse?.propertyInstructions?.length || task.postResponse?.outputBuildInstructions?.length || task.postResponse?.qrBuildInstructions?.length)}
          >
            <div className="space-y-4" data-testid="pre-post-section">
              <p className="text-[10px] text-muted-foreground leading-relaxed">
                {t(
                  "llmEditor.prePostDesc",
                  "Configure property instructions that run before each LLM request, and output/property instructions that run after each response."
                )}
              </p>

              {/* Pre-Request */}
              <div>
                <h6 className="mb-1.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <ArrowRightLeft className="h-3 w-3" />
                  {t("llmEditor.preRequest", "Pre-Request")}
                </h6>
                <p className="mb-2 text-[10px] text-muted-foreground">
                  {t(
                    "llmEditor.preRequestDesc",
                    "Set or transform properties before the LLM call executes."
                  )}
                </p>
                <PropertyInstructionsEditor
                  instructions={task.preRequest?.propertyInstructions ?? []}
                  onChange={(list) =>
                    onChange({
                      ...task,
                      preRequest: list.length > 0
                        ? { ...task.preRequest, propertyInstructions: list }
                        : undefined,
                    })
                  }
                  readOnly={readOnly}
                />
              </div>

              {/* Post-Response */}
              <div className="space-y-3">
                <h6 className="mb-1 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  <FileOutput className="h-3 w-3" />
                  {t("llmEditor.postResponse", "Post-Response")}
                </h6>
                <p className="mb-2 text-[10px] text-muted-foreground">
                  {t(
                    "llmEditor.postResponseDesc",
                    "Process LLM output: set properties, build output messages, and generate quick replies."
                  )}
                </p>

                {/* Post-Response: Property Instructions */}
                <div>
                  <span className="mb-1 block text-[10px] font-medium text-muted-foreground">
                    {t("llmEditor.postPropertyInstructions", "Property Instructions")}
                  </span>
                  <PropertyInstructionsEditor
                    instructions={task.postResponse?.propertyInstructions ?? []}
                    onChange={(list) => {
                      const pr: LlmPostResponse = { ...task.postResponse, propertyInstructions: list };
                      const isEmpty = !pr.propertyInstructions?.length && !pr.outputBuildInstructions?.length && !pr.qrBuildInstructions?.length;
                      onChange({ ...task, postResponse: isEmpty ? undefined : pr });
                    }}
                    readOnly={readOnly}
                  />
                </div>

                {/* Post-Response: Output Build Instructions */}
                <div>
                  <span className="mb-1 flex items-center gap-1 text-[10px] font-medium text-muted-foreground">
                    <FileOutput className="h-2.5 w-2.5" />
                    {t("llmEditor.postOutputInstructions", "Output Build Instructions")}
                  </span>
                  <OutputBuildInstructionsEditor
                    instructions={task.postResponse?.outputBuildInstructions ?? []}
                    onChange={(list) => {
                      const pr: LlmPostResponse = { ...task.postResponse, outputBuildInstructions: list };
                      const isEmpty = !pr.propertyInstructions?.length && !pr.outputBuildInstructions?.length && !pr.qrBuildInstructions?.length;
                      onChange({ ...task, postResponse: isEmpty ? undefined : pr });
                    }}
                    readOnly={readOnly}
                  />
                </div>

                {/* Post-Response: Quick Reply Build Instructions */}
                <div>
                  <span className="mb-1 flex items-center gap-1 text-[10px] font-medium text-muted-foreground">
                    <MessageCircle className="h-2.5 w-2.5" />
                    {t("llmEditor.postQrInstructions", "Quick Reply Build Instructions")}
                  </span>
                  <QrBuildInstructionsEditor
                    instructions={task.postResponse?.qrBuildInstructions ?? []}
                    onChange={(list) => {
                      const pr: LlmPostResponse = { ...task.postResponse, qrBuildInstructions: list };
                      const isEmpty = !pr.propertyInstructions?.length && !pr.outputBuildInstructions?.length && !pr.qrBuildInstructions?.length;
                      onChange({ ...task, postResponse: isEmpty ? undefined : pr });
                    }}
                    readOnly={readOnly}
                  />
                </div>
              </div>
            </div>
          </EditorSection>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export interface LlmEditorProps {
  data: LangchainConfig;
  onChange: (data: LangchainConfig) => void;
  readOnly?: boolean;
}
/** @deprecated Use LlmEditorProps */
export type LangchainEditorProps = LlmEditorProps;

export function LlmEditor({
  data,
  onChange,
  readOnly,
}: LlmEditorProps) {
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
    <div className="space-y-6" data-testid="llm-editor">
      {/* Tasks list */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Zap className="h-4 w-4 text-primary" />
            {t("llmEditor.tasks", "LLM Tasks")}
          </h3>
          {!readOnly && (
            <button
              type="button"
              onClick={addTask}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-task-btn"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("llmEditor.addTask", "Add Task")}
            </button>
          )}
        </div>

        {(data.tasks ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("llmEditor.noTasks", "No LLM Tasks configured")}
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
