import { useTranslation } from "react-i18next";
import { Scale } from "lucide-react";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { useOwningAgentId } from "@/hooks/use-owning-agent";
import { ProviderSelect } from "@/components/shared/provider-select";
import { type CascadeJudgeModel } from "../types";
import { modelParamSpec } from "../model-params";
import { LEGACY_MODEL_KEY, modelField, withModel } from "./cascade-utils";

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
  const owningAgentId = useOwningAgentId();
  const judge = value ?? {};

  const params = judge.parameters ?? {};
  const setParam = (key: string, val: string) =>
    onChange({ ...judge, parameters: { ...params, [key]: val } });
  // The judge is built by its own provider's builder, so its model lives under
  // that provider's key; a judge saved with the legacy `model` is shown as is
  // and moved on the first edit.
  const judgeType = judge.type ?? "openai";
  const model = modelField(params, judgeType);

  /** A provider change carries the model to the key the new provider reads. */
  const changeType = (type: string) => {
    const nextKey = modelParamSpec(type).modelKey;
    if ((nextKey === model.key && !model.legacy) || model.value === "") {
      onChange({ ...judge, type });
      return;
    }
    const next = { ...params };
    delete next[model.key];
    if (model.legacy) delete next[LEGACY_MODEL_KEY];
    next[nextKey] = model.value;
    onChange({ ...judge, type, parameters: next });
  };

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
        <ProviderSelect
          value={judge.type ?? "openai"}
          onChange={changeType}
          disabled={readOnly}
          className="h-7 rounded-md border border-input bg-background px-2 text-xs font-semibold text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          testId="cascade-judge-type"
        />
        <input
          type="text"
          value={model.value}
          onChange={(e) => onChange({ ...judge, parameters: withModel(params, model.key, e.target.value, model.legacy) })}
          readOnly={readOnly}
          placeholder={t("llmEditor.cascadeModelName", "e.g. gpt-4o-mini")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="cascade-judge-model-name"
          data-model-key={model.key}
        />
      </div>

      <div>
        <label className="mb-0.5 block text-[10px] text-muted-foreground">
          {t("llmEditor.cascadeJudgeApiKey", "Judge API Key")}
        </label>
        <SecretKeyPicker
          agentId={owningAgentId}
          value={params.apiKey ?? ""}
          onChange={(v) => setParam("apiKey", v)}
          readOnly={readOnly}
          placeholder={"${vault:...}"}
          testId="cascade-judge-apikey"
        />
      </div>
    </div>
  );
}
