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
  ArrowRightLeft,
  FileOutput,
  MessageCircle,
  Timer,
  Shield,
} from "lucide-react";
import { ContentEditor } from "./content-editor";
import {
  discoverEndpoints,
  type DiscoverEndpointsResult,
} from "@/lib/api/openapi-discover";
import { isValidUrl } from "@/lib/utils";

// ─── Types matching HttpCallsConfiguration backend model ─────────────────────

export interface HttpCodeValidator {
  runOnHttpCode?: number[];
  skipOnHttpCode?: number[];
}

export interface PropertyInstruction {
  name?: string;
  valueString?: string;
  valueObject?: Record<string, unknown>;
  valueList?: unknown[];
  valueInt?: number;
  valueFloat?: number;
  valueBoolean?: boolean;
  scope?: "step" | "conversation" | "longTerm" | "secret";
  fromObjectPath?: string;
  toObjectPath?: string;
  convertToObject?: boolean;
  override?: boolean;
  runOnValidationError?: boolean;
  httpCodeValidator?: HttpCodeValidator;
}

export interface OutputBuildingInstruction {
  pathToTargetArray?: string;
  iterationObjectName?: string;
  templateFilterExpression?: string;
  outputType?: string;
  outputValue?: string;
  httpCodeValidator?: HttpCodeValidator;
}

