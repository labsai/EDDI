import { useTranslation } from "react-i18next";
import { Scale } from "lucide-react";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { MODEL_TYPES, type CascadeJudgeModel } from "../types";

/**
 * Editor for the `judge_model` confidence-evaluation strategy — a separate
 * (usually cheap) model that scores each response's confidence. Shown only
 * when the evaluation strategy is "judge_model".
 */
export function CascadeJudgeModelEditor({
  value,
  onChange,
  readOnly,
}: {
  value?: CascadeJudgeModel;
  onChange: (v: CascadeJudgeModel) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const judge = value ?? {};

  const setParam = (key: string, val: string) =>
    onChange({ ...judge, parameters: { ...judge.parameters, [key]: val } });

  return (
    <div
      className="space-y-2 rounded-lg border border-border bg-card p-3"
      data-testid="cascade-judge-model"
    >
      <label className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
        <Scale className="h-3 w-3" />
        {t("llmEditor.cascadeJudgeModel", "Judge Model")}
      </label>
      <p className="text-[10px] text-muted-foreground">
        {t(
          "llmEditor.cascadeJudgeModelDesc",
          "A separate (usually cheap) model that rates each response's confidence.",
        )}
      </p>

      <div className="flex items-center gap-2">
        <select
          value={judge.type ?? "openai"}
          onChange={(e) => onChange({ ...judge, type: e.target.value })}
          disabled={readOnly}
          className="h-7 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid="cascade-judge-type"
        >
          {MODEL_TYPES.map((mt) => (
            <option key={mt} value={mt}>
              {mt}
            </option>
          ))}
        </select>
        <input
          type="text"
          value={judge.parameters?.model ?? ""}
          onChange={(e) => setParam("model", e.target.value)}
          readOnly={readOnly}
          placeholder={t("llmEditor.cascadeModelName", "e.g. gpt-4o-mini")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="cascade-judge-model-name"
        />
      </div>

      <div>
        <label className="mb-0.5 block text-[10px] text-muted-foreground">
          {t("llmEditor.cascadeJudgeApiKey", "Judge API Key")}
        </label>
        <SecretKeyPicker
          value={judge.parameters?.apiKey ?? ""}
          onChange={(v) => setParam("apiKey", v)}
          readOnly={readOnly}
          placeholder={"${vault:...}"}
          testId="cascade-judge-apikey"
        />
      </div>
    </div>
  );
}
