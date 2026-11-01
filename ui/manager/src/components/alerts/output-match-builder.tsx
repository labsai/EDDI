import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { joinPath, opsFor, outputMatchProblems, pathProblem, splitPath, valueTypeOf, type PathSource, type ValueType } from "@/lib/alert-rules";
import type { AlertCondition, MatchOp } from "@/lib/api/alerts";

const selectClass =
  "h-10 rounded-lg border border-input bg-background px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring";

interface OutputMatchBuilderProps {
  condition: AlertCondition;
  onChange: (patch: Partial<AlertCondition>) => void;
}

function convert(text: string, type: ValueType): AlertCondition["value"] {
  switch (type) {
    case "number":
      return text.trim() === "" || Number.isNaN(Number(text)) ? null : Number(text);
    case "boolean":
      return text === "true";
    case "list":
      return text
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);
    default:
      return text;
  }
}

function asText(value: AlertCondition["value"]): string {
  if (value === null || value === undefined) return "";
  return Array.isArray(value) ? value.join(", ") : String(value);
}

/**
 * The OUTPUT_MATCH condition: where to look (a property, a field of the agent's
 * JSON output, or the turn's actions), how to compare, and the rule author's
 * constant. No expressions: the backend compares the value at the path with the
 * constant, nothing else.
 */
export function OutputMatchBuilder({ condition, onChange }: OutputMatchBuilderProps) {
  const { t } = useTranslation();
  const { source, rest } = splitPath(condition.path);
  const type = valueTypeOf(condition.value);
  const ops = opsFor(source, type);
  const pathError = pathProblem(condition.path);
  const problems = outputMatchProblems(condition);

  const setSource = (next: PathSource) => {
    const nextOps = opsFor(next, type);
    const op = nextOps.includes(condition.op as MatchOp) ? condition.op : nextOps[0];
    if (next === "actions") {
      // Actions are compared as text: contains needs a string, exists takes no value.
      onChange({ path: joinPath(next, ""), op, value: op === "exists" ? null : asText(condition.value) });
      return;
    }
    onChange({ path: joinPath(next, rest), op });
  };

  const setType = (next: ValueType) => {
    const nextOps = opsFor(source, next);
    onChange({
      value: convert(asText(condition.value), next),
      op: nextOps.includes(condition.op as MatchOp) ? condition.op : nextOps[0],
    });
  };

  return (
    <div className="space-y-3" data-testid="output-match-builder">
      <div className="flex flex-wrap items-start gap-2">
        <select
          className={selectClass}
          value={source}
          onChange={(e) => setSource(e.target.value as PathSource)}
          aria-label={t("alerts.matchSource", "Look at")}
          data-testid="output-match-source"
        >
          <option value="properties">{t("alerts.matchSourceProperties", "A conversation property")}</option>
          <option value="output.json">{t("alerts.matchSourceOutput", "A field of the agent's JSON output")}</option>
          <option value="actions">{t("alerts.matchSourceActions", "The turn's actions")}</option>
        </select>
        {source !== "actions" && (
          <div className="min-w-[12rem] flex-1">
            <Input
              value={rest}
              placeholder={source === "properties" ? "escalate" : "sentiment"}
              onChange={(e) => onChange({ path: joinPath(source, e.target.value) })}
              aria-label={t("alerts.matchPath", "Path")}
              aria-invalid={pathError ? true : undefined}
              data-testid="output-match-path"
            />
            {pathError && (
              <p className="mt-1 text-xs text-destructive" data-testid="output-match-path-error">
                {pathError === "depth"
                  ? t("alerts.matchPathDepth", "At most 8 levels deep.")
                  : t("alerts.matchPathInvalid", "Dot-separated names only (letters, digits, _ and -), no wildcards.")}
              </p>
            )}
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {source !== "actions" && (
          <select
            className={selectClass}
            value={type}
            onChange={(e) => setType(e.target.value as ValueType)}
            aria-label={t("alerts.matchValueType", "Value type")}
            data-testid="output-match-type"
          >
            <option value="text">{t("alerts.matchTypeText", "Text")}</option>
            <option value="number">{t("alerts.matchTypeNumber", "Number")}</option>
            <option value="boolean">{t("alerts.matchTypeBoolean", "True / false")}</option>
            <option value="list">{t("alerts.matchTypeList", "One of a list")}</option>
          </select>
        )}
        <select
          className={selectClass}
          value={condition.op ?? ops[0]}
          onChange={(e) => onChange({ op: e.target.value as MatchOp })}
          aria-label={t("alerts.matchOp", "Comparison")}
          data-testid="output-match-op"
        >
          {ops.map((op) => (
            <option key={op} value={op}>
              {t(`alerts.op.${op}`, op)}
            </option>
          ))}
        </select>
        {condition.op !== "exists" &&
          (type === "boolean" && source !== "actions" ? (
            <select
              className={selectClass}
              value={String(condition.value === true)}
              onChange={(e) => onChange({ value: e.target.value === "true" })}
              aria-label={t("alerts.matchValue", "Value")}
              data-testid="output-match-value"
            >
              <option value="true">true</option>
              <option value="false">false</option>
            </select>
          ) : (
            <Input
              className="max-w-xs"
              type={type === "number" ? "number" : "text"}
              value={asText(condition.value)}
              placeholder={type === "list" ? "angry, frustrated" : source === "actions" ? "handoff_requested" : ""}
              onChange={(e) => onChange({ value: convert(e.target.value, source === "actions" ? "text" : type) })}
              aria-label={t("alerts.matchValue", "Value")}
              data-testid="output-match-value"
            />
          ))}
      </div>

      <label className="flex items-start gap-2 text-sm">
        <input
          type="checkbox"
          className="mt-0.5 h-4 w-4 accent-primary"
          checked={condition.includeValue === true}
          onChange={(e) => onChange({ includeValue: e.target.checked })}
          data-testid="output-match-include-value"
        />
        <span>
          {t("alerts.includeValue", "Include the value that matched")}
          <span className="block text-xs text-muted-foreground">
            {t(
              "alerts.includeValueHint",
              "Only when the agent version has conversation review turned on — the value comes from the conversation. Otherwise the alert names the path, the comparison and your constant only. Cut to 280 characters.",
            )}
          </span>
        </span>
      </label>
      {problems.length > 0 && !pathError && (
        <p className="text-xs text-destructive" data-testid="output-match-problem">
          {t("alerts.matchIncomplete", "Choose a comparison that fits the value, and enter the value.")}
        </p>
      )}
    </div>
  );
}
