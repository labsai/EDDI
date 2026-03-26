import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  Plug,
  ShieldCheck,
  Zap,
} from "lucide-react";

// ─── Types matching McpCallsConfiguration backend model ──────────────────────

export interface McpCall {
  name?: string;
  actions?: string[];
  toolName?: string;
  toolArguments?: Record<string, unknown>;
  saveResponse?: boolean;
  responseObjectName?: string;
  preRequest?: unknown;
  postResponse?: unknown;
}

export interface McpCallsConfig {
  name?: string;
  mcpServerUrl?: string;
  transport?: string;
  apiKey?: string;
  timeoutMs?: number;
  toolsWhitelist?: string[];
  toolsBlacklist?: string[];
  mcpCalls?: McpCall[];
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function TagListInput({
  label,
  tags,
  onChange,
  readOnly,
  placeholder,
  testId,
}: {
  label: string;
  tags: string[];
  onChange: (t: string[]) => void;
  readOnly?: boolean;
  placeholder?: string;
  testId?: string;
}) {
  const [input, setInput] = useState("");

  const add = () => {
    const trimmed = input.trim();
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed]);
      setInput("");
    }
  };

  return (
    <div className="space-y-1.5" data-testid={testId}>
      <label className="block text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t, i) => (
          <span
            key={i}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
          >
            {t}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange(tags.filter((_, j) => j !== i))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                aria-label={`Remove ${t}`}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {tags.length === 0 && (
          <span className="text-xs text-muted-foreground italic">—</span>
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
                add();
              }
            }}
            placeholder={placeholder}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={add}
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
  icon: Icon,
  defaultOpen = true,
  children,
}: {
  label: string;
  icon?: React.ComponentType<{ className?: string }>;
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
        {Icon && <Icon className="h-3 w-3" />}
        {label}
      </button>
      {open && <div className="space-y-3">{children}</div>}
    </div>
  );
}

// ─── McpCall Editor ──────────────────────────────────────────────────────────

