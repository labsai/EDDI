import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  GitBranch,
} from "lucide-react";

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
            {t("rulesEditor.noActions", "No actions")}
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
              "rulesEditor.actionPlaceholder",
              "e.g. greet, get_weather"
            )}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <button
            type="button"
            onClick={addAction}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
          >
            <Plus className="h-3 w-3" />
            {t("rulesEditor.addAction", "Add")}
          </button>
        </div>
      )}
    </div>
  );
}

function KeyValueRow({
  configKey,
  value,
  onKeyChange,
  onValueChange,
  onRemove,
  readOnly,
}: {
  configKey: string;
  value: string;
  onKeyChange: (k: string) => void;
  onValueChange: (v: string) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5">
      <input
        type="text"
        value={configKey}
        onChange={(e) => onKeyChange(e.target.value)}
        readOnly={readOnly}
        placeholder={t("rulesEditor.configKey", "Key")}
        className="h-7 w-28 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
      <span className="text-xs text-muted-foreground">=</span>
      <input
        type="text"
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        readOnly={readOnly}
        placeholder={t("rulesEditor.configValue", "Value")}
        className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
      {!readOnly && (
        <button
          type="button"
          onClick={onRemove}
          className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
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

  const updateConfig = (key: string, value: string) => {
    onChange({
      ...condition,
      configs: { ...condition.configs, [key]: value },
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
      entries.map(([k, v]) => (k === oldKey ? [newKey, v] : [k, v]))
    );
    onChange({ ...condition, configs: updated });
  };

  const addConfigEntry = () => {
    const nextKey = `key${configEntries.length}`;
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

  /** Provide sensible default configs when switching condition type */
  const handleTypeChange = (newType: string) => {
    let configs: Record<string, string>;
    switch (newType) {
      case "inputmatcher":
        configs = { expressions: "", occurrence: "currentStep" };
        break;
      case "actionmatcher":
        configs = { actions: "", occurrence: "currentStep" };
        break;
      case "occurrence":
        configs = { maxTimesOccurred: "1", behaviorRuleName: "" };
        break;
      case "deploymentContext":
        configs = { when: "production", tagMatches: "" };
        break;
      case "capabilityMatch":
        configs = { skill: "", strategy: "highest_confidence", attributes: "" };
        break;
      case "contextmatcher":
        configs = { context: "", contextType: "string", string: "" };
        break;
      case "contentTypeMatcher":
        configs = { mimeType: "", minCount: "1" };
        break;
      case "sizematcher":
        configs = { valuePath: "", min: "", max: "" };
        break;
      case "dependency":
        configs = { reference: "" };
        break;
      default:
        // negation, connector, dynamicvaluematcher — no preset configs
        configs = {};
        break;
    }
    onChange({ ...condition, type: newType, configs });
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
              value={v}
              onKeyChange={(nk) => renameConfigKey(k, nk)}
              onValueChange={(nv) => updateConfig(k, nv)}
              onRemove={() => removeConfigEntry(k)}
              readOnly={readOnly}
            />
          ))}
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
    </div>
  );
}

function RuleEditor({
  rule,
  onChange,
  onRemove,
  readOnly,
}: {
  rule: Rule;
  onChange: (r: Rule) => void;
  onRemove: () => void;
  readOnly?: boolean;
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
          onChange={(e) => onChange({ ...rule, name: e.target.value })}
          readOnly={readOnly}
          placeholder={t("rulesEditor.ruleName", "Rule Name")}
          className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="rule-name-input"
        />
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("rulesEditor.removeRule", "Remove Rule")}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>

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
                <button
                  type="button"
                  onClick={() => removeGroup(gi)}
                  className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
                  aria-label={t("rulesEditor.removeGroup", "Remove Group")}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
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
