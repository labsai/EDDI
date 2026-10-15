import { useTranslation } from "react-i18next";
import { ArrowRightLeft, Ban, CircleSlash } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ParameterVerdict } from "@/lib/llm-parameter-catalog";
import { cn } from "@/lib/utils";

/**
 * What the configured model does with one parameter, in words: "Rejected by
 * gpt-5: …" (EDDI drops it before the request), "No effect on …", or — for a
 * rejection the backend repairs — "Sent as maxCompletionTokens for gpt-5".
 * Renders nothing while the parameter applies.
 *
 * `onRemove` offers the one-click fix: shown only when the key is set and its
 * value is about to be thrown away on every request — never for a value the
 * backend moves to another key, since that value is still used.
 */
export function ParameterVerdictNote({
  verdict,
  paramKey,
  configured,
  onRemove,
  readOnly,
  id,
  testId,
  className,
}: {
  verdict: ParameterVerdict;
  paramKey: string;
  configured: boolean;
  onRemove?: () => void;
  readOnly?: boolean;
  id?: string;
  testId: string;
  className?: string;
}) {
  const { t } = useTranslation();
  if (verdict.state === "ok") return null;
  const model = verdict.model ?? "";
  const migrated = verdict.migratesTo;
  const dropped = verdict.state === "rejected" && !migrated;
  const Icon = migrated ? ArrowRightLeft : dropped ? Ban : CircleSlash;

  return (
    <div
      id={id}
      className={cn(
        "flex flex-wrap items-start gap-1.5 text-[11px] leading-snug",
        // A set value that will be dropped (or wasted) is an alarm; an unset
        // setting the model would refuse, or a value EDDI moves to the right
        // key, is information and stays quiet. Dark mode uses the lighter red:
        // the token red on a red-tinted dark surface misses WCAG AA.
        configured && !migrated
          ? cn(
              "rounded-md border px-2 py-1.5",
              dropped
                ? "border-destructive/30 bg-destructive/10 text-destructive dark:text-red-300"
                : "border-warning/30 bg-warning/10 text-warning",
            )
          : cn(
              "text-muted-foreground",
              migrated ? "[&>svg]:text-primary" : dropped ? "[&>svg]:text-destructive" : "[&>svg]:text-warning",
            ),
        className,
      )}
      data-testid={testId}
      data-state={verdict.state}
      data-migrates-to={migrated}
    >
      <Icon className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <p className="min-w-0 flex-1 basis-40">
        <span className="font-semibold">
          {migrated
            ? t("llmEditor.modelSettings.migratedTo", "Sent as {{key}} for {{model}}", { key: migrated, model })
            : dropped
              ? t("llmEditor.modelSettings.rejectedBy", "Rejected by {{model}}", { model })
              : t("llmEditor.modelSettings.noEffectOn", "No effect on {{model}}", { model })}
        </span>
        {verdict.note ? <>: {verdict.note}</> : null}
        {dropped && configured && (
          <span className="block">
            {t(
              "llmEditor.modelSettings.droppedHint",
              "EDDI removes it before each request, so this value is never sent.",
            )}
          </span>
        )}
      </p>
      {dropped && configured && onRemove && !readOnly && (
        <Button
          type="button"
          variant="destructive"
          size="sm"
          className="ms-5 h-6 shrink-0 px-2 text-[11px]"
          onClick={onRemove}
          aria-label={t("llmEditor.modelSettings.removeKey", "Remove {{key}}", { key: paramKey })}
          data-testid={`${testId}-remove`}
          data-focus-fallback
        >
          {t("llmEditor.modelSettings.remove", "Remove")}
        </Button>
      )}
    </div>
  );
}