export interface QuickRepliesBuildingInstruction {
  pathToTargetArray?: string;
  iterationObjectName?: string;
  templateFilterExpression?: string;
  quickReplyValue?: string;
  quickReplyExpressions?: string;
  httpCodeValidator?: HttpCodeValidator;
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
  retryApiCallInstruction?: unknown;
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
            {t("apiCallsEditor.noActions", "No actions")}
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
              "apiCallsEditor.actionPlaceholder",
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

// ─── HttpCodeValidator editor ────────────────────────────────────────────────

function HttpCodeValidatorEditor({
  validator,
  onChange,
  readOnly,
}: {
  validator?: HttpCodeValidator;
  onChange: (v: HttpCodeValidator | undefined) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [showValidator, setShowValidator] = useState(!!validator && (!!validator.runOnHttpCode?.length || !!validator.skipOnHttpCode?.length));

  const runCodes = validator?.runOnHttpCode ?? [];
  const skipCodes = validator?.skipOnHttpCode ?? [];

  const parseCodeList = (raw: string): number[] =>
    raw.split(",").map((s) => parseInt(s.trim(), 10)).filter((n) => !isNaN(n));

  if (!showValidator && !readOnly) {
    return (
      <button
        type="button"
        onClick={() => {
          setShowValidator(true);
          onChange({ runOnHttpCode: [], skipOnHttpCode: [] });
        }}
        className="inline-flex items-center gap-1 text-[10px] text-muted-foreground hover:text-foreground transition-colors"
      >
        <Shield className="h-2.5 w-2.5" />
        {t("apiCallsEditor.addHttpCodeValidator", "Add HTTP Code Filter")}
      </button>
    );
  }
  if (!showValidator && readOnly) return null;

  return (
    <div className="rounded-md border border-border/50 bg-muted/30 p-2 space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1">
          <Shield className="h-2.5 w-2.5" />
          {t("apiCallsEditor.httpCodeFilter", "HTTP Code Filter")}
        </span>
        {!readOnly && (
          <button
            type="button"
            onClick={() => { setShowValidator(false); onChange(undefined); }}
            className="rounded p-0.5 text-muted-foreground hover:text-destructive transition-colors"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.runOnCodes", "Run on codes")}
          </label>
          <input
            type="text"
            value={runCodes.join(", ")}
            onChange={(e) => onChange({ ...validator, runOnHttpCode: parseCodeList(e.target.value) })}
            readOnly={readOnly}
            placeholder="200, 201"
            className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.skipOnCodes", "Skip on codes")}
          </label>
          <input
            type="text"
            value={skipCodes.join(", ")}
            onChange={(e) => onChange({ ...validator, skipOnHttpCode: parseCodeList(e.target.value) })}
            readOnly={readOnly}
            placeholder="400, 500"
            className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
      </div>
    </div>
  );
}

// ─── PropertyInstruction row editor ──────────────────────────────────────────

const SCOPE_OPTIONS = ["step", "conversation", "longTerm", "secret"] as const;

function PropertyInstructionRow({
  instruction,
  onChange,
  onRemove,
  readOnly,
}: {
  instruction: PropertyInstruction;
  onChange: (i: PropertyInstruction) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [showAdvanced, setShowAdvanced] = useState(
    !!instruction.fromObjectPath || !!instruction.toObjectPath ||
    !!instruction.convertToObject || !!instruction.runOnValidationError
  );

  return (
    <div className="rounded-lg border border-border/60 bg-card/50 p-2.5 space-y-2" data-testid="property-instruction-row">
      <div className="flex items-center gap-1.5">
        <input
          type="text"
          value={instruction.name ?? ""}
          onChange={(e) => onChange({ ...instruction, name: e.target.value })}
          readOnly={readOnly}
          placeholder={t("apiCallsEditor.propName", "Property name")}
          className="h-7 w-32 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        <span className="text-xs text-muted-foreground">=</span>
        <input
          type="text"
          value={instruction.valueString ?? ""}
          onChange={(e) => onChange({ ...instruction, valueString: e.target.value })}
          readOnly={readOnly}
          placeholder={t("apiCallsEditor.propValue", "Value or template")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
        <select
          value={instruction.scope ?? "conversation"}
          onChange={(e) => onChange({ ...instruction, scope: e.target.value as PropertyInstruction["scope"] })}
          disabled={readOnly}
          className="h-7 rounded border border-input bg-background px-1.5 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
        >
          {SCOPE_OPTIONS.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <label className="inline-flex items-center gap-1 text-[10px] text-foreground whitespace-nowrap" title={t("apiCallsEditor.overrideTitle", "Override existing value")}>
          <input
            type="checkbox"
            checked={instruction.override ?? true}
            onChange={(e) => onChange({ ...instruction, override: e.target.checked })}
            disabled={readOnly}
            className="h-3 w-3 accent-primary"
          />
          {t("apiCallsEditor.override", "Override")}
        </label>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-0.5 text-muted-foreground hover:text-destructive transition-colors"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>

      {/* Advanced toggle */}
      <div>
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="text-[10px] text-muted-foreground hover:text-foreground transition-colors flex items-center gap-0.5"
        >
          {showAdvanced ? <ChevronDown className="h-2.5 w-2.5" /> : <ChevronRight className="h-2.5 w-2.5" />}
          {t("apiCallsEditor.advancedMapping", "Object Path Mapping")}
        </button>
        {showAdvanced && (
          <div className="mt-1.5 space-y-1.5 ps-3">
            <div className="grid grid-cols-2 gap-1.5">
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("apiCallsEditor.fromPath", "From Object Path")}
                </label>
                <input
                  type="text"
                  value={instruction.fromObjectPath ?? ""}
                  onChange={(e) => onChange({ ...instruction, fromObjectPath: e.target.value })}
                  readOnly={readOnly}
                  placeholder="response.data.items"
                  className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">
                  {t("apiCallsEditor.toPath", "To Object Path")}
                </label>
                <input
                  type="text"
                  value={instruction.toObjectPath ?? ""}
                  onChange={(e) => onChange({ ...instruction, toObjectPath: e.target.value })}
                  readOnly={readOnly}
                  placeholder="properties.result"
                  className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
            </div>
            <div className="flex items-center gap-3">
              <label className="inline-flex items-center gap-1 text-[10px] text-foreground">
                <input
                  type="checkbox"
                  checked={instruction.convertToObject ?? false}
                  onChange={(e) => onChange({ ...instruction, convertToObject: e.target.checked })}
                  disabled={readOnly}
                  className="h-3 w-3 accent-primary"
                />
                {t("apiCallsEditor.convertToObject", "Convert to Object")}
              </label>
              <label className="inline-flex items-center gap-1 text-[10px] text-foreground">
                <input
                  type="checkbox"
                  checked={instruction.runOnValidationError ?? false}
                  onChange={(e) => onChange({ ...instruction, runOnValidationError: e.target.checked })}
                  disabled={readOnly}
                  className="h-3 w-3 accent-primary"
                />
                {t("apiCallsEditor.runOnValidationError", "Run on Validation Error")}
              </label>
            </div>
            <HttpCodeValidatorEditor
              validator={instruction.httpCodeValidator}
              onChange={(v) => onChange({ ...instruction, httpCodeValidator: v })}
              readOnly={readOnly}
            />
          </div>
        )}
      </div>
    </div>
  );
}

// ─── PropertyInstructions list editor ────────────────────────────────────────

export function PropertyInstructionsEditor({
  instructions,
  onChange,
  readOnly,
}: {
  instructions: PropertyInstruction[];
  onChange: (list: PropertyInstruction[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="space-y-1.5">
      {instructions.length === 0 && (
        <p className="text-[10px] italic text-muted-foreground">
          {t("apiCallsEditor.noPropertyInstructions", "No property instructions")}
        </p>
      )}
      {instructions.map((inst, i) => (
        <PropertyInstructionRow
          key={i}
          instruction={inst}
          onChange={(updated) => {
            const list = [...instructions];
            list[i] = updated;
            onChange(list);
          }}
          onRemove={() => onChange(instructions.filter((_, j) => j !== i))}
          readOnly={readOnly}
        />
      ))}
      {!readOnly && (
        <button
          type="button"
          onClick={() => onChange([...instructions, { name: "", valueString: "", scope: "conversation", override: true }])}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <Plus className="h-3 w-3" />
          {t("apiCallsEditor.addPropertyInstruction", "Add Property Instruction")}
        </button>
      )}
    </div>
  );
}

// ─── OutputBuildingInstruction row editor ────────────────────────────────────

const OUTPUT_TYPES = ["text", "image", "other"] as const;

function OutputBuildInstructionRow({
  instruction,
  onChange,
  onRemove,
  readOnly,
}: {
  instruction: OutputBuildingInstruction;
  onChange: (i: OutputBuildingInstruction) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="rounded-lg border border-border/60 bg-card/50 p-2.5 space-y-2" data-testid="output-build-instruction-row">
      <div className="flex items-center gap-1.5">
        <div className="flex-1 grid grid-cols-3 gap-1.5">
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.pathToTargetArray", "Path to Target Array")}
            </label>
            <input
              type="text"
              value={instruction.pathToTargetArray ?? ""}
              onChange={(e) => onChange({ ...instruction, pathToTargetArray: e.target.value })}
              readOnly={readOnly}
              placeholder="aiOutput.outputs"
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.iterationObjName", "Iteration Object")}
            </label>
            <input
              type="text"
              value={instruction.iterationObjectName ?? "obj"}
              onChange={(e) => onChange({ ...instruction, iterationObjectName: e.target.value })}
              readOnly={readOnly}
              placeholder="obj"
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.templateFilter", "Filter Expression")}
            </label>
            <input
              type="text"
              value={instruction.templateFilterExpression ?? ""}
              onChange={(e) => onChange({ ...instruction, templateFilterExpression: e.target.value })}
              readOnly={readOnly}
              placeholder={t("apiCallsEditor.filterPlaceholder", "Optional filter...")}
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
        </div>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="mt-3 rounded p-0.5 text-muted-foreground hover:text-destructive transition-colors"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      <div className="grid grid-cols-3 gap-1.5">
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.outputType", "Output Type")}
          </label>
          <select
            value={instruction.outputType ?? "text"}
            onChange={(e) => onChange({ ...instruction, outputType: e.target.value })}
            disabled={readOnly}
            className="h-6 w-full rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          >
            {OUTPUT_TYPES.map((ot) => (
              <option key={ot} value={ot}>{ot}</option>
            ))}
          </select>
        </div>
        <div className="col-span-2">
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.outputValue", "Output Value")}
          </label>
          <input
            type="text"
            value={instruction.outputValue ?? ""}
            onChange={(e) => onChange({ ...instruction, outputValue: e.target.value })}
            readOnly={readOnly}
            placeholder="{aiOutput.htmlResponseText}"
            className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
      </div>
      <HttpCodeValidatorEditor
        validator={instruction.httpCodeValidator}
        onChange={(v) => onChange({ ...instruction, httpCodeValidator: v })}
        readOnly={readOnly}
      />
    </div>
  );
}

// ─── OutputBuildInstructions list editor ─────────────────────────────────────

export function OutputBuildInstructionsEditor({
  instructions,
  onChange,
  readOnly,
}: {
  instructions: OutputBuildingInstruction[];
  onChange: (list: OutputBuildingInstruction[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="space-y-1.5">
      {instructions.length === 0 && (
        <p className="text-[10px] italic text-muted-foreground">
          {t("apiCallsEditor.noOutputInstructions", "No output build instructions")}
        </p>
      )}
      {instructions.map((inst, i) => (
        <OutputBuildInstructionRow
          key={i}
          instruction={inst}
          onChange={(updated) => {
            const list = [...instructions];
            list[i] = updated;
            onChange(list);
          }}
          onRemove={() => onChange(instructions.filter((_, j) => j !== i))}
          readOnly={readOnly}
        />
      ))}
      {!readOnly && (
        <button
          type="button"
          onClick={() => onChange([...instructions, { iterationObjectName: "obj", outputType: "text", outputValue: "", httpCodeValidator: {} }])}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <Plus className="h-3 w-3" />
          {t("apiCallsEditor.addOutputInstruction", "Add Output Instruction")}
        </button>
      )}
    </div>
  );
}

// ─── QuickRepliesBuildingInstruction row editor ──────────────────────────────

function QrBuildInstructionRow({
  instruction,
  onChange,
  onRemove,
  readOnly,
}: {
  instruction: QuickRepliesBuildingInstruction;
  onChange: (i: QuickRepliesBuildingInstruction) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="rounded-lg border border-border/60 bg-card/50 p-2.5 space-y-2" data-testid="qr-build-instruction-row">
      <div className="flex items-center gap-1.5">
        <div className="flex-1 grid grid-cols-3 gap-1.5">
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.pathToTargetArray", "Path to Target Array")}
            </label>
            <input
              type="text"
              value={instruction.pathToTargetArray ?? ""}
              onChange={(e) => onChange({ ...instruction, pathToTargetArray: e.target.value })}
              readOnly={readOnly}
              placeholder="aiOutput.quickReplies"
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.iterationObjName", "Iteration Object")}
            </label>
            <input
              type="text"
              value={instruction.iterationObjectName ?? "obj"}
              onChange={(e) => onChange({ ...instruction, iterationObjectName: e.target.value })}
              readOnly={readOnly}
              placeholder="obj"
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("apiCallsEditor.templateFilter", "Filter Expression")}
            </label>
            <input
              type="text"
              value={instruction.templateFilterExpression ?? ""}
              onChange={(e) => onChange({ ...instruction, templateFilterExpression: e.target.value })}
              readOnly={readOnly}
              placeholder={t("apiCallsEditor.filterPlaceholder", "Optional filter...")}
              className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
        </div>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="mt-3 rounded p-0.5 text-muted-foreground hover:text-destructive transition-colors"
          >
            <X className="h-3 w-3" />
          </button>
        )}
      </div>
      <div className="grid grid-cols-2 gap-1.5">
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.qrValue", "Quick Reply Value")}
          </label>
          <input
            type="text"
            value={instruction.quickReplyValue ?? ""}
            onChange={(e) => onChange({ ...instruction, quickReplyValue: e.target.value })}
            readOnly={readOnly}
            placeholder="{obj.value}"
            className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <div>
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("apiCallsEditor.qrExpressions", "Quick Reply Expressions")}
          </label>
          <input
            type="text"
            value={instruction.quickReplyExpressions ?? ""}
            onChange={(e) => onChange({ ...instruction, quickReplyExpressions: e.target.value })}
            readOnly={readOnly}
            placeholder="{obj.expressions}"
            className="h-6 w-full rounded border border-input bg-background px-1.5 font-mono text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
      </div>
      <HttpCodeValidatorEditor
        validator={instruction.httpCodeValidator}
        onChange={(v) => onChange({ ...instruction, httpCodeValidator: v })}
        readOnly={readOnly}
      />
    </div>
  );
}

// ─── QuickRepliesBuildInstructions list editor ───────────────────────────────

export function QrBuildInstructionsEditor({
  instructions,
  onChange,
  readOnly,
}: {
  instructions: QuickRepliesBuildingInstruction[];
  onChange: (list: QuickRepliesBuildingInstruction[]) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();

  return (
    <div className="space-y-1.5">
      {instructions.length === 0 && (
        <p className="text-[10px] italic text-muted-foreground">
          {t("apiCallsEditor.noQrInstructions", "No quick reply build instructions")}
        </p>
      )}
      {instructions.map((inst, i) => (
        <QrBuildInstructionRow
          key={i}
          instruction={inst}
          onChange={(updated) => {
            const list = [...instructions];
            list[i] = updated;
            onChange(list);
          }}
          onRemove={() => onChange(instructions.filter((_, j) => j !== i))}
          readOnly={readOnly}
        />
      ))}
      {!readOnly && (
        <button
          type="button"
          onClick={() => onChange([...instructions, { pathToTargetArray: "", iterationObjectName: "obj", quickReplyValue: "", quickReplyExpressions: "", httpCodeValidator: {} }])}
          className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <Plus className="h-3 w-3" />
          {t("apiCallsEditor.addQrInstruction", "Add Quick Reply Instruction")}
        </button>
      )}
    </div>
  );
}

// ─── PreRequest editor ───────────────────────────────────────────────────────

function PreRequestEditor({
  preRequest,
  onChange,
  readOnly,
}: {
  preRequest?: HttpPreRequest;
  onChange: (pr: HttpPreRequest | undefined) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const data = preRequest ?? { propertyInstructions: [] };

  return (
    <div className="space-y-3">
      {/* Delay */}
      <div className="flex items-center gap-2">
        <Timer className="h-3.5 w-3.5 text-muted-foreground" />
        <label className="text-xs text-muted-foreground whitespace-nowrap">
          {t("apiCallsEditor.delayMs", "Delay (ms)")}
        </label>
        <input
          type="number"
          value={data.delayBeforeExecutingInMillis ?? 0}
          onChange={(e) => onChange({ ...data, delayBeforeExecutingInMillis: parseInt(e.target.value, 10) || 0 })}
          readOnly={readOnly}
          min={0}
          className="h-7 w-24 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
      </div>

      {/* Property Instructions */}
      <div>
        <h6 className="mb-1 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          <ArrowRightLeft className="h-3 w-3" />
          {t("apiCallsEditor.propertyInstructions", "Property Instructions")}
        </h6>
        <PropertyInstructionsEditor
          instructions={data.propertyInstructions ?? []}
          onChange={(list) => onChange({ ...data, propertyInstructions: list })}
          readOnly={readOnly}
        />
      </div>
    </div>
  );
}

// ─── PostResponse editor ─────────────────────────────────────────────────────

function PostResponseEditor({
  postResponse,
  onChange,
  readOnly,
}: {
  postResponse?: HttpPostResponse;
  onChange: (pr: HttpPostResponse | undefined) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const data = postResponse ?? { propertyInstructions: [], outputBuildInstructions: [], qrBuildInstructions: [] };

  return (
    <div className="space-y-4">
      {/* Property Instructions */}
      <div>
        <h6 className="mb-1 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          <ArrowRightLeft className="h-3 w-3" />
          {t("apiCallsEditor.propertyInstructions", "Property Instructions")}
        </h6>
        <PropertyInstructionsEditor
          instructions={data.propertyInstructions ?? []}
          onChange={(list) => onChange({ ...data, propertyInstructions: list })}
          readOnly={readOnly}
        />
      </div>

      {/* Output Build Instructions */}
      <div>
        <h6 className="mb-1 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          <FileOutput className="h-3 w-3" />
          {t("apiCallsEditor.outputBuildInstructions", "Output Build Instructions")}
        </h6>
        <OutputBuildInstructionsEditor
          instructions={data.outputBuildInstructions ?? []}
          onChange={(list) => onChange({ ...data, outputBuildInstructions: list })}
          readOnly={readOnly}
        />
      </div>

      {/* Quick Reply Build Instructions */}
      <div>
        <h6 className="mb-1 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          <MessageCircle className="h-3 w-3" />
          {t("apiCallsEditor.qrBuildInstructions", "Quick Reply Build Instructions")}
        </h6>
        <QrBuildInstructionsEditor
          instructions={data.qrBuildInstructions ?? []}
          onChange={(list) => onChange({ ...data, qrBuildInstructions: list })}
          readOnly={readOnly}
        />
      </div>
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
          placeholder={t("apiCallsEditor.callName", "Call Name")}
          className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="call-name-input"
        />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("apiCallsEditor.removeCall", "Remove Call")}
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
              {t("apiCallsEditor.callDescription", "Description")}
            </label>
            <input
              type="text"
              value={call.description ?? ""}
              onChange={(e) =>
                onChange({ ...call, description: e.target.value })
              }
              readOnly={readOnly}
              placeholder={t(
                "apiCallsEditor.descriptionPlaceholder",
                "Natural language description for LLM agents"
              )}
              className="h-8 w-full rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>

          {/* Actions */}
          <Section label={t("apiCallsEditor.actions", "Trigger Actions")}>
            <ActionTags
              actions={call.actions ?? []}
              onChange={(a) => onChange({ ...call, actions: a })}
              readOnly={readOnly}
            />
          </Section>

          {/* Request */}
          <Section label={t("apiCallsEditor.request", "Request")}>
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
                  "apiCallsEditor.pathPlaceholder",
                  "/api/endpoint"
                )}
                className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-xs font-mono text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                {t("apiCallsEditor.contentType", "Content Type")}
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
            label={t("apiCallsEditor.headers", "Headers")}
            defaultOpen={
              Object.keys(call.request.headers ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.request.headers ?? {}}
              onChange={(h) => updateRequest({ headers: h })}
              keyPlaceholder="Header name"
              valuePlaceholder="Header value"
              addLabel={t("apiCallsEditor.addHeader", "Add Header")}
              readOnly={readOnly}
            />
          </Section>

          {/* Query params */}
          <Section
            label={t("apiCallsEditor.queryParams", "Query Parameters")}
            defaultOpen={
              Object.keys(call.request.queryParams ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.request.queryParams ?? {}}
              onChange={(q) => updateRequest({ queryParams: q })}
              keyPlaceholder="Param name"
              valuePlaceholder="Param value"
              addLabel={t("apiCallsEditor.addQueryParam", "Add Query Param")}
              readOnly={readOnly}
            />
          </Section>

          {/* Body */}
          <Section
            label={t("apiCallsEditor.body", "Request Body")}
            defaultOpen={!!call.request.body}
          >
            <ContentEditor
              value={call.request.body ?? ""}
              onChange={(v) => updateRequest({ body: v })}
              readOnly={readOnly}
              language="json"
              label={t("apiCallsEditor.body", "Request Body")}
              placeholder={t(
                "apiCallsEditor.bodyPlaceholder",
                "JSON body template..."
              )}
              testId="request-body-editor"
            />
          </Section>

          {/* Parameters (for LLM agents) */}
          <Section
            label={t(
              "apiCallsEditor.parameters",
              "Parameters (for LLM agents)"
            )}
            defaultOpen={
              Object.keys(call.parameters ?? {}).length > 0
            }
          >
            <KvEditor
              entries={call.parameters ?? {}}
              onChange={(p) => onChange({ ...call, parameters: p })}
              keyPlaceholder={t("apiCallsEditor.paramName", "Param name")}
              valuePlaceholder={t(
                "apiCallsEditor.paramDescription",
                "Description"
              )}
              addLabel={t("apiCallsEditor.addParam", "Add Parameter")}
              readOnly={readOnly}
            />
          </Section>

          {/* Options */}
          <Section
            label={t("apiCallsEditor.options", "Options")}
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
                {t("apiCallsEditor.saveResponse", "Save Response")}
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
                    "apiCallsEditor.responseObjectName",
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
                    "apiCallsEditor.responseHeaderObjectName",
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
                {t("apiCallsEditor.fireAndForget", "Fire and Forget")}
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
                {t("apiCallsEditor.batchCalls", "Batch Calls")}
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
                    "apiCallsEditor.iterationObjectName",
                    "Iteration Object Name"
                  )}
                  className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              )}
            </div>
          </Section>

          {/* Pre-Request */}
          <Section
            label={t("apiCallsEditor.preRequest", "Pre-Request")}
            defaultOpen={
              !!(call.preRequest?.propertyInstructions?.length || call.preRequest?.delayBeforeExecutingInMillis)
            }
          >
            <PreRequestEditor
              preRequest={call.preRequest}
              onChange={(pr) => onChange({ ...call, preRequest: pr })}
              readOnly={readOnly}
            />
          </Section>

          {/* Post-Response */}
          <Section
            label={t("apiCallsEditor.postResponse", "Post-Response")}
            defaultOpen={
              !!(call.postResponse?.propertyInstructions?.length ||
                call.postResponse?.outputBuildInstructions?.length ||
                call.postResponse?.qrBuildInstructions?.length)
            }
          >
            <PostResponseEditor
              postResponse={call.postResponse}
              onChange={(pr) => onChange({ ...call, postResponse: pr })}
              readOnly={readOnly}
            />
          </Section>
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
          {t("apiCallsEditor.discoveredEndpoints", "Discovered Endpoints")} ({result.endpointCount})
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

export interface ApiCallsEditorProps {
  data: HttpCallsConfig;
  onChange: (data: HttpCallsConfig) => void;
  readOnly?: boolean;
}
/** @deprecated Use ApiCallsEditorProps */
export type HttpCallsEditorProps = ApiCallsEditorProps;

export function ApiCallsEditor({
  data,
  onChange,
  readOnly,
}: ApiCallsEditorProps) {
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
          : t("apiCallsEditor.discoveryError", "Could not parse OpenAPI spec");
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
    <div className="space-y-6" data-testid="apicalls-editor">
      {/* Target Server URL */}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-foreground">
          <Globe className="me-1.5 inline h-4 w-4 text-primary" />
          {t("apiCallsEditor.targetServerUrl", "Target Server URL")}
        </label>
        <input
          type="text"
          value={data.targetServerUrl ?? ""}
          onChange={(e) =>
            onChange({ ...data, targetServerUrl: e.target.value })
          }
          readOnly={readOnly}
          placeholder={t(
            "apiCallsEditor.targetServerUrlPlaceholder",
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
            {t("apiCallsEditor.openApiSpecUrl", "Import from OpenAPI Spec")}
          </label>
          <div className="flex gap-2">
            <input
              type="url"
              value={specUrl}
              onChange={(e) => setSpecUrl(e.target.value)}
              placeholder={t(
                "apiCallsEditor.specUrlPlaceholder",
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
              title={specUrl.trim() && !specUrlValid ? t("apiCallsEditor.invalidUrl", "Enter a valid http:// or https:// URL") : undefined}
              className="inline-flex h-8 items-center gap-1.5 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
              data-testid="discover-endpoints-btn"
            >
              {isDiscovering ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Search className="h-3.5 w-3.5" />
              )}
              {isDiscovering
                ? t("apiCallsEditor.discovering", "Parsing…")
                : t("apiCallsEditor.discoverEndpoints", "Discover Endpoints")}
            </button>
          </div>

          {/* Discovery loading */}
          {isDiscovering && (
            <div
              className="flex items-center gap-2 rounded-lg border border-border bg-card/50 px-3 py-4 text-xs text-muted-foreground"
              data-testid="discovery-loading"
            >
              <Loader2 className="h-4 w-4 animate-spin text-primary" />
              {t("apiCallsEditor.discovering", "Parsing…")}
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
                {t("apiCallsEditor.retry", "Retry")}
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
                  "apiCallsEditor.noEndpointsFound",
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
                  {t("apiCallsEditor.selectedCount", "selected")}
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
                  {t("apiCallsEditor.importAppend", "Append to Calls")}
                </button>
                <button
                  type="button"
                  onClick={handleImportReplace}
                  disabled={selected.size === 0}
                  className="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                  data-testid="import-replace-btn"
                >
                  <FileDown className="h-3 w-3" />
                  {t("apiCallsEditor.importReplace", "Replace All Calls")}
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
                      "apiCallsEditor.replaceConfirmMessage",
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
                    {t("apiCallsEditor.confirmReplace", "Yes, Replace All")}
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
            {t("apiCallsEditor.calls", "HTTP Calls")}
          </h3>
          {!readOnly && (
            <button
              type="button"
              onClick={addCall}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-call-btn"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("apiCallsEditor.addCall", "Add HTTP Call")}
            </button>
          )}
        </div>

        {(data.httpCalls ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("apiCallsEditor.noCalls", "No HTTP calls configured")}
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
