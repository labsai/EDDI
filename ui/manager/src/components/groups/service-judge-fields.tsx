import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Info } from "lucide-react";
import type { ServiceJudgeConfig } from "@/lib/api/groups";

/** Backend `ServiceJudgeConfig.MAX_TIMEOUT_MS`. */
export const SERVICE_JUDGE_MAX_TIMEOUT_MS = 60_000;
/** Backend `ServiceJudgeConfig.DEFAULT_TIMEOUT_MS`. */
export const SERVICE_JUDGE_DEFAULT_TIMEOUT_MS = 20_000;

interface ServiceJudgeFieldsProps {
  /** The phase index, for stable test ids and label ids. */
  idx: number;
  service: ServiceJudgeConfig | null | undefined;
  onChange: (service: ServiceJudgeConfig) => void;
  inputCls: string;
}

/** A blank text field is absent, the way the backend normalizes it. */
function text(value: string): string | null {
  return value.trim() === "" ? null : value;
}

/** A blank or non-finite number field is absent (unpriced, or the default timeout). */
function num(value: number): number | null {
  return Number.isFinite(value) ? value : null;
}

/**
 * The model a `convergence.judge: "SERVICE"` phase calls directly (8a). Only the
 * API key is editable among the parameters — it is the one a keyed provider
 * cannot run without — and every other parameter the config carries is kept as
 * it is. The key field takes a `${vault:...}` reference: the backend refuses a
 * literal at save time.
 */
export function ServiceJudgeFields({ idx, service, onChange, inputCls }: ServiceJudgeFieldsProps) {
  const { t } = useTranslation();
  const current: ServiceJudgeConfig = service ?? {};
  const patch = (p: Partial<ServiceJudgeConfig>) => onChange({ ...current, ...p });
  const apiKey = current.parameters?.apiKey ?? "";
  const configured = !!current.llmProvider?.trim() && !!current.llmModel?.trim();

  const setApiKey = (value: string) => {
    const parameters = { ...(current.parameters ?? {}) };
    if (value.trim() === "") delete parameters.apiKey;
    else parameters.apiKey = value;
    patch({ parameters });
  };

  const field = (key: string, label: string, input: ReactNode) => (
    <div>
      <label htmlFor={`phase-service-${key}-${idx}`} className="mb-0.5 block text-[10px] text-muted-foreground">
        {label}
      </label>
      {input}
    </div>
  );

  return (
    <div className="mt-2 space-y-2" data-testid={`phase-convergence-service-${idx}`}>
      <div className="grid grid-cols-2 gap-2">
        {field(
          "provider",
          t("groupServiceJudge.provider", "Provider"),
          <input
            id={`phase-service-provider-${idx}`}
            value={current.llmProvider ?? ""}
            placeholder="openai"
            onChange={(e) => patch({ llmProvider: text(e.target.value) })}
            className={inputCls}
            data-testid={`phase-service-provider-${idx}`}
          />,
        )}
        {field(
          "model",
          t("groupServiceJudge.model", "Model"),
          <input
            id={`phase-service-model-${idx}`}
            value={current.llmModel ?? ""}
            placeholder="gpt-4o-mini"
            onChange={(e) => patch({ llmModel: text(e.target.value) })}
            className={inputCls}
            data-testid={`phase-service-model-${idx}`}
          />,
        )}
        {field(
          "apikey",
          t("groupServiceJudge.apiKey", "API key reference"),
          <input
            id={`phase-service-apikey-${idx}`}
            value={apiKey}
            placeholder="${vault:openai-key}"
            onChange={(e) => setApiKey(e.target.value)}
            className={inputCls}
            data-testid={`phase-service-apikey-${idx}`}
          />,
        )}
        {field(
          "timeout",
          t("groupServiceJudge.timeoutMs", "Timeout (ms)"),
          <input
            id={`phase-service-timeout-${idx}`}
            type="number"
            min={1000}
            max={SERVICE_JUDGE_MAX_TIMEOUT_MS}
            step={1000}
            value={current.timeoutMs ?? ""}
            placeholder={String(SERVICE_JUDGE_DEFAULT_TIMEOUT_MS)}
            onChange={(e) => {
              const v = num(e.currentTarget.valueAsNumber);
              patch({ timeoutMs: v === null ? null : Math.min(SERVICE_JUDGE_MAX_TIMEOUT_MS, Math.max(1, Math.trunc(v))) });
            }}
            className={inputCls}
            data-testid={`phase-service-timeout-${idx}`}
          />,
        )}
        {field(
          "input-price",
          t("groupServiceJudge.inputPrice", "Input $ / 1M tokens"),
          <input
            id={`phase-service-input-price-${idx}`}
            type="number"
            min={0}
            step={0.01}
            value={current.inputPricePer1M ?? ""}
            onChange={(e) => patch({ inputPricePer1M: num(e.currentTarget.valueAsNumber) })}
            className={inputCls}
            data-testid={`phase-service-input-price-${idx}`}
          />,
        )}
        {field(
          "output-price",
          t("groupServiceJudge.outputPrice", "Output $ / 1M tokens"),
          <input
            id={`phase-service-output-price-${idx}`}
            type="number"
            min={0}
            step={0.01}
            value={current.outputPricePer1M ?? ""}
            onChange={(e) => patch({ outputPricePer1M: num(e.currentTarget.valueAsNumber) })}
            className={inputCls}
            data-testid={`phase-service-output-price-${idx}`}
          />,
        )}
      </div>
      <p
        className="flex items-start gap-1 text-[10px] text-muted-foreground"
        data-testid={`phase-convergence-service-note-${idx}`}
      >
        <Info className="mt-0.5 h-2.5 w-2.5 shrink-0" aria-hidden="true" />
        {configured
          ? t(
              "groupServiceJudge.noteConfigured",
              "One direct model call per check, no moderator needed. Its cost counts toward the discussion. A failed or unreadable reply never ends the phase.",
            )
          : t(
              "groupServiceJudge.noteFallback",
              "Name a provider and a model, or the moderator agent judges instead.",
            )}
      </p>
    </div>
  );
}
