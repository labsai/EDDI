import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  Globe,
} from "lucide-react";

// ─── Types matching HttpCallsConfiguration backend model ─────────────────────

export interface PropertyInstruction {
  name?: string;
  value?: string;
  valueString?: string;
  scope?: string;
  fromObjectPath?: string;
  override?: boolean;
  httpCodeValidator?: { runOnHttpCode: number[] };
}

export interface OutputBuildingInstruction {
  action?: string;
  valueString?: string;
  httpCodeValidator?: { runOnHttpCode: number[] };
}

export interface QuickRepliesBuildingInstruction {
  action?: string;
  quickReplies?: Array<{
    value: string;
    expressions: string;
  }>;
  httpCodeValidator?: { runOnHttpCode: number[] };
}

export interface RetryInstruction {
  maxRetries?: number;
  exponentialBackoffDelayInMillis?: number;
  retryOnHttpCodes?: number[];
  responseValuePathMatchers?: Array<{
    valuePath?: string;
    contains?: string;
    equals?: string;
    trueIfNoMatch?: boolean;
  }>;
}

export interface HttpPreRequest {
  propertyInstructions?: PropertyInstruction[];
  batchRequests?: unknown;
  delayBeforeExecutingInMillis?: number;
}

export interface HttpPostResponse {
  propertyInstructions?: PropertyInstruction[];
  outputBuildInstructions?: OutputBuildingInstruction[];
  qrBuildInstructions?: QuickRepliesBuildingInstruction[];
  retryHttpCallInstruction?: RetryInstruction;
}

export interface HttpRequest {
  path: string;
  method: string;
  headers: Record<string, string>;
  queryParams: Record<string, string>;
  contentType: string;
  body: string;
}

export interface HttpCall {
  name: string;
  description?: string;
  parameters?: Record<string, string>;
  actions: string[];
  saveResponse?: boolean;
  responseObjectName?: string;
  responseHeaderObjectName?: string;
  fireAndForget?: boolean;
  isBatchCalls?: boolean;
  iterationObjectName?: string;
  preRequest?: HttpPreRequest;
  request: HttpRequest;
  postResponse?: HttpPostResponse;
}

