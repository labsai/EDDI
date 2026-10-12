import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { ActionTags } from "./action-tags";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  GitBranch,
} from "lucide-react";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { nextFreeKey } from "./editor-value-utils";

// ─── Types matching RulesConfiguration backend model ──────────────────────

export interface RuleCondition {
  type: string;
  configs?: Record<string, string>;
  conditions?: RuleCondition[] | null;
}

export interface Rule {
  name: string;
  actions: string[];
  conditions: RuleCondition[];
}

/** A rule group as this editor works with it, and as it saves it. */
export interface RulesGroup {
  name: string;
  executionStrategy?: string;
  behaviorRules: Rule[];
}

export interface RulesConfig {
  appendActions?: boolean;
  expressionsAsActions?: boolean;
  behaviorGroups: RulesGroup[];
}

/**
 * A rule group as the *server* may send it — which is not the same shape.
 *
 * `RuleGroupConfiguration`'s accessors are `getRules`/`setRules`, so Jackson
 * emitted `rules` while every authored artefact — the shipped reference config,
 * the ZIP fixtures, the docs, and this editor — says `behaviorRules`. The
 * backend accepted both on write, so the mismatch only ever bit on reads: a rule
 * set posted as `behaviorRules` came back as `rules`, and this editor rendered
 * every group as "No rules in this group" no matter what it held. Our own MSW
 * handlers returned `behaviorRules`, so the suite agreed with the fiction rather
 * than the server.
 *
 * The point of naming this shape rather than widening `RulesGroup`: the original
 * bug was a type that described what we *wished* the server sent. Modelling the
 * wire separately means the compiler, not a reader, is what stops the two being
 * confused — `normalizeRulesConfig` is the only bridge between them.
 *
 * EDDI now emits `behaviorRules` too (labsai/EDDI#717). This stays so a current
 * Manager keeps working against an older EDDI, and can go once no supported
 * backend emits `rules`.
 */
export interface RulesGroupInput {
  name: string;
  executionStrategy?: string;
  behaviorRules?: Rule[];
  rules?: Rule[];
}

