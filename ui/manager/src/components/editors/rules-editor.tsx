import { useState, useCallback } from "react";
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

// ─── Constants ───────────────────────────────────────────────────────────────

const CONDITION_TYPES = [
  "inputmatcher",
  "actionmatcher",
  "negation",
  "connector",
  "occurrence",
  "dynamicValueMatcher",
  "deploymentContext",
  "capabilityMatch",
] as const;

const EXECUTION_STRATEGIES = ["currentStepOnly", "lastStepOnly", "anyStep"] as const;

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
    let configs: Record<string, string> = {};
    switch (newType) {
      case "deploymentContext":
        configs = { when: "production", tagMatches: "" };
        break;
      case "capabilityMatch":
        configs = { skill: "", strategy: "highest_confidence", attributes: "" };
        break;
      case "inputmatcher":
        configs = { expressions: "", occurrence: "currentStep" };
        break;
      default:
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
  data: RulesConfig;
  onChange: (data: RulesConfig) => void;
  readOnly?: boolean;
}

export function RulesEditor({
  data,
  onChange,
  readOnly,
}: RulesEditorProps) {
  const { t } = useTranslation();
  data = data ?? ({} as RulesConfig);
  const [expandedGroups, setExpandedGroups] = useState<Record<number, boolean>>(
    () =>
      Object.fromEntries((data.behaviorGroups ?? []).map((_, i) => [i, true]))
  );

  const toggleGroup = useCallback((idx: number) => {
    setExpandedGroups((prev) => ({ ...prev, [idx]: !prev[idx] }));
  }, []);

  const updateGroup = useCallback(
    (idx: number, group: RulesGroup) => {
      const groups = [...(data.behaviorGroups ?? [])];
      groups[idx] = group;
      onChange({ ...data, behaviorGroups: groups });
    },
    [data, onChange]
  );

  const removeGroup = useCallback(
    (idx: number) => {
      onChange({
        ...data,
        behaviorGroups: (data.behaviorGroups ?? []).filter((_, i) => i !== idx),
      });
    },
    [data, onChange]
  );

  const addGroup = useCallback(() => {
    onChange({
      ...data,
      behaviorGroups: [
        ...(data.behaviorGroups ?? []),
        { name: "", executionStrategy: "currentStepOnly", behaviorRules: [] },
      ],
    });
  }, [data, onChange]);

  const addRule = useCallback(
    (groupIdx: number) => {
      const groups = [...(data.behaviorGroups ?? [])];
      const group = groups[groupIdx];
      if (!group) return;
      groups[groupIdx] = {
        ...group,
        behaviorRules: [
          ...(group.behaviorRules ?? []),
          { name: "", actions: [], conditions: [] },
        ],
      };
      onChange({ ...data, behaviorGroups: groups });
    },
    [data, onChange]
  );

  return (
    <div className="space-y-6" data-testid="rules-editor">
      {/* Top-level toggles */}
      <div className="flex flex-wrap gap-6">
        <label className="inline-flex items-center gap-2 text-sm text-foreground">
          <input
            type="checkbox"
            checked={data.appendActions ?? false}
            onChange={(e) =>
              onChange({ ...data, appendActions: e.target.checked })
            }
            disabled={readOnly}
            className="h-4 w-4 rounded border-input accent-primary"
          />
          {t("rulesEditor.appendActions", "Append Actions")}
        </label>
        <label className="inline-flex items-center gap-2 text-sm text-foreground">
          <input
            type="checkbox"
            checked={data.expressionsAsActions ?? false}
            onChange={(e) =>
              onChange({ ...data, expressionsAsActions: e.target.checked })
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

        {(data.behaviorGroups ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("rulesEditor.noGroups", "No rules groups defined")}
          </div>
        )}

        {(data.behaviorGroups ?? []).map((group, gi) => (
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
                value={group.executionStrategy ?? "currentStepOnly"}
                onChange={(e) =>
                  updateGroup(gi, {
                    ...group,
                    executionStrategy: e.target.value,
                  })
                }
                disabled={readOnly}
                className="h-8 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
              >
                {EXECUTION_STRATEGIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
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