export interface HttpCallsConfig {
  targetServerUrl?: string;
  httpCalls: HttpCall[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

const HTTP_METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"] as const;

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
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {actions.length === 0 && (
          <span className="text-xs text-muted-foreground italic">
            {t("httpcallsEditor.noActions", "No actions")}
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
              "httpcallsEditor.actionPlaceholder",
              "e.g. get_weather"
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

function KvEditor({
  entries,
  onChange,
  keyPlaceholder = "Key",
  valuePlaceholder = "Value",
  addLabel = "Add",
  readOnly,
}: {
  entries: Record<string, string>;
  onChange: (e: Record<string, string>) => void;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addLabel?: string;
  readOnly?: boolean;
}) {
  const pairs = Object.entries(entries ?? {});

  const update = (oldKey: string, newKey: string, val: string) => {
    const next: Record<string, string> = {};
    for (const [k, v] of pairs) {
      if (k === oldKey) next[newKey] = val;
      else next[k] = v;
    }
    onChange(next);
  };

  const remove = (key: string) => {
    const next = { ...entries };
    delete next[key];
    onChange(next);
  };

  const add = () => {
    const nextKey = `key${pairs.length}`;
    onChange({ ...entries, [nextKey]: "" });
  };

  return (
    <div className="space-y-1.5">
      {pairs.map(([k, v], i) => (
        <div key={i} className="flex items-center gap-1.5">
          <input
            type="text"
            value={k}
            onChange={(e) => update(k, e.target.value, v)}
            readOnly={readOnly}
            placeholder={keyPlaceholder}
            className="h-7 w-36 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <span className="text-xs text-muted-foreground">:</span>
          <input
            type="text"
            value={v}
            onChange={(e) => update(k, k, e.target.value)}
            readOnly={readOnly}
            placeholder={valuePlaceholder}
            className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          {!readOnly && (
            <button
              type="button"
              onClick={() => remove(k)}
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
          onClick={add}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <Plus className="h-3 w-3" />
          {addLabel}
        </button>
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

// ─── HttpCallEditor ──────────────────────────────────────────────────────────

function HttpCallEditor({
  call,
  onChange,
  onRemove,
  readOnly,
}: {
  call: HttpCall;
  onChange: (c: HttpCall) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  const updateRequest = useCallback(
    (patch: Partial<HttpRequest>) => {
      onChange({
        ...call,
        request: { ...call.request, ...patch },
      });
    },
    [call, onChange]
  );

  return (
    <div
      className="rounded-xl border border-border bg-card shadow-sm"
      data-testid="httpcall-editor"
    >
      {/* Call header */}
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
        {/* Method badge */}
        <span
          className={`inline-flex rounded px-1.5 py-0.5 text-[10px] font-bold tracking-wider ${
            call.request.method === "GET"
              ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
              : call.request.method === "POST"
                ? "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"
                : call.request.method === "PUT"
                  ? "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
                  : call.request.method === "DELETE"
                    ? "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400"
                    : "bg-muted text-muted-foreground"
          }`}
        >
          {call.request.method}
        </span>
        <input
          type="text"
          value={call.name ?? ""}
          onChange={(e) => onChange({ ...call, name: e.target.value })}
          readOnly={readOnly}
          placeholder={t("httpcallsEditor.callName", "Call Name")}
          className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="call-name-input"
        />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("httpcallsEditor.removeCall", "Remove Call")}
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
              {t("httpcallsEditor.callDescription", "Description")}
            </label>
            <input
              type="text"
              value={call.description ?? ""}
              onChange={(e) =>
                onChange({ ...call, description: e.target.value })
              }
              readOnly={readOnly}
              placeholder={t(
                "httpcallsEditor.descriptionPlaceholder",
                "Natural language description for LLM agents"
              )}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>

          {/* Actions */}
          <Section label={t("httpcallsEditor.actions", "Trigger Actions")}>
            <ActionTags
              actions={call.actions ?? []}
              onChange={(a) => onChange({ ...call, actions: a })}
              readOnly={readOnly}
            />
          </Section>

          {/* Request */}
          <Section label={t("httpcallsEditor.request", "Request")}>
            <div className="flex gap-2">
              <select
                value={call.request.method}
                onChange={(e) => updateRequest({ method: e.target.value })}
                disabled={readOnly}
                className="h-8 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                data-testid="method-select"
              >
                {HTTP_METHODS.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
              <input
                type="text"
                value={call.request.path ?? ""}
                onChange={(e) => updateRequest({ path: e.target.value })}
                readOnly={readOnly}
                placeholder={t(
                  "httpcallsEditor.pathPlaceholder",
                  "/api/endpoint"
                )}
                className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-xs font-mono text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                {t("httpcallsEditor.contentType", "Content Type")}
              </label>
              <input
                type="text"
                value={call.request.contentType ?? ""}
                onChange={(e) => updateRequest({ contentType: e.target.value })}
                readOnly={readOnly}
                placeholder="application/json"
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
          </Section>

          {/* Headers */}
          <Section
            label={t("httpcallsEditor.headers", "Headers")}
            defaultOpen={
              Object.keys(call.request.headers ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.request.headers ?? {}}
              onChange={(h) => updateRequest({ headers: h })}
              keyPlaceholder="Header name"
              valuePlaceholder="Header value"
              addLabel={t("httpcallsEditor.addHeader", "Add Header")}
              readOnly={readOnly}
            />
          </Section>

          {/* Query params */}
          <Section
            label={t("httpcallsEditor.queryParams", "Query Parameters")}
            defaultOpen={
              Object.keys(call.request.queryParams ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.request.queryParams ?? {}}
              onChange={(q) => updateRequest({ queryParams: q })}
              keyPlaceholder="Param name"
              valuePlaceholder="Param value"
              addLabel={t("httpcallsEditor.addQueryParam", "Add Query Param")}
              readOnly={readOnly}
            />
          </Section>

          {/* Body */}
          <Section
            label={t("httpcallsEditor.body", "Request Body")}
            defaultOpen={!!call.request.body}
          >
            <textarea
              value={call.request.body ?? ""}
              onChange={(e) => updateRequest({ body: e.target.value })}
              readOnly={readOnly}
              rows={4}
              placeholder={t(
                "httpcallsEditor.bodyPlaceholder",
                "JSON body template..."
              )}
              className="w-full rounded-md border border-input bg-background px-3 py-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </Section>

          {/* Parameters (for LLM agents) */}
          <Section
            label={t(
              "httpcallsEditor.parameters",
              "Parameters (for LLM agents)"
            )}
            defaultOpen={
              Object.keys(call.parameters ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.parameters ?? {}}
              onChange={(p) => onChange({ ...call, parameters: p })}
              keyPlaceholder={t("httpcallsEditor.paramName", "Param name")}
              valuePlaceholder={t(
                "httpcallsEditor.paramDescription",
                "Description"
              )}
              addLabel={t("httpcallsEditor.addParam", "Add Parameter")}
              readOnly={readOnly}
            />
          </Section>

          {/* Options */}
          <Section
            label={t("httpcallsEditor.options", "Options")}
            defaultOpen={false}
          >
            <div className="space-y-2">
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
                {t("httpcallsEditor.saveResponse", "Save Response")}
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
                    "httpcallsEditor.responseObjectName",
                    "Response Object Name"
                  )}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              )}
              {call.saveResponse && (
                <input
                  type="text"
                  value={call.responseHeaderObjectName ?? ""}
                  onChange={(e) =>
                    onChange({
                      ...call,
                      responseHeaderObjectName: e.target.value,
                    })
                  }
                  readOnly={readOnly}
                  placeholder={t(
                    "httpcallsEditor.responseHeaderObjectName",
                    "Response Header Object Name"
                  )}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              )}
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={call.fireAndForget ?? false}
                  onChange={(e) =>
                    onChange({ ...call, fireAndForget: e.target.checked })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                />
                {t("httpcallsEditor.fireAndForget", "Fire and Forget")}
              </label>
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={call.isBatchCalls ?? false}
                  onChange={(e) =>
                    onChange({ ...call, isBatchCalls: e.target.checked })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                />
                {t("httpcallsEditor.batchCalls", "Batch Calls")}
              </label>
              {call.isBatchCalls && (
                <input
                  type="text"
                  value={call.iterationObjectName ?? ""}
                  onChange={(e) =>
                    onChange({ ...call, iterationObjectName: e.target.value })
                  }
                  readOnly={readOnly}
                  placeholder={t(
                    "httpcallsEditor.iterationObjectName",
                    "Iteration Object Name"
                  )}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              )}
            </div>
          </Section>

          {/* Pre/Post Instructions (shown as JSON for now) */}
          {(call.preRequest || call.postResponse) && (
            <Section
              label={t(
                "httpcallsEditor.prePostInstructions",
                "Pre/Post Instructions"
              )}
              defaultOpen={false}
            >
              {call.preRequest && (
                <div>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("httpcallsEditor.preRequest", "Pre-Request")}
                  </span>
                  <pre className="max-h-32 overflow-auto rounded bg-muted p-2 text-[10px] text-foreground">
                    {JSON.stringify(call.preRequest, null, 2)}
                  </pre>
                </div>
              )}
              {call.postResponse && (
                <div>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("httpcallsEditor.postResponse", "Post-Response")}
                  </span>
                  <pre className="max-h-32 overflow-auto rounded bg-muted p-2 text-[10px] text-foreground">
                    {JSON.stringify(call.postResponse, null, 2)}
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

export interface HttpCallsEditorProps {
  data: HttpCallsConfig;
  onChange: (data: HttpCallsConfig) => void;
  readOnly?: boolean;
}

export function HttpCallsEditor({
  data,
  onChange,
  readOnly,
}: HttpCallsEditorProps) {
  const { t } = useTranslation();

  const addCall = useCallback(() => {
    const newCall: HttpCall = {
      name: "",
      actions: [],
      request: {
        path: "",
        method: "GET",
        headers: {},
        queryParams: {},
        contentType: "application/json",
        body: "",
      },
    };
    onChange({ ...data, httpCalls: [...(data.httpCalls ?? []), newCall] });
  }, [data, onChange]);

  return (
    <div className="space-y-6" data-testid="httpcalls-editor">
      {/* Target Server URL */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-foreground">
          <Globe className="me-1.5 inline h-4 w-4 text-primary" />
          {t("httpcallsEditor.targetServerUrl", "Target Server URL")}
        </label>
        <input
          type="text"
          value={data.targetServerUrl ?? ""}
          onChange={(e) =>
            onChange({ ...data, targetServerUrl: e.target.value })
          }
          readOnly={readOnly}
          placeholder={t(
            "httpcallsEditor.targetServerUrlPlaceholder",
            "https://api.example.com"
          )}
          className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          data-testid="server-url-input"
        />
      </div>

      {/* Calls list */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Globe className="h-4 w-4 text-primary" />
            {t("httpcallsEditor.calls", "HTTP Calls")}
          </h3>
          {!readOnly && (
            <button
              type="button"
              onClick={addCall}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-call-btn"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("httpcallsEditor.addCall", "Add HTTP Call")}
            </button>
          )}
        </div>

        {(data.httpCalls ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("httpcallsEditor.noCalls", "No HTTP calls configured")}
          </div>
        )}

        {(data.httpCalls ?? []).map((call, ci) => (
          <HttpCallEditor
            key={ci}
            call={call}
            onChange={(updated) => {
              const calls = [...(data.httpCalls ?? [])];
              calls[ci] = updated;
              onChange({ ...data, httpCalls: calls });
            }}
            onRemove={() =>
              onChange({
                ...data,
                httpCalls: (data.httpCalls ?? []).filter((_, j) => j !== ci),
              })
            }
            readOnly={readOnly}
          />
        ))}
      </div>
    </div>
  );
}
