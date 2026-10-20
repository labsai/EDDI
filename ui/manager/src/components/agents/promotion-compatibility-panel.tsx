import { useTranslation } from "react-i18next";
import { ArrowRight, Pin } from "lucide-react";
import { CompatibleVersionCheckbox } from "@/components/agents/compatible-version-checkbox";
import type { PromotionImpact } from "@/lib/api/backup";

export interface PromotionCompatibilityPanelProps {
  /** What the write does to the target's running conversations; absent when unknown. */
  impact?: PromotionImpact | null;
  compatible: boolean;
  onCompatibleChange: (compatible: boolean) => void;
  disabled?: boolean;
  "data-testid"?: string;
}

/**
 * The compatible/breaking choice for promoting an agent, with what each answer
 * does to the conversations running on it.
 *
 * Unticked by default, always: a promotion comes from another environment and
 * nobody has judged it against the conversations running here, so the safe
 * answer is that they stay on the version they started on. The caller owns the
 * state and passes it on as `compatible`.
 *
 * The preview carries the counts for both answers, so ticking the box changes the
 * sentence below it instantly and never costs another round trip to the source.
 */
export function PromotionCompatibilityPanel({
  impact,
  compatible,
  onCompatibleChange,
  disabled,
  "data-testid": testId = "promotion-compatibility",
}: PromotionCompatibilityPanelProps) {
  const { t } = useTranslation();
  const total = impact?.activeConversations ?? 0;
  const following = compatible ? (impact?.followingIfCompatible ?? 0) : 0;

  return (
    <div className="space-y-2" data-testid={testId}>
      <CompatibleVersionCheckbox
        checked={compatible}
        onChange={onCompatibleChange}
        disabled={disabled}
        // null (a version that predates generations) is what makes the checkbox
        // say that conversations on it stay; undefined (no counts for the caller)
        // makes it say so conditionally.
        previousGeneration={impact ? impact.currentGeneration : undefined}
        data-testid={`${testId}-checkbox`}
      />
      {impact && total > 0 && (
        <p
          className={
            following > 0
              ? "flex items-start gap-1.5 text-xs text-emerald-600 dark:text-emerald-400"
              : "flex items-start gap-1.5 text-xs text-muted-foreground"
          }
          data-testid={`${testId}-impact`}
          data-outcome={following > 0 ? "FOLLOW" : "STAY"}
        >
          {following > 0 ? (
            <ArrowRight className="mt-0.5 h-3.5 w-3.5 shrink-0 rtl:rotate-180" aria-hidden="true" />
          ) : (
            <Pin className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          )}
          <span>
            {compatible
              ? t(
                  "agentVersioning.promotionImpactCompatible",
                  "Running conversations that will continue on the new version on their next turn: {{following}} of {{total}}. The rest stay on the version they started on.",
                  { following, total },
                )
              : t(
                  "agentVersioning.promotionImpactBreaking",
                  "Running conversations that will stay on the version they started on: {{total}}. Tick the box above to let them continue on the new version.",
                  { total },
                )}
          </span>
        </p>
      )}
    </div>
  );
}