/** A rule set as the server may send it. See {@link RulesGroupInput}. */
export interface RulesConfigInput {
  appendActions?: boolean;
  expressionsAsActions?: boolean;
  behaviorGroups?: RulesGroupInput[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

// Backend condition IDs (ai.labs.eddi.modules.rules.impl.conditions.*.ID).
// RuleDeserialization matches these EXACTLY (case-sensitive) — a wrong value
// throws "No condition for type ..." and yields an unloadable ruleset.
const CONDITION_TYPES = [
  "inputmatcher",
  "actionmatcher",
  "contextmatcher",
  "contentTypeMatcher",
  "occurrence",
  "dynamicvaluematcher",
  "sizematcher",
  "dependency",
  "negation",
  "connector",
  "deploymentContext",
  "capabilityMatch",
] as const;

// Backend RuleGroup.ExecutionStrategy enum — the ONLY two values valueOf() accepts.
const EXECUTION_STRATEGIES = ["executeUntilFirstSuccess", "executeAll"] as const;
type ExecutionStrategy = (typeof EXECUTION_STRATEGIES)[number];
const STRATEGY_LABELS: Record<ExecutionStrategy, string> = {
  executeUntilFirstSuccess: "Until first success",
  executeAll: "Execute all",
};
const isKnownStrategy = (v: string | undefined): v is ExecutionStrategy =>
  (EXECUTION_STRATEGIES as readonly string[]).includes(v ?? "");
const isKnownCondition = (v: string | undefined): boolean =>
  (CONDITION_TYPES as readonly string[]).includes(v ?? "");

/**
 * Config values the engine accepts from a closed list, per condition type and
 * key. `occurrence` is read by `BaseMatcher` (inputmatcher, actionmatcher) and
 * `contextType` by `ContextMatcher`; both throw on any other value, so a free
 * text box only invited a typo that fails at deploy time. `strategy` of
 * `capabilityMatch` is a template too, so a custom value stays selectable.
 */
const CONFIG_OPTIONS: Record<string, Record<string, readonly string[]>> = {
  inputmatcher: { occurrence: ["currentStep", "lastStep", "anyStep", "never"] },
  actionmatcher: { occurrence: ["currentStep", "lastStep", "anyStep", "never"] },
  contextmatcher: { contextType: ["expressions", "object", "string"] },
  capabilityMatch: { strategy: ["highest_confidence", "round_robin", "random", "all"] },
};

/** Preset configs for a freshly chosen condition type. */
function defaultConfigsFor(type: string): Record<string, string> {
  switch (type) {
    case "inputmatcher":
      return { expressions: "", occurrence: "currentStep" };
    case "actionmatcher":
      return { actions: "", occurrence: "currentStep" };
    case "occurrence":
      return { maxTimesOccurred: "1", behaviorRuleName: "" };
    case "deploymentContext":
      return { when: "production", tagMatches: "" };
    case "capabilityMatch":
      return { skill: "", strategy: "highest_confidence", attributes: "" };
    case "contextmatcher":
      return { context: "", contextType: "string", string: "" };
    case "contentTypeMatcher":
      return { mimeType: "", minCount: "1" };
    case "sizematcher":
      // SizeMatcher parses every min/max/equal key it finds with
      // Integer.parseInt, so an empty "min" or "max" failed the save with a
      // 400. Preset only a bound that means something: "at least one".
      return { valuePath: "", min: "1" };
    case "dependency":
      return { reference: "" };
    default:
      // negation, connector, dynamicvaluematcher — no preset configs
      return {};
  }
}

/** Whether the user has put anything into `condition` beyond its type's presets. */
function hasUserConfigs(condition: RuleCondition): boolean {
  const defaults = defaultConfigsFor(condition.type);
  const configs = Object.entries(condition.configs ?? {});
  const customised = configs.some(([k, v]) => v !== "" && defaults[k] !== v);
  const nested = (condition.conditions ?? []).length > 0;
  return customised || nested;
}

/** Whether `condition`, or anything nested in it, is an actionmatcher on `lastStep`. */
function hasLastStepActionMatcher(condition: RuleCondition): boolean {
  if (condition.type === "actionmatcher" && condition.configs?.occurrence?.trim() === "lastStep") {
    return true;
  }
  return (condition.conditions ?? []).some(hasLastStepActionMatcher);
}

/** An actionmatcher whose `actions` is a comma list: the engine reads it as AND, not OR. */
function isCommaActionMatcher(condition: RuleCondition): boolean {
  return condition.type === "actionmatcher" && (condition.configs?.actions ?? "").includes(",");
}

// ─── Sub-components ──────────────────────────────────────────────────────────

function Warning({ children, testId }: { children: React.ReactNode; testId?: string }) {
  return (
    <p
      className="flex items-start gap-1.5 rounded-md bg-warning/10 px-2 py-1.5 text-[11px] text-foreground"
      data-testid={testId}
    >
      <AlertTriangle className="mt-px h-3 w-3 shrink-0 text-warning" aria-hidden="true" />
      <span>{children}</span>
    </p>
  );
}

/** Up / down buttons that reorder an item in a list whose order matters. */
function MoveButtons({
  onUp,
  onDown,
  label,
  testId,
}: {
  onUp?: () => void;
  onDown?: () => void;
  label: string;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <>
      <button
        type="button"
        onClick={onUp}
        disabled={!onUp}
        aria-label={t("rulesEditor.moveUp", "Move {{name}} up", { name: label })}
        className="rounded p-1 text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30"
        data-testid={`${testId}-up`}
      >
        <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onClick={onDown}
        disabled={!onDown}
        aria-label={t("rulesEditor.moveDown", "Move {{name}} down", { name: label })}
        className="rounded p-1 text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30"
        data-testid={`${testId}-down`}
      >
        <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </>
  );
}

/** `items` with the entry at `from` moved to `to`. */
function moved<T>(items: readonly T[], from: number, to: number): T[] {
  const copy = [...items];
  const [item] = copy.splice(from, 1);
  copy.splice(to, 0, item as T);
  return copy;
}

function KeyValueRow({
  configKey,
  value,
  onKeyChange,
  onValueChange,
  onRemove,
  readOnly,
  valuePlaceholder,
  invalidMessage,
  options,
}: {
  configKey: string;
  value: string;
  onKeyChange: (k: string) => void;
  onValueChange: (v: string) => void;
  onRemove: () => void;
  readOnly?: boolean;
  valuePlaceholder?: string;
  /** Shown under the row, and marks the value invalid, when set. */
  invalidMessage?: string;
  /** The values the engine accepts for this key; renders a select instead of free text. */
  options?: readonly string[];
}) {
  const { t } = useTranslation();
  return (
    <div>
    <div className="flex items-center gap-1.5">
      <input
        type="text"
        value={configKey}
        onChange={(e) => onKeyChange(e.target.value)}
        readOnly={readOnly}
        placeholder={t("rulesEditor.configKey", "Key")}
        aria-label={t("rulesEditor.configKey", "Key")}
        className="h-7 w-28 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
      <span className="text-xs text-muted-foreground">=</span>
      {options ? (
        <select
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          disabled={readOnly}
          aria-label={t("rulesEditor.configValueOf", "Value of {{key}}", { key: configKey })}
          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid={`config-select-${configKey}`}
        >
          {!options.includes(value) && (
            <option value={value}>{value === "" ? "—" : value}</option>
          )}
          {options.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </select>
      ) : (
        <input
          type="text"
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          readOnly={readOnly}
          placeholder={valuePlaceholder ?? t("rulesEditor.configValue", "Value")}
          aria-label={t("rulesEditor.configValueOf", "Value of {{key}}", { key: configKey })}
          aria-invalid={invalidMessage ? true : undefined}
          className={`h-7 flex-1 rounded border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${
            invalidMessage ? "border-destructive" : "border-input"
          }`}
        />
      )}
      {!readOnly && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={t("rulesEditor.removeConfig", "Remove {{key}}", { key: configKey })}
          className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
        >
          <X className="h-3 w-3" aria-hidden="true" />
        </button>
      )}
    </div>
    {invalidMessage && (
      <p className="mt-0.5 ps-1 text-[10px] text-destructive" role="alert">
        {invalidMessage}
      </p>
    )}
    </div>
  );
}

/**
 * SizeMatcher parses every `min` / `max` / `equal` key it finds with
 * `Integer.parseInt`, so an empty or non-numeric bound fails the whole save
 * with a 400. `-1` is its own "no bound" value: a cleared bound is stored as
 * `-1` and shown as an empty field.
 */
const SIZE_BOUND_KEYS = new Set(["min", "max", "equal"]);
const NO_SIZE_BOUND = "-1";

/**
 * Whether `Integer.parseInt` accepts `value` as stored: no surrounding
 * whitespace (it does not trim) and inside the Java `int` range.
 */
function isJavaInt(value: string): boolean {
  if (!/^-?\d+$/.test(value)) return false;
  const n = Number(value);
  return n >= -2147483648 && n <= 2147483647;
}

function ConditionEditor({
  condition,
  onChange,
  onRemove,
  readOnly,
  depth = 0,
}: {
  condition: RuleCondition;
  onChange: (c: RuleCondition) => void;
  onRemove: () => void;
  readOnly?: boolean;
  depth?: number;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);
  const hasNested =
    condition.type === "negation" || condition.type === "connector";

  const configEntries = Object.entries(condition.configs ?? {});

  const isSizeBound = (key: string) =>
    condition.type === "sizematcher" && SIZE_BOUND_KEYS.has(key);
  /** What is written for a value typed into `key`. */
  const toStored = (key: string, value: string) => {
    if (!isSizeBound(key)) return value;
    // SizeMatcher does not trim, so " 2 " would fail the save.
    const trimmed = value.trim();
    return trimmed === "" ? NO_SIZE_BOUND : trimmed;
  };

  const updateConfig = (key: string, value: string) => {
    onChange({
      ...condition,
      configs: { ...condition.configs, [key]: toStored(key, value) },
    });
  };

  const removeConfigEntry = (key: string) => {
    const next = { ...condition.configs };
    delete next[key];
    onChange({ ...condition, configs: next });
  };

  const renameConfigKey = (oldKey: string, newKey: string) => {
    if (oldKey === newKey) return;
    const entries = Object.entries(condition.configs ?? {});
    const updated = Object.fromEntries(
      entries.map(([k, v]) => (k === oldKey ? [newKey, toStored(newKey, v)] : [k, v]))
    );
    onChange({ ...condition, configs: updated });
  };

  const addConfigEntry = () => {
    // The first free key<n>: counting entries handed out a taken name once a
    // lower one had been deleted, overwriting that entry's value.
    const nextKey = nextFreeKey(Object.keys(condition.configs ?? {}), "key");
    onChange({
      ...condition,
      configs: { ...condition.configs, [nextKey]: "" },
    });
  };

  const addNestedCondition = () => {
    onChange({
      ...condition,
      conditions: [
        ...(condition.conditions ?? []),
        { type: "inputmatcher", configs: { expressions: "", occurrence: "currentStep" } },
      ],
    });
  };

  const [pendingType, setPendingType] = useState<string | null>(null);

  const applyType = (newType: string) => {
    onChange({ ...condition, type: newType, configs: defaultConfigsFor(newType) });
  };

  /** Switching type replaces every config, so ask first if the user filled any in. */
  const handleTypeChange = (newType: string) => {
    if (newType === condition.type) return;
    if (hasUserConfigs(condition)) setPendingType(newType);
    else applyType(newType);
  };

  return (
    <div
      className={`rounded-lg border bg-card ${depth > 0 ? "border-dashed border-muted-foreground/30" : "border-border"}`}
      data-testid="condition-editor"
    >
      <div className="flex items-center gap-2 p-2">
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          aria-label={t("rulesEditor.toggleCondition", "Show or hide condition details")}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors"
        >
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5" />
          )}
        </button>
        <select
          value={condition.type}
          onChange={(e) => handleTypeChange(e.target.value)}
          disabled={readOnly}
          aria-label={t("rulesEditor.conditionType", "Condition type")}
          className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid="condition-type-select"
        >
          {!isKnownCondition(condition.type) && condition.type && (
            <option value={condition.type} disabled>
              {condition.type} {t("rulesEditor.invalidValue", "(invalid)")}
            </option>
          )}
          {CONDITION_TYPES.map((ct) => (
            <option key={ct} value={ct}>
              {ct === "deploymentContext"
                ? t("rulesEditor.condDeploymentContext", "deploymentContext")
                : ct === "capabilityMatch"
                  ? t("rulesEditor.condCapabilityMatch", "capabilityMatch")
                  : ct}
            </option>
          ))}
        </select>
        <span className="flex-1" />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("rulesEditor.removeCondition", "Remove")}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      {expanded && (
        <div className="space-y-2 px-3 pb-3">
          {/* Config key-value pairs */}
          {configEntries.map(([k, v], i) => (
            <KeyValueRow
              key={i}
              configKey={k}
              value={isSizeBound(k) && v === NO_SIZE_BOUND ? "" : v}
              valuePlaceholder={isSizeBound(k) ? t("rulesEditor.sizeNoBound", "no limit") : undefined}
              invalidMessage={
                isSizeBound(k) && v !== "" && !isJavaInt(v)
                  ? t("rulesEditor.sizeBoundInvalid", "Must be a whole number from -2147483648 to 2147483647, or empty for no limit.")
                  : undefined
              }
              options={CONFIG_OPTIONS[condition.type]?.[k]}
              onKeyChange={(nk) => renameConfigKey(k, nk)}
              onValueChange={(nv) => updateConfig(k, nv)}
              onRemove={() => removeConfigEntry(k)}
              readOnly={readOnly}
            />
          ))}
          {isCommaActionMatcher(condition) && (
            <Warning testId="comma-actionmatcher-warning">
              {t(
                "rulesEditor.commaActionsWarning",
                "A comma list here means AND: every listed action must have occurred, in that order. To match any one of several actions, add one condition per action under an OR connector, or emit a shared action."
              )}
            </Warning>
          )}
          {!readOnly && (
            <button
              type="button"
              onClick={addConfigEntry}
              className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            >
              <Plus className="h-3 w-3" />
              {t("rulesEditor.addConfig", "Add config")}
            </button>
          )}

          {/* Nested conditions (for negation / connector) */}
          {hasNested && (
            <div className="mt-2 space-y-2 ps-3 border-s-2 border-muted">
              <span className="text-xs font-medium text-muted-foreground">
                {t("rulesEditor.nestedConditions", "Nested Conditions")}
              </span>
              {(condition.conditions ?? []).map((nc, ni) => (
                <ConditionEditor
                  key={ni}
                  condition={nc}
                  onChange={(updated) => {
                    const copy = [...(condition.conditions ?? [])];
                    copy[ni] = updated;
                    onChange({ ...condition, conditions: copy });
                  }}
                  onRemove={() => {
                    onChange({
                      ...condition,
                      conditions: (condition.conditions ?? []).filter(
                        (_, j) => j !== ni
                      ),
                    });
                  }}
                  readOnly={readOnly}
                  depth={depth + 1}
                />
              ))}
              {!readOnly && (
                <button
                  type="button"
                  onClick={addNestedCondition}
                  className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                >
                  <Plus className="h-3 w-3" />
                  {t("rulesEditor.addCondition", "Add Condition")}
                </button>
              )}
            </div>
          )}
        </div>
      )}
      <AlertDialog
        open={pendingType !== null}
        onOpenChange={(open) => {
          if (!open) setPendingType(null);
        }}
        title={t("rulesEditor.changeTypeTitle", "Change condition type?")}
        description={t(
          "rulesEditor.changeTypeDescription",
          "Switching to {{type}} replaces this condition's settings with that type's defaults. The values you entered are lost.",
          { type: pendingType ?? "" }
        )}
        confirmLabel={t("rulesEditor.changeTypeConfirm", "Change type")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="warning"
        onConfirm={() => {
          if (pendingType) applyType(pendingType);
          setPendingType(null);
        }}
      />
    </div>
  );
}

