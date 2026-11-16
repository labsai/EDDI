import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Plus, X } from "lucide-react";
import { NumberInput } from "../number-input";
import type { TaskSectionProps } from "./task-section-props";
import type { LlmTask } from "./types";

type ToolQuotas = NonNullable<LlmTask["toolQuotas"]>;
type QuotaRule = ToolQuotas["tools"][string];

/** The per-run scope: one scheduled fire, one group discussion, else the conversation. */
const RUN_WINDOW = "RUN";

/** Windows the picker offers; any other stored ISO-8601 duration is kept and shown as-is. */
const WINDOWS: { value: string; key: string; fallback: string }[] = [
  { value: RUN_WINDOW, key: "llmEditor.toolQuotasWindowRun", fallback: "Per run" },
  { value: "PT1H", key: "llmEditor.toolQuotasWindowHour", fallback: "Per hour" },
  { value: "P1D", key: "llmEditor.toolQuotasWindowDay", fallback: "Per day (UTC)" },
  { value: "P7D", key: "llmEditor.toolQuotasWindowWeek", fallback: "Per 7 days" },
  { value: "P31D", key: "llmEditor.toolQuotasWindowMonth", fallback: "Per 31 days" },
];

/**
 * The quotas object with a change applied. An object with no rule left is
 * dropped — subject and all, because the backend refuses a `toolQuotas` with an
 * empty `tools` map — and an empty subject is dropped: the field is only ever
 * written when the author set something, because an older backend's strict
 * parser refuses a field it does not know.
 */
function withQuotas(task: LlmTask, subject: string | undefined, rules: [string, QuotaRule][]): LlmTask {
  const trimmed = subject?.trim();
  if (rules.length === 0) return { ...task, toolQuotas: undefined };
  const next: ToolQuotas = { tools: Object.fromEntries(rules) };
  if (trimmed) next.subject = subject;
  return { ...task, toolQuotas: next };
}

/**
 * Per-subject and per-run tool quotas (`toolQuotas`, EDDI 6.5+; `RUN` windows
 * need the release that introduced them). Caps how often a tool may run for one
 * user inside a fixed window, counted across conversations, or — with `Per run`
 * — inside one scheduled fire, group discussion or conversation.
 */