function McpCallEditor({
  call,
  onChange,
  onRemove,
  readOnly,
}: {
  call: McpCall;
  onChange: (c: McpCall) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  return (
    <div
      className="rounded-xl border border-border bg-card shadow-sm"
      data-testid="mcp-call-editor"
    >
      {/* Header */}
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
        <Zap className="h-4 w-4 text-amber-500" />
        <input
          type="text"
          value={call.name ?? ""}
          onChange={(e) => onChange({ ...call, name: e.target.value })}
          readOnly={readOnly}
          placeholder={t("mcpcallsEditor.callName", "Call name")}
          className="h-8 w-40 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        <span className="flex-1 truncate font-mono text-xs text-muted-foreground">
          → {call.toolName || "…"}
        </span>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("mcpcallsEditor.removeCall", "Remove Call")}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>

      {expanded && (
        <div className="space-y-3 border-t px-4 py-3">
          {/* Tool name */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.toolName", "Tool Name")}
            </label>
            <input
              type="text"
              value={call.toolName ?? ""}
              onChange={(e) =>
                onChange({ ...call, toolName: e.target.value })
              }
              readOnly={readOnly}
              placeholder="e.g. search_documents"
              className="h-8 w-full rounded-md border border-input bg-background px-3 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="tool-name-input"
            />
          </div>

          {/* Actions */}
          <TagListInput
            label={t("mcpcallsEditor.triggerActions", "Trigger Actions")}
            tags={call.actions ?? []}
            onChange={(a) => onChange({ ...call, actions: a })}
            readOnly={readOnly}
            placeholder="e.g. search, lookup"
            testId="call-actions"
          />

          {/* Tool arguments */}
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.toolArguments", "Tool Arguments")}
            </label>
            <div className="space-y-1.5">
              {Object.entries(call.toolArguments ?? {}).map(([k, v]) => (
                <div key={k} className="flex items-center gap-1.5">
                  <input
                    type="text"
                    value={k}
                    readOnly
                    className="h-7 w-28 rounded border border-input bg-muted px-2 text-xs text-foreground"
                  />
                  <input
                    type="text"
                    value={String(v ?? "")}
                    onChange={(e) =>
                      onChange({
                        ...call,
                        toolArguments: {
                          ...call.toolArguments,
                          [k]: e.target.value,
                        },
                      })
                    }
                    readOnly={readOnly}
                    placeholder="{{memory.input}}"
                    className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() => {
                        const next = { ...call.toolArguments };
                        delete next[k];
                        onChange({ ...call, toolArguments: next });
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
                  const idx = Object.keys(call.toolArguments ?? {}).length;
                  onChange({
                    ...call,
                    toolArguments: {
                      ...call.toolArguments,
                      [`arg${idx}`]: "",
                    },
                  });
                }}
                className="mt-1 inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                <Plus className="h-3 w-3" />
                {t("mcpcallsEditor.addArgument", "Add Argument")}
              </button>
            )}
          </div>

          {/* Response options */}
          <div className="grid grid-cols-2 gap-4">
            <label className="inline-flex items-center gap-2 text-xs text-foreground">
              <input
                type="checkbox"
                checked={call.saveResponse ?? false}
                onChange={(e) =>
                  onChange({ ...call, saveResponse: e.target.checked })
                }
                disabled={readOnly}
                className="h-3.5 w-3.5 rounded border-input accent-primary"
              />
              {t("mcpcallsEditor.saveResponse", "Save Response")}
            </label>
            {call.saveResponse && (
              <input
                type="text"
                value={call.responseObjectName ?? ""}
                onChange={(e) =>
                  onChange({ ...call, responseObjectName: e.target.value })
                }
                readOnly={readOnly}
                placeholder={t(
                  "mcpcallsEditor.responseObjectName",
                  "Response object name"
                )}
                className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Main Editor ─────────────────────────────────────────────────────────────

interface McpCallsEditorProps {
  data: McpCallsConfig;
  onChange: (data: McpCallsConfig) => void;
  readOnly?: boolean;
}

export function McpCallsEditor({
  data,
  onChange,
  readOnly,
}: McpCallsEditorProps) {
  const { t } = useTranslation();

  const update = useCallback(
    (patch: Partial<McpCallsConfig>) => onChange({ ...data, ...patch }),
    [data, onChange]
  );

  return (
    <div className="space-y-6" data-testid="mcpcalls-form-editor">
      {/* Server Connection */}
      <Section
        label={t("mcpcallsEditor.serverConnection", "Server Connection")}
        icon={Plug}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.displayName", "Display Name")}
            </label>
            <input
              type="text"
              value={data.name ?? ""}
              onChange={(e) => update({ name: e.target.value })}
              readOnly={readOnly}
              placeholder="e.g. My MCP Server"
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="mcp-name-input"
            />
          </div>
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.serverUrl", "MCP Server URL")}
            </label>
            <input
              type="url"
              value={data.mcpServerUrl ?? ""}
              onChange={(e) => update({ mcpServerUrl: e.target.value })}
              readOnly={readOnly}
              placeholder="http://localhost:7070/mcp"
              className="h-8 w-full rounded-md border border-input bg-background px-3 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="mcp-url-input"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.transport", "Transport")}
            </label>
            <select
              value={data.transport ?? "http"}
              onChange={(e) => update({ transport: e.target.value })}
              disabled={readOnly}
              className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
              data-testid="mcp-transport-select"
            >
              <option value="http">HTTP</option>
              <option value="sse">SSE</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.timeoutMs", "Timeout (ms)")}
            </label>
            <input
              type="number"
              value={data.timeoutMs ?? 30000}
              onChange={(e) =>
                update({ timeoutMs: parseInt(e.target.value, 10) || 30000 })
              }
              readOnly={readOnly}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div className="sm:col-span-2">
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("mcpcallsEditor.apiKey", "API Key / Vault Reference")}
            </label>
            <input
              type="text"
              value={data.apiKey ?? ""}
              onChange={(e) => update({ apiKey: e.target.value })}
              readOnly={readOnly}
              placeholder="${vault:my-mcp-key}"
              className="h-8 w-full rounded-md border border-input bg-background px-3 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="mcp-apikey-input"
            />
          </div>
        </div>
      </Section>

      {/* Tool Governance */}
      <Section
        label={t("mcpcallsEditor.toolGovernance", "Tool Governance")}
        icon={ShieldCheck}
        defaultOpen={
          (data.toolsWhitelist?.length ?? 0) > 0 ||
          (data.toolsBlacklist?.length ?? 0) > 0
        }
      >
        <p className="text-[10px] text-muted-foreground -mt-1 mb-2">
          {t(
            "mcpcallsEditor.governanceHint",
            "Control which tools are exposed. Whitelist = only these; Blacklist = exclude these."
          )}
        </p>
        <TagListInput
          label={t("mcpcallsEditor.whitelist", "Whitelist (only allow)")}
          tags={data.toolsWhitelist ?? []}
          onChange={(w) => update({ toolsWhitelist: w })}
          readOnly={readOnly}
          placeholder="tool_name"
          testId="tools-whitelist"
        />
        <TagListInput
          label={t("mcpcallsEditor.blacklist", "Blacklist (exclude)")}
          tags={data.toolsBlacklist ?? []}
          onChange={(b) => update({ toolsBlacklist: b })}
          readOnly={readOnly}
          placeholder="tool_name"
          testId="tools-blacklist"
        />
      </Section>

      {/* Pipeline Calls */}
      <Section
        label={t("mcpcallsEditor.pipelineCalls", "Pipeline Calls")}
        icon={Zap}
        defaultOpen={(data.mcpCalls?.length ?? 0) > 0}
      >
        <p className="text-[10px] text-muted-foreground -mt-1 mb-2">
          {t(
            "mcpcallsEditor.pipelineHint",
            "Deterministic, action-triggered tool calls (pipeline mode)."
          )}
        </p>
        <div className="space-y-3">
          {(data.mcpCalls ?? []).map((call, i) => (
            <McpCallEditor
              key={i}
              call={call}
              onChange={(c) => {
                const next = [...(data.mcpCalls ?? [])];
                next[i] = c;
                update({ mcpCalls: next });
              }}
              onRemove={() =>
                update({
                  mcpCalls: (data.mcpCalls ?? []).filter((_, j) => j !== i),
                })
              }
              readOnly={readOnly}
            />
          ))}
          {!readOnly && (
            <button
              type="button"
              onClick={() =>
                update({
                  mcpCalls: [
                    ...(data.mcpCalls ?? []),
                    {
                      name: "",
                      toolName: "",
                      actions: [],
                      toolArguments: {},
                      saveResponse: true,
                    },
                  ],
                })
              }
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-border px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:border-primary/50 transition-colors"
              data-testid="add-mcp-call"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("mcpcallsEditor.addCall", "Add MCP Call")}
            </button>
          )}
        </div>
      </Section>
    </div>
  );
}
