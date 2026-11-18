import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";

/**
 * What an import, upgrade or sync wrote differently from the source, and why:
 * an `UpgradeResult`'s `warnings`, or an import's `X-Agent-Signing-Turned-Off`.
 *
 * The case that made this necessary: EDDI turns `signInterAgentMessages` off on
 * an agent this instance cannot sign for — every agent an import creates, and
 * any target without a usable key of its own — rather than refusing the whole
 * write. That report was the only signal, and nothing here showed it, so the
 * agent ran unsigned behind a plain success, and every peer that requires
 * verification withheld its messages. The texts are EDDI's own and name the
 * way back (generate a key, then turn signing on).
 */
export function ResultWarnings({ warnings }: { warnings: readonly string[] }) {
  const { t } = useTranslation();
  if (warnings.length === 0) return null;

  return (
    <div
      className="space-y-1 rounded-lg border border-warning/30 bg-warning/5 p-3 text-xs text-foreground"
      data-testid="result-warnings"
    >
      <p className="flex items-start gap-1.5 font-medium">
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
        {t("importDialog.writtenDifferently", "Written, but not exactly as in the source:")}
      </p>
      <ul className="space-y-1 ps-5">
        {/* Keyed by position, not text: two agents of one name can get the same
            text, and the list is never reordered. */}
        {warnings.map((warning, i) => (
          <li key={i}>{warning}</li>
        ))}
      </ul>
    </div>
  );
}