function RuleEditor({
  rule,
  onChange,
  onRemove,
  onMoveUp,
  onMoveDown,
  readOnly,
  actionSuggestions,
}: {
  rule: Rule;
  onChange: (r: Rule) => void;
  onRemove: () => void;
  /** Absent at the edge of the list. Order matters: the first match wins. */
  onMoveUp?: () => void;
  onMoveDown?: () => void;
  readOnly?: boolean;
  /** Actions emitted by rules elsewhere in this config. */
  actionSuggestions?: readonly string[];
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  return (
    <div
      className="rounded-lg border border-border bg-card shadow-sm"
      data-testid="rule-editor"
    >
      {/* Rule header */}
      <div className="flex items-center gap-2 p-3">
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          aria-label={t("rulesEditor.toggleRule", "Show or hide rule details")}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors"
        >
          {expanded ? (
            <ChevronDown className="h-4 w-4" />
          ) : (
            <ChevronRight className="h-4 w-4" />
          )}
        </button>
        <input
          type="text"
          value={rule.name}
          aria-label={t("rulesEditor.ruleName", "Rule Name")}
          onChange={(e) => onChange({ ...rule, name: e.target.value })}
          readOnly={readOnly}
          placeholder={t("rulesEditor.ruleName", "Rule Name")}
          className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="rule-name-input"
        />
        {!readOnly && (
          <>
            <MoveButtons
              onUp={onMoveUp}
              onDown={onMoveDown}
              label={rule.name || t("rulesEditor.thisRule", "this rule")}
              testId="rule-move"
            />
            <button
              type="button"
              onClick={onRemove}
              className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
              aria-label={t("rulesEditor.removeRule", "Remove Rule")}
            >
              <Trash2 className="h-4 w-4" />
            </button>
          </>
        )}
      </div>
      {!(rule.conditions ?? []).some(hasLastStepActionMatcher) && (
        <div className="px-3 pb-3">
          <Warning testId="no-lastStep-actionmatcher-warning">
            {t(
              "rulesEditor.noLastStepWarning",
              "No actionmatcher on lastStep: this rule can fire on any step of the conversation, not just after the action you expect. Add an actionmatcher condition with occurrence lastStep."
            )}
          </Warning>
        </div>
      )}

      {expanded && (
        <div className="space-y-4 border-t px-4 py-3">
          {/* Actions */}
          <div>
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("rulesEditor.actions", "Actions")}
            </h5>
            <ActionTags
              actions={rule.actions}
              onChange={(a) => onChange({ ...rule, actions: a })}
              readOnly={readOnly}
              suggestions={actionSuggestions}
              placeholder={t("rulesEditor.actionPlaceholder", "e.g. greet, get_weather")}
              emptyLabel={t("rulesEditor.noActions", "No actions")}
              ariaLabel={t("rulesEditor.actions", "Actions")}
            />
          </div>

          {/* Conditions */}
          <div>
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("rulesEditor.conditions", "Conditions")}
            </h5>
            <div className="space-y-2">
              {(rule.conditions ?? []).length === 0 && (
                <p className="text-xs italic text-muted-foreground">
                  {t("rulesEditor.noConditions", "No conditions")}
                </p>
              )}
              {(rule.conditions ?? []).map((cond, ci) => (
                <ConditionEditor
                  key={ci}
                  condition={cond}
                  onChange={(updated) => {
                    const copy = [...(rule.conditions ?? [])];
                    copy[ci] = updated;
                    onChange({ ...rule, conditions: copy });
                  }}
                  onRemove={() =>
                    onChange({
                      ...rule,
                      conditions: (rule.conditions ?? []).filter((_, j) => j !== ci),
                    })
                  }
                  readOnly={readOnly}
                />
              ))}
              {!readOnly && (
                <button
                  type="button"
                  onClick={() =>
                    onChange({
                      ...rule,
                      conditions: [
                        ...(rule.conditions ?? []),
                        {
                          type: "inputmatcher",
                          configs: {
                            expressions: "",
                            occurrence: "currentStep",
                          },
                        },
                      ],
                    })
                  }
                  className="inline-flex items-center gap-1.5 rounded-md border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
                  data-testid="add-condition-btn"
                >
                  <Plus className="h-3.5 w-3.5" />
                  {t("rulesEditor.addCondition", "Add Condition")}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export interface RulesEditorProps {
  /** Accepts either spelling — see {@link RulesGroupInput}. */
  data: RulesConfigInput;
  onChange: (data: RulesConfig) => void;
  readOnly?: boolean;
}

/**
 * The single bridge from {@link RulesConfigInput} to {@link RulesConfig}:
 * collapses a rule set onto the `behaviorRules` spelling, whichever one the
 * server sent.
 *
 * The legacy key is *dropped* rather than carried alongside: a group holding
 * both would be serialised with both, and Jackson's last-one-wins would then
 * decide which list survives a save by field order. Losing rules to that is a
 * far worse outcome than losing a redundant key.
 */
function normalizeRulesConfig(data: RulesConfigInput): RulesConfig {
  return {
    ...data,
    behaviorGroups: (data.behaviorGroups ?? []).map(
      ({ rules, behaviorRules, ...rest }) => ({
        ...rest,
        behaviorRules: behaviorRules ?? rules ?? [],
      })
    ),
  };
}

export function RulesEditor({
  data,
  onChange,
  readOnly,
}: RulesEditorProps) {
  const { t } = useTranslation();
  // Memoised, and applied before the useState below (which reads behaviorGroups
  // to seed its expansion state) and before every read and write further down.
  // Without the memo a legacy-shaped rule set yields a fresh object on every
  // render, which invalidates the useCallbacks keyed on `data` every time.
  const config = useMemo(
    () => normalizeRulesConfig(data ?? {}),
    [data]
  );
  const [expandedGroups, setExpandedGroups] = useState<Record<number, boolean>>(
    () =>
      Object.fromEntries((config.behaviorGroups ?? []).map((_, i) => [i, true]))
  );

  const toggleGroup = useCallback((idx: number) => {
    setExpandedGroups((prev) => ({ ...prev, [idx]: !prev[idx] }));
  }, []);

  // Every action some rule emits: offered as a suggestion wherever actions are typed.
  const allActions = useMemo(
    () => [
      ...new Set(
        (config.behaviorGroups ?? []).flatMap((g) =>
          (g.behaviorRules ?? []).flatMap((r) => r.actions ?? [])
        )
      ),
    ],
    [config]
  );

  /** Moves a group; its expansion state travels with it. */
  const moveGroup = useCallback(
    (from: number, to: number) => {
      onChange({ ...config, behaviorGroups: moved(config.behaviorGroups ?? [], from, to) });
      setExpandedGroups((prev) => {
        const next = { ...prev };
        next[from] = prev[to] ?? true;
        next[to] = prev[from] ?? true;
        return next;
      });
    },
    [config, onChange]
  );

  const updateGroup = useCallback(
    (idx: number, group: RulesGroup) => {
      const groups = [...(config.behaviorGroups ?? [])];
      groups[idx] = group;
      onChange({ ...config, behaviorGroups: groups });
    },
    [config, onChange]
  );

  const removeGroup = useCallback(
    (idx: number) => {
      onChange({
        ...config,
        behaviorGroups: (config.behaviorGroups ?? []).filter((_, i) => i !== idx),
      });
    },
    [config, onChange]
  );

  const addGroup = useCallback(() => {
    onChange({
      ...config,
      behaviorGroups: [
        ...(config.behaviorGroups ?? []),
        {
          name: "",
          executionStrategy: "executeUntilFirstSuccess",
          behaviorRules: [],
        },
      ],
    });
  }, [config, onChange]);

  const addRule = useCallback(
    (groupIdx: number) => {
      const groups = [...(config.behaviorGroups ?? [])];
      const group = groups[groupIdx];
      if (!group) return;
      groups[groupIdx] = {
        ...group,
        behaviorRules: [
          ...(group.behaviorRules ?? []),
          { name: "", actions: [], conditions: [] },
        ],
      };
      onChange({ ...config, behaviorGroups: groups });
    },
    [config, onChange]
  );

  return (
    <div className="space-y-6" data-testid="rules-editor">
      {/* Top-level toggles */}
      <div className="flex flex-wrap gap-6">
        <label className="inline-flex items-center gap-2 text-sm text-foreground">
          <input
            type="checkbox"
            checked={config.appendActions ?? false}
            onChange={(e) =>
              onChange({ ...config, appendActions: e.target.checked })
            }
            disabled={readOnly}
            className="h-4 w-4 rounded border-input accent-primary"
          />
          {t("rulesEditor.appendActions", "Append Actions")}
        </label>
        <label className="inline-flex items-center gap-2 text-sm text-foreground">
          <input
            type="checkbox"
            checked={config.expressionsAsActions ?? false}
            onChange={(e) =>
              onChange({ ...config, expressionsAsActions: e.target.checked })
            }
            disabled={readOnly}
            className="h-4 w-4 rounded border-input accent-primary"
          />
          {t("rulesEditor.expressionsAsActions", "Expressions as Actions")}
        </label>
      </div>

      {/* Groups */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <GitBranch className="h-4 w-4 text-primary" />
            {t("rulesEditor.groups", "Behavior Groups")}
          </h3>
          {!readOnly && (
            <button
              type="button"
              onClick={addGroup}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-group-btn"
            >
              <Plus className="h-3.5 w-3.5" />
              {t("rulesEditor.addGroup", "Add Group")}
            </button>
          )}
        </div>

        {(config.behaviorGroups ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("rulesEditor.noGroups", "No rules groups defined")}
          </div>
        )}

        {(config.behaviorGroups ?? []).map((group, gi) => (
          <div
            key={gi}
            className="rounded-xl border bg-card shadow-sm"
            data-testid="rules-group"
          >
            {/* Group header */}
            <div className="flex items-center gap-2 p-3">
              <button
                type="button"
                onClick={() => toggleGroup(gi)}
                aria-expanded={expandedGroups[gi] !== false}
                aria-label={t("rulesEditor.toggleGroup", "Show or hide group")}
                className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors"
              >
                {expandedGroups[gi] !== false ? (
                  <ChevronDown className="h-4 w-4" />
                ) : (
                  <ChevronRight className="h-4 w-4" />
                )}
              </button>
              <input
                type="text"
                value={group.name ?? ""}
                onChange={(e) =>
                  updateGroup(gi, { ...group, name: e.target.value })
                }
                readOnly={readOnly}
                aria-label={t("rulesEditor.groupName", "Group Name")}
                placeholder={t("rulesEditor.groupName", "Group Name")}
                className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
              <select
                value={
                  isKnownStrategy(group.executionStrategy)
                    ? group.executionStrategy
                    : group.executionStrategy || "executeUntilFirstSuccess"
                }
                onChange={(e) =>
                  updateGroup(gi, {
                    ...group,
                    executionStrategy: e.target.value,
                  })
                }
                disabled={readOnly}
                aria-label={t("rulesEditor.executionStrategy", "Execution strategy")}
                className="h-8 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                data-testid="group-strategy-select"
              >
                {!isKnownStrategy(group.executionStrategy) &&
                  group.executionStrategy && (
                    <option value={group.executionStrategy} disabled>
                      {group.executionStrategy}{" "}
                      {t("rulesEditor.invalidValue", "(invalid)")}
                    </option>
                  )}
                {EXECUTION_STRATEGIES.map((s) => (
                  <option key={s} value={s}>
                    {t(`rulesEditor.strategy_${s}`, STRATEGY_LABELS[s])}
                  </option>
                ))}
              </select>
              {!readOnly && (
                <>
                  <MoveButtons
                    onUp={gi > 0 ? () => moveGroup(gi, gi - 1) : undefined}
                    onDown={
                      gi < (config.behaviorGroups ?? []).length - 1
                        ? () => moveGroup(gi, gi + 1)
                        : undefined
                    }
                    label={group.name || t("rulesEditor.thisGroup", "this group")}
                    testId="group-move"
                  />
                  <button
                    type="button"
                    onClick={() => removeGroup(gi)}
                    className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
                    aria-label={t("rulesEditor.removeGroup", "Remove Group")}
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </>
              )}
            </div>

            {/* Group body — rules */}
            {expandedGroups[gi] !== false && (
              <div className="space-y-3 border-t px-4 py-3">
                {(group.behaviorRules ?? []).length === 0 && (
                  <p className="text-xs italic text-muted-foreground">
                    {t("rulesEditor.noRules", "No rules in this group")}
                  </p>
                )}
                {(group.behaviorRules ?? []).map((rule, ri) => (
                  <RuleEditor
                    key={ri}
                    rule={rule}
                    actionSuggestions={allActions}
                    onMoveUp={
                      ri > 0
                        ? () =>
                            updateGroup(gi, {
                              ...group,
                              behaviorRules: moved(group.behaviorRules ?? [], ri, ri - 1),
                            })
                        : undefined
                    }
                    onMoveDown={
                      ri < (group.behaviorRules ?? []).length - 1
                        ? () =>
                            updateGroup(gi, {
                              ...group,
                              behaviorRules: moved(group.behaviorRules ?? [], ri, ri + 1),
                            })
                        : undefined
                    }
                    onChange={(updated) => {
                      const rules = [...(group.behaviorRules ?? [])];
                      rules[ri] = updated;
                      updateGroup(gi, { ...group, behaviorRules: rules });
                    }}
                    onRemove={() =>
                      updateGroup(gi, {
                        ...group,
                        behaviorRules: (group.behaviorRules ?? []).filter(
                          (_, j) => j !== ri
                        ),
                      })
                    }
                    readOnly={readOnly}
                  />
                ))}
                {!readOnly && (
                  <button
                    type="button"
                    onClick={() => addRule(gi)}
                    className="inline-flex w-full items-center justify-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 py-2 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
                    data-testid="add-rule-btn"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    {t("rulesEditor.addRule", "Add Rule")}
                  </button>
                )}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
