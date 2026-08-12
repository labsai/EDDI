import { useTranslation } from "react-i18next";
import { ArrowUpCircle } from "lucide-react";
import { UpdateCheckCard } from "@/components/shared/update-check-card";

/**
 * "Is a newer EDDI out, and how do I get it?"
 *
 * Its own screen rather than a block on the dashboard: upgrading is a
 * deployment chore an operator goes looking for, not something the daily view
 * should keep offering. The update banner links here.
 */
export function UpdatesPage() {
  const { t } = useTranslation();

  return (
    // Capped rather than full-bleed: the card is three version cells and a
    // button, which on a wide screen strands the button an inch from anything
    // it relates to — and the release notes below it are prose, which reads
    // badly at 1000px a line.
    <div className="max-w-3xl space-y-6" data-testid="updates-page">
      <div>
        <h1
          className="flex items-center gap-2 text-2xl font-bold text-foreground"
          data-testid="updates-page-title"
        >
          <ArrowUpCircle className="h-6 w-6 text-primary" aria-hidden="true" />
          {t("updates.title", "EDDI Updates")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground" data-testid="updates-page-description">
          {t(
            "updates.description",
            "Check whether a newer EDDI release is available. Nothing is sent until you ask.",
          )}
        </p>
      </div>

      <UpdateCheckCard />
    </div>
  );
}
