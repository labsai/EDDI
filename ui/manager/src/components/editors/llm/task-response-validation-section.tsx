import { useTranslation } from "react-i18next";
import { ShieldCheck, Info } from "lucide-react";
import { EditorSection } from "../editor-section";
import type { TaskSectionProps } from "./task-section-props";
import type { ResponseValidation, ResponseValidationAction } from "./types";
import { RESPONSE_VALIDATION_ACTIONS } from "./types";

/** Policy fields on `responseValidation`, in display order. */
type PolicyField = keyof Omit<ResponseValidation, "enabled">;

interface PolicyMeta {
  field: PolicyField;
  labelKey: string;
  labelText: string;
  hintKey: string;
  hintText: string;
  /** Backend default when the field is unset. */
  fallback: ResponseValidationAction;
}

/**
 * Per-policy descriptors. `fallback` mirrors the backend defaults
 * (onRefusal = "ignore", everything else = "warn") so the selects reflect what
 * the engine actually does when the field is omitted.
 */
const POLICIES: PolicyMeta[] = [
  {
    field: "onEmpty",
    labelKey: "llmEditor.rvOnEmpty",
    labelText: "Empty response",
    hintKey: "llmEditor.rvOnEmptyHint",
    hintText: "LLM returned nothing (empty or null)",
    fallback: "warn",
  },
  {
    field: "onTruncation",
    labelKey: "llmEditor.rvOnTruncation",
    labelText: "Truncated response",
    hintKey: "llmEditor.rvOnTruncationHint",
    hintText: "Response cut off (finishReason=LENGTH)",
    fallback: "warn",
  },
  {
    field: "onContentFilter",
    labelKey: "llmEditor.rvOnContentFilter",
    labelText: "Content-filtered",
    hintKey: "llmEditor.rvOnContentFilterHint",
    hintText: "Response blocked by the provider's content filter",
    fallback: "warn",
  },
  {
    field: "onRefusal",
    labelKey: "llmEditor.rvOnRefusal",
    labelText: "Refusal",
    hintKey: "llmEditor.rvOnRefusalHint",
    hintText: "LLM refused to answer (heuristic detection)",
    fallback: "ignore",
  },
  {
    field: "onStreamingTimeout",
    labelKey: "llmEditor.rvOnStreamingTimeout",
    labelText: "Streaming timeout",
    hintKey: "llmEditor.rvOnStreamingTimeoutHint",
    hintText: "A streaming response timed out before completing",
    fallback: "warn",
  },
];

/**
 * Response Validation & Recovery section.
 *
 * Exposes the engine's `LlmConfiguration.Task.responseValidation` policies plus
 * `streamingTimeoutSeconds`. Each policy decides how the engine reacts to a
 * specific anomaly in an LLM turn — ignore, warn (log + continue), fallback
 * (substitute a canned message), or error (fail the turn).
 */
export function TaskResponseValidationSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();

  const rv = task.responseValidation;
  const enabled = rv?.enabled ?? false;

  const actionLabel = (action: ResponseValidationAction): string => {
    switch (action) {
      case "ignore":
        return t("llmEditor.rvActionIgnore", "Ignore");
      case "warn":
        return t("llmEditor.rvActionWarn", "Warn (log & continue)");
      case "fallback":
        return t("llmEditor.rvActionFallback", "Fallback (canned message)");
      case "error":
        return t("llmEditor.rvActionError", "Error (fail the turn)");
    }
  };

  const setPolicy = (field: PolicyField, value: ResponseValidationAction) =>
    onChange({
      ...task,
      responseValidation: { ...task.responseValidation, [field]: value },
    });

  return (
    <EditorSection
      label={t("llmEditor.responseValidation", "Response Validation & Recovery")}
      icon={ShieldCheck}
      accent="text-emerald-500"
      defaultOpen={enabled || task.streamingTimeoutSeconds != null}
    >
      <div className="space-y-3" data-testid="response-validation-section">
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t(
            "llmEditor.responseValidationDesc",
            "Control how the engine reacts to an empty, truncated, content-filtered, refused, or timed-out turn. \"Fallback\" substitutes a canned message; \"Error\" fails the turn; \"Warn\" logs and continues; \"Ignore\" does nothing."
          )}
        </p>

        {/* Master switch */}
        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) =>
              onChange({
                ...task,
                responseValidation: { ...task.responseValidation, enabled: e.target.checked },
              })
            }
            disabled={readOnly}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="rv-enabled"
          />
          <ShieldCheck className="h-3.5 w-3.5 text-emerald-500" />
          {t("llmEditor.rvEnable", "Enable Response Validation")}
        </label>

        {!enabled && (
          <div className="flex items-start gap-2 rounded-md bg-sky-500/10 px-3 py-2 text-[11px] text-sky-700 dark:text-sky-400" data-testid="rv-disabled-info">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>
              {t(
                "llmEditor.rvDisabledInfo",
                "Policies below are saved but only take effect while validation is enabled."
              )}
            </span>
          </div>
        )}

        {/* Per-policy actions */}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {POLICIES.map(({ field, labelKey, labelText, hintKey, hintText, fallback }) => (
            <div key={field}>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t(labelKey, labelText)}
              </label>
              <select
                value={rv?.[field] ?? fallback}
                onChange={(e) => setPolicy(field, e.target.value as ResponseValidationAction)}
                disabled={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                data-testid={`rv-${field}`}
              >
                {RESPONSE_VALIDATION_ACTIONS.map((action) => (
                  <option key={action} value={action}>
                    {actionLabel(action)}
                  </option>
                ))}
              </select>
              <p className="mt-0.5 text-[10px] text-muted-foreground">{t(hintKey, hintText)}</p>
            </div>
          ))}
        </div>

        {/* Streaming timeout */}
        <div className="border-t border-border pt-3">
          <label className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("llmEditor.streamingTimeoutSeconds", "Streaming Timeout (seconds)")}
          </label>
          <input
            type="number"
            min={1}
            value={task.streamingTimeoutSeconds ?? ""}
            onChange={(e) =>
              onChange({
                ...task,
                streamingTimeoutSeconds: e.target.value ? parseInt(e.target.value, 10) : undefined,
              })
            }
            readOnly={readOnly}
            placeholder="120"
            className="h-7 w-28 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="rv-streaming-timeout-seconds"
          />
          <p className="mt-0.5 text-[10px] text-muted-foreground">
            {t(
              "llmEditor.streamingTimeoutSecondsHint",
              "Overrides the engine default (120s). Only applies while streaming; empty = use default."
            )}
          </p>
        </div>
      </div>
    </EditorSection>
  );
}
