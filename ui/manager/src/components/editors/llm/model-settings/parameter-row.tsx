import { useTranslation } from "react-i18next";
import { ExternalLink, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  evaluateParameter,
  type ParameterCatalog,
} from "@/lib/llm-parameter-catalog";
import { cn } from "@/lib/utils";
import { ParameterControl } from "./parameter-control";
import { ParameterVerdictNote } from "./parameter-verdict-note";
import { isDropped, type ParamView } from "./model-settings-utils";

/**
 * One catalog parameter: label, key, one-line help, the typed control, its
 * model verdict, a link to the provider's documentation and a reset that
 * removes the key so the provider default applies again.
 */
export function ParameterRow({
  view,
  catalog,
  type,
  effective,
  inherited,
  onChange,
  readOnly,
  domId,
  testIdPrefix,
}: {
  view: ParamView;
  catalog: ParameterCatalog;
  type: string | undefined;
  /** The parameters the backend evaluates (own over inherited). */
  effective: Readonly<Record<string, string>>;
  inherited?: string;
  onChange: (value: string | undefined) => void;
  readOnly?: boolean;
  domId: string;
  testIdPrefix: string;
}) {
  const { t } = useTranslation();
  const { param, value, configured, verdict, locked } = view;
  const controlId = `${domId}-control`;
  const labelId = `${domId}-label`;
  const helpId = `${domId}-help`;
  const verdictId = `${domId}-verdict`;
  const testId = `${testIdPrefix}-${param.key}`;

  const rejectedOptions =
    param.type === "enum"
      ? new Set(
          (param.enum ?? []).filter(
            (option) =>
              evaluateParameter(catalog, type, param.key, { ...effective, [param.key]: option }).state === "rejected",
          ),
        )
      : undefined;

  const range =
    param.min !== undefined && param.max !== undefined
      ? t("llmEditor.modelSettings.range", "{{min}} to {{max}}", { min: param.min, max: param.max })
      : param.min !== undefined
        ? t("llmEditor.modelSettings.atLeast", "at least {{min}}", { min: param.min })
        : param.max !== undefined
          ? t("llmEditor.modelSettings.atMost", "at most {{max}}", { max: param.max })
          : null;

  return (
    <div
      id={domId}
      tabIndex={-1}
      className={cn(
        "scroll-mt-24 outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-lg border px-2.5 py-2 transition-colors @lg/settings:px-3 @lg/settings:py-2.5",
        configured ? "border-primary/30 bg-primary/[0.03]" : "border-border",
        isDropped(verdict) && configured && "border-destructive/40",
      )}
      data-testid={testId}
      data-configured={configured || undefined}
      data-state={verdict.state}
    >
      {/* Side by side once the panel (not the viewport) is wide enough; stacked
          below that, so a narrow panel does not wrap descriptions every few words. */}
      <div className="grid gap-x-4 gap-y-2 @xl/settings:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
        <div className="min-w-0 space-y-0.5">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <label
              id={labelId}
              htmlFor={param.type === "boolean" ? undefined : controlId}
              className="text-xs font-medium text-foreground"
            >
              {param.label}
            </label>
            <code className="text-[10px] text-muted-foreground" dir="ltr">
              {param.key}
            </code>
            {verdict.state !== "ok" && (
              <span
                className={cn(
                  "rounded-full px-1.5 py-px text-[9px] font-semibold uppercase tracking-wide",
                  !configured || verdict.migratesTo
                    ? "bg-muted text-muted-foreground"
                    : verdict.state === "rejected"
                      ? "bg-destructive/15 text-destructive dark:text-red-400"
                      : "bg-warning/15 text-warning",
                )}
                aria-hidden="true"
              >
                {verdict.migratesTo
                  ? t("llmEditor.modelSettings.badgeMigrated", "Sent as {{key}}", { key: verdict.migratesTo })
                  : verdict.state === "rejected"
                    ? t("llmEditor.modelSettings.badgeRejected", "Rejected")
                    : t("llmEditor.modelSettings.badgeNoEffect", "No effect")}
              </span>
            )}
          </div>
          <p id={helpId} className="text-[11px] leading-snug text-muted-foreground">
            {param.description}
            {range && (
              <span className="whitespace-nowrap">
                {" · "}
                {range}
              </span>
            )}
            {param.default !== undefined && param.type !== "boolean" && (
              <span className="whitespace-nowrap">
                {" · "}
                {t("llmEditor.modelSettings.defaultIs", "default {{value}}", { value: param.default })}
              </span>
            )}
          </p>
        </div>

        <div className="min-w-0 space-y-1.5">
          <div className="flex items-start gap-1">
            <div className="min-w-0 flex-1">
              <ParameterControl
                param={param}
                value={value}
                onChange={onChange}
                inherited={inherited}
                disabled={locked}
                readOnly={readOnly}
                rejectedOptions={rejectedOptions}
                id={controlId}
                labelId={labelId}
                describedBy={[helpId, verdict.state !== "ok" ? verdictId : undefined].filter(Boolean).join(" ")}
                testId={`${testId}-input`}
              />
            </div>
            <div className="flex shrink-0 items-center">
              <Button
                asChild
                variant="ghost"
                size="iconSm"
                className="h-8 w-7 text-muted-foreground"
              >
                <a
                  href={param.source}
                  target="_blank"
                  rel="noopener noreferrer"
                  title={t("llmEditor.modelSettings.docs", "Official documentation")}
                  aria-label={t("llmEditor.modelSettings.docsFor", "Official documentation for {{key}} (opens in a new tab)", { key: param.key })}
                  data-testid={`${testId}-docs`}
                >
                  <ExternalLink aria-hidden="true" />
                </a>
              </Button>
              {configured && !readOnly && (
                <Button
                  type="button"
                  variant="ghost"
                  size="iconSm"
                  className="h-8 w-7 text-muted-foreground hover:text-foreground"
                  onClick={() => onChange(undefined)}
                  title={t("llmEditor.modelSettings.reset", "Reset to provider default")}
                  aria-label={t("llmEditor.modelSettings.resetKey", "Reset {{key}} to the provider default", { key: param.key })}
                  data-testid={`${testId}-reset`}
                >
                  <RotateCcw aria-hidden="true" />
                </Button>
              )}
            </div>
          </div>
          <ParameterVerdictNote
            id={verdictId}
            verdict={verdict}
            paramKey={param.key}
            configured={configured}
            onRemove={() => onChange(undefined)}
            readOnly={readOnly}
            testId={`${testId}-verdict`}
          />
        </div>
      </div>
    </div>
  );
}