export function TaskToolQuotas({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const subjectId = useId();
  const hintId = useId();
  const quotas = task.toolQuotas;
  const rules = Object.entries(quotas?.tools ?? {});
  const hasRun = rules.some(([, r]) => r.window?.toUpperCase() === RUN_WINDOW);
  // A rename that lands on another rule's name is kept here, not written: writing
  // it would collapse the two rules into one and silently drop the other's limit.
  const [nameDrafts, setNameDrafts] = useState<Record<number, string>>({});

  const inputCls =
    "h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring aria-[invalid=true]:border-destructive";

  const setRule = (i: number, tool: string, rule: QuotaRule) => {
    const next = [...rules];
    next[i] = [tool, rule];
    onChange(withQuotas(task, quotas?.subject, next));
  };

  const rename = (i: number, name: string, rule: QuotaRule) => {
    if (rules.some(([other], j) => j !== i && other === name)) {
      setNameDrafts((d) => ({ ...d, [i]: name }));
      return;
    }
    setNameDrafts((d) => {
      const rest = { ...d };
      delete rest[i];
      return rest;
    });
    setRule(i, name, rule);
  };

  return (
    <div className="space-y-2 border-t border-border pt-3" data-testid="task-tool-quotas">
      <p className="text-xs font-medium text-foreground">{t("llmEditor.toolQuotasTitle", "Tool quotas")}</p>
      <p id={hintId} className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.toolQuotasHint",
          "Cap how often a tool may run: per user within a time window, counted across conversations, or per run (one scheduled fire, group discussion or conversation). A call over its cap is not run and the model is told the limit is final. Tools in the same pool share one counter and must share limit and window.",
        )}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={subjectId} className="text-xs text-foreground whitespace-nowrap">
          {t("llmEditor.toolQuotasSubject", "Count per")}
        </label>
        <input
          id={subjectId}
          type="text"
          value={quotas?.subject ?? ""}
          onChange={(e) => onChange(withQuotas(task, e.target.value, rules))}
          readOnly={readOnly}
          disabled={!readOnly && rules.length === 0}
          placeholder="{context.personId}"
          aria-describedby={hintId}
          className={`${inputCls} w-56`}
          data-testid="task-tool-quotas-subject"
        />
        <span className="text-[10px] text-muted-foreground">
          {t("llmEditor.toolQuotasSubjectDefault", "(default: the user id)")}
        </span>
      </div>
      <div className="space-y-1.5">
        {rules.map(([tool, rule], i) => {
          const windowKnown = WINDOWS.some((w) => w.value === rule.window?.toUpperCase());
          const limitInvalid = rule.limit === undefined || rule.limit < 1;
          const draft = nameDrafts[i];
          const duplicate = draft !== undefined;
          return (
            <div key={`quota-${i}`} className="flex flex-wrap items-center gap-1.5" data-testid={`task-tool-quota-${i}`}>
              <input
                type="text"
                value={draft ?? tool}
                onChange={(e) => rename(i, e.target.value, rule)}
                readOnly={readOnly}
                placeholder={t("llmEditor.toolName", "Tool name")}
                aria-label={t("llmEditor.toolName", "Tool name")}
                aria-invalid={duplicate}
                title={
                  duplicate
                    ? t(
                        "llmEditor.toolQuotasDuplicate",
                        "Another quota already uses this tool name. The rename is not saved until the name is unique.",
                      )
                    : undefined
                }
                className={`${inputCls} w-40`}
                data-testid={`task-tool-quota-name-${i}`}
              />
              <NumberInput
                integer
                value={rule.limit}
                onChange={(v) => setRule(i, tool, { ...rule, limit: v })}
                readOnly={readOnly}
                placeholder="20"
                aria-label={t("llmEditor.toolQuotasLimit", "Calls allowed")}
                aria-invalid={limitInvalid}
                className={`${inputCls} w-16`}
              />
              <select
                value={rule.window ?? ""}
                disabled={readOnly}
                aria-label={t("llmEditor.toolQuotasWindow", "Window")}
                onChange={(e) => setRule(i, tool, { ...rule, window: e.target.value })}
                className={`${inputCls} w-36`}
                data-testid={`task-tool-quota-window-${i}`}
              >
                {!windowKnown && <option value={rule.window ?? ""}>{rule.window || "—"}</option>}
                {WINDOWS.map((w) => (
                  <option key={w.value} value={w.value}>
                    {t(w.key, w.fallback)}
                  </option>
                ))}
              </select>
              <input
                type="text"
                value={rule.pool ?? ""}
                onChange={(e) => {
                  const next: QuotaRule = { ...rule, pool: e.target.value };
                  if (!e.target.value.trim()) delete next.pool;
                  setRule(i, tool, next);
                }}
                readOnly={readOnly}
                placeholder={t("llmEditor.toolQuotasPool", "Pool (optional)")}
                aria-label={t("llmEditor.toolQuotasPool", "Pool (optional)")}
                className={`${inputCls} w-32`}
              />
              {duplicate && (
                <p className="basis-full text-[10px] text-destructive" role="alert" data-testid={`task-tool-quota-duplicate-${i}`}>
                  {t(
                    "llmEditor.toolQuotasDuplicate",
                    "Another quota already uses this tool name. The rename is not saved until the name is unique.",
                  )}
                </p>
              )}
              {!readOnly && (
                <button
                  type="button"
                  aria-label={t("common.remove", "Remove")}
                  onClick={() => {
                    setNameDrafts({});
                    onChange(
                      withQuotas(
                        task,
                        quotas?.subject,
                        rules.filter((_, j) => j !== i),
                      ),
                    );
                  }}
                  className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
            </div>
          );
        })}
        {!readOnly && (
          <button
            type="button"
            onClick={() => {
              const existing = rules.map(([name]) => name);
              let name = "tool";
              let n = 1;
              while (existing.includes(name)) name = `tool${n++}`;
              onChange(withQuotas(task, quotas?.subject, [...rules, [name, { limit: 20, window: RUN_WINDOW }]]));
            }}
            className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            data-testid="task-tool-quotas-add"
          >
            <Plus className="h-3 w-3" />
            {t("llmEditor.toolQuotasAdd", "Add quota")}
          </button>
        )}
      </div>
      {hasRun && (
        <p className="text-[10px] text-warn-700 dark:text-warn-400" role="status" data-testid="task-tool-quotas-run-note">
          {t(
            "llmEditor.toolQuotasRunNote",
            "A per-run cap bounds an autonomous run (a schedule or a group cadence). It is not an abuse limit for end users — use a fixed window for that.",
          )}
        </p>
      )}
    </div>
  );
}
