import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  Globe,
  Search,
  Loader2,
  AlertCircle,
  RefreshCw,
  Check,
  FileDown,
  ShieldAlert,
} from "lucide-react";
import { ContentEditor } from "./content-editor";
import {
  discoverEndpoints,
  type DiscoverEndpointsResult,
} from "@/lib/api/openapi-discover";
import { isValidUrl } from "@/lib/utils";

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
            <ContentEditor
              value={call.request.body ?? ""}
              onChange={(v) => updateRequest({ body: v })}
              readOnly={readOnly}
              language="json"
              label={t("httpcallsEditor.body", "Request Body")}
              placeholder={t(
                "httpcallsEditor.bodyPlaceholder",
                "JSON body template..."
              )}
              testId="request-body-editor"
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

// ─── Discovered Endpoints Panel ──────────────────────────────────────────────

function DiscoveredEndpointsPanel({
  result,
  selected,
  onToggle,
  onToggleGroup,
  readOnly,
}: {
  result: DiscoverEndpointsResult;
  selected: Set<string>;
  onToggle: (key: string) => void;
  onToggleGroup: (group: string, allKeys: string[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div
      className="space-y-3 rounded-lg border border-border bg-card/50 p-3"
      data-testid="discovered-endpoints-panel"
    >
      <div className="flex items-center justify-between">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("httpcallsEditor.discoveredEndpoints", "Discovered Endpoints")} ({result.endpointCount})
        </p>
        <span className="text-[10px] text-muted-foreground">
          {result.title} — {result.baseUrl}
        </span>
      </div>
      {Object.entries(result.groups).map(([groupName, group]) => {
        const groupKeys = group.httpCalls.map(
          (c, i) => `${groupName}::${i}::${c.name}`
        );
        const allSelected = groupKeys.every((k) => selected.has(k));
        const someSelected = groupKeys.some((k) => selected.has(k));

        return (
          <div key={groupName} className="space-y-1">
            <div className="flex items-center gap-2">
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => onToggleGroup(groupName, groupKeys)}
                  className={`h-3.5 w-3.5 rounded border flex items-center justify-center transition-colors ${
                    allSelected
                      ? "bg-primary border-primary text-primary-foreground"
                      : someSelected
                        ? "bg-primary/30 border-primary"
                        : "border-input hover:border-primary"
                  }`}
                  data-testid={`group-toggle-${groupName}`}
                >
                  {allSelected && <Check className="h-2.5 w-2.5" />}
                </button>
              )}
              <span className="text-xs font-semibold text-foreground uppercase tracking-wider">
                {groupName}
              </span>
              <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                {group.httpCalls.length}
              </span>
            </div>
            <div className="grid gap-1 ps-5">
              {group.httpCalls.map((call, idx) => {
                const key = `${groupName}::${idx}::${call.name}`;
                const isSelected = selected.has(key);
                const method = call.request?.method?.toUpperCase() ?? "GET";
                const methodColor =
                  method === "GET"
                    ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
                    : method === "POST"
                      ? "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"
                      : method === "PUT"
                        ? "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400"
                        : method === "DELETE"
                          ? "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400"
                          : "bg-muted text-muted-foreground";

                return (
                  <label
                    key={key}
                    className={`group flex items-center gap-2 rounded-md border px-2.5 py-1.5 transition-colors cursor-pointer ${
                      isSelected
                        ? "border-primary/30 bg-primary/5"
                        : "border-border/50 bg-background hover:border-border"
                    }`}
                    data-testid="discovered-endpoint-item"
                  >
                    {!readOnly && (
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => onToggle(key)}
                        className="h-3.5 w-3.5 rounded border-input accent-primary"
                      />
                    )}
                    <span
                      className={`inline-flex rounded px-1.5 py-0.5 text-[10px] font-bold tracking-wider ${methodColor}`}
                    >
                      {method}
                    </span>
                    <span className="font-mono text-xs text-foreground">
                      {call.request?.path ?? ""}
                    </span>
                    {call.description && (
                      <span className="text-[10px] text-muted-foreground truncate">
                        — {call.description}
                      </span>
                    )}
                  </label>
                );
              })}
            </div>
          </div>
        );
      })}
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

  // Discovery state
  const [specUrl, setSpecUrl] = useState("");
  const [discoveryResult, setDiscoveryResult] =
    useState<DiscoverEndpointsResult | null>(null);
  const [isDiscovering, setIsDiscovering] = useState(false);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);
  const [hasDiscovered, setHasDiscovered] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [showReplaceConfirm, setShowReplaceConfirm] = useState(false);

  const specUrlValid = specUrl.trim().length > 0 && isValidUrl(specUrl.trim());

  const handleDiscover = useCallback(async () => {
    if (!specUrlValid) return;
    setIsDiscovering(true);
    setDiscoveryError(null);
    setDiscoveryResult(null);
    setSelected(new Set());
    try {
      const result = await discoverEndpoints(specUrl);
      setDiscoveryResult(result);
      // Select all by default, using collision-safe keys
      const allKeys = new Set<string>();
      for (const [groupName, group] of Object.entries(result.groups)) {
        group.httpCalls.forEach((call, i) => {
          allKeys.add(`${groupName}::${i}::${call.name}`);
        });
      }
      setSelected(allKeys);
      setHasDiscovered(true);
    } catch (err: unknown) {
      const msg =
        err && typeof err === "object" && "message" in err
          ? String((err as { message: string }).message)
          : t("httpcallsEditor.discoveryError", "Could not parse OpenAPI spec");
      setDiscoveryError(msg);
      setHasDiscovered(true);
    } finally {
      setIsDiscovering(false);
    }
  }, [specUrl, specUrlValid, t]);

  const toggleEndpoint = useCallback((key: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const toggleGroup = useCallback(
    (_group: string, allKeys: string[]) => {
      setSelected((prev) => {
        const next = new Set(prev);
        const allSelected = allKeys.every((k) => next.has(k));
        if (allSelected) {
          allKeys.forEach((k) => next.delete(k));
        } else {
          allKeys.forEach((k) => next.add(k));
        }
        return next;
      });
    },
    []
  );

  const getSelectedCalls = useCallback((): HttpCall[] => {
    if (!discoveryResult) return [];
    const calls: HttpCall[] = [];
    for (const [groupName, group] of Object.entries(discoveryResult.groups)) {
      group.httpCalls.forEach((call, i) => {
        if (selected.has(`${groupName}::${i}::${call.name}`)) {
          calls.push(call);
        }
      });
    }
    return calls;
  }, [discoveryResult, selected]);

  const handleImportAppend = useCallback(() => {
    const calls = getSelectedCalls();
    if (calls.length === 0) return;
    const updated: HttpCallsConfig = {
      ...data,
      httpCalls: [...(data.httpCalls ?? []), ...calls],
    };
    // Auto-fill targetServerUrl from discovered baseUrl if empty
    if (!data.targetServerUrl && discoveryResult?.baseUrl) {
      updated.targetServerUrl = discoveryResult.baseUrl;
    }
    onChange(updated);
    setDiscoveryResult(null);
    setHasDiscovered(false);
    setSpecUrl("");
  }, [getSelectedCalls, data, onChange, discoveryResult]);

  const handleImportReplace = useCallback(() => {
    const calls = getSelectedCalls();
    if (calls.length === 0) return;
    // Ask for confirmation if there are existing calls
    if ((data.httpCalls ?? []).length > 0 && !showReplaceConfirm) {
      setShowReplaceConfirm(true);
      return;
    }
    const updated: HttpCallsConfig = {
      ...data,
      httpCalls: calls,
    };
    if (!data.targetServerUrl && discoveryResult?.baseUrl) {
      updated.targetServerUrl = discoveryResult.baseUrl;
    }
    onChange(updated);
    setDiscoveryResult(null);
    setHasDiscovered(false);
    setSpecUrl("");
    setShowReplaceConfirm(false);
  }, [getSelectedCalls, data, onChange, discoveryResult, showReplaceConfirm]);

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

      {/* OpenAPI Discovery */}
      {!readOnly && (
        <div className="space-y-3 rounded-xl border border-dashed border-primary/30 bg-primary/5 p-4">
          <label className="mb-1 block text-sm font-medium text-foreground">
            <FileDown className="me-1.5 inline h-4 w-4 text-primary" />
            {t("httpcallsEditor.openApiSpecUrl", "Import from OpenAPI Spec")}
          </label>
          <div className="flex gap-2">
            <input
              type="url"
              value={specUrl}
              onChange={(e) => setSpecUrl(e.target.value)}
              placeholder={t(
                "httpcallsEditor.specUrlPlaceholder",
                "https://api.example.com/openapi.json"
              )}
              className="h-8 flex-1 rounded-md border border-input bg-background px-3 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="spec-url-input"
              onKeyDown={(e) => {
                if (e.key === "Enter" && specUrlValid && !isDiscovering) {
                  e.preventDefault();
                  handleDiscover();
                }
              }}
            />
            <button
              type="button"
              onClick={handleDiscover}
              disabled={isDiscovering || !specUrlValid}
              title={specUrl.trim() && !specUrlValid ? t("httpcallsEditor.invalidUrl", "Enter a valid http:// or https:// URL") : undefined}
              className="inline-flex h-8 items-center gap-1.5 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
              data-testid="discover-endpoints-btn"
            >
              {isDiscovering ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Search className="h-3.5 w-3.5" />
              )}
              {isDiscovering
                ? t("httpcallsEditor.discovering", "Parsing…")
                : t("httpcallsEditor.discoverEndpoints", "Discover Endpoints")}
            </button>
          </div>

          {/* Discovery loading */}
          {isDiscovering && (
            <div
              className="flex items-center gap-2 rounded-lg border border-border bg-card/50 px-3 py-4 text-xs text-muted-foreground"
              data-testid="discovery-loading"
            >
              <Loader2 className="h-4 w-4 animate-spin text-primary" />
              {t("httpcallsEditor.discovering", "Parsing…")}
            </div>
          )}

          {/* Discovery error */}
          {discoveryError && (
            <div
              className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-3"
              data-testid="discovery-error"
            >
              <AlertCircle className="h-4 w-4 shrink-0 text-destructive" />
              <p className="flex-1 text-xs text-destructive">
                {discoveryError}
              </p>
              <button
                type="button"
                onClick={handleDiscover}
                className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-destructive hover:bg-destructive/10 transition-colors"
              >
                <RefreshCw className="h-3 w-3" />
                {t("httpcallsEditor.retry", "Retry")}
              </button>
            </div>
          )}

          {/* No endpoints found */}
          {!isDiscovering &&
            hasDiscovered &&
            !discoveryError &&
            !discoveryResult && (
              <div
                className="rounded-lg border border-border bg-card/50 px-3 py-4 text-center text-xs text-muted-foreground"
                data-testid="discovery-empty"
              >
                {t(
                  "httpcallsEditor.noEndpointsFound",
                  "No endpoints found in the spec"
                )}
              </div>
            )}

          {/* Discovered endpoints */}
          {discoveryResult && (
            <>
              <DiscoveredEndpointsPanel
                result={discoveryResult}
                selected={selected}
                onToggle={toggleEndpoint}
                onToggleGroup={toggleGroup}
                readOnly={readOnly}
              />
              {/* Import buttons */}
              <div className="flex items-center gap-2">
                <span className="text-[10px] text-muted-foreground">
                  {selected.size}{" "}
                  {t("httpcallsEditor.selectedCount", "selected")}
                </span>
                <div className="flex-1" />
                <button
                  type="button"
                  onClick={handleImportAppend}
                  disabled={selected.size === 0}
                  className="inline-flex items-center gap-1.5 rounded-md border border-primary/50 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/10 disabled:opacity-50"
                  data-testid="import-append-btn"
                >
                  <Plus className="h-3 w-3" />
                  {t("httpcallsEditor.importAppend", "Append to Calls")}
                </button>
                <button
                  type="button"
                  onClick={handleImportReplace}
                  disabled={selected.size === 0}
                  className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                  data-testid="import-replace-btn"
                >
                  <FileDown className="h-3 w-3" />
                  {t("httpcallsEditor.importReplace", "Replace All Calls")}
                </button>
              </div>

              {/* Replace confirmation inline */}
              {showReplaceConfirm && (
                <div
                  className="flex items-center gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2.5"
                  data-testid="replace-confirm"
                >
                  <ShieldAlert className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
                  <p className="flex-1 text-xs text-amber-700 dark:text-amber-300">
                    {t(
                      "httpcallsEditor.replaceConfirmMessage",
                      `This will replace ${(data.httpCalls ?? []).length} existing call(s). Are you sure?`
                    )}
                  </p>
                  <button
                    type="button"
                    onClick={() => setShowReplaceConfirm(false)}
                    className="rounded px-2 py-1 text-xs font-medium text-amber-700 dark:text-amber-300 hover:bg-amber-500/20 transition-colors"
                  >
                    {t("common.cancel", "Cancel")}
                  </button>
                  <button
                    type="button"
                    onClick={handleImportReplace}
                    className="rounded bg-amber-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-amber-700 transition-colors"
                    data-testid="replace-confirm-yes"
                  >
                    {t("httpcallsEditor.confirmReplace", "Yes, Replace All")}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

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
