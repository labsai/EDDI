import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ArrowUpCircle, X } from "lucide-react";
import { useUpdateCheck } from "@/hooks/use-update-check";

/**
 * The "a newer EDDI is out" message.
 *
 * Only ever shown to someone who ticked the auto-check box — a manual check
 * reports its own result in the card, and turning a one-off button press into a
 * page-wide banner would be putting up a notice nobody asked to keep.
 *
 * Dismissal is per page load on purpose: the check itself runs once per reload,
 * so a dismissal that outlived the reload would silently mute the feature.
 */
export function UpdateBanner() {
  const { t } = useTranslation();
  const { autoCheck, status, latest, installedVersion } = useUpdateCheck();
  const [dismissed, setDismissed] = useState(false);

  if (!autoCheck || status !== "update-available" || !latest || dismissed) {
    return null;
  }

  return (
    <div
      className="relative flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-primary/10 px-10 py-1.5 text-xs font-medium text-foreground"
      data-testid="update-banner"
    >
      <ArrowUpCircle className="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
      <span>
        {t("updates.updateAvailable", "EDDI {{version}} is available", {
          version: latest.version,
        })}
        {installedVersion && installedVersion !== "Unknown" && (
          <>
            {" "}
            {t("updates.updateAvailableDetail", "You are running {{installed}}.", {
              installed: installedVersion,
            })}
          </>
        )}
      </span>
      <Link to="/manage#updates" className="underline underline-offset-2 hover:no-underline">
        {t("updates.howToUpdate", "How to update")}
      </Link>
      <button
        onClick={() => setDismissed(true)}
        className="absolute inset-e-2 top-1/2 -translate-y-1/2 rounded p-0.5 transition-colors hover:bg-primary/20"
        aria-label={t("common.dismiss", "Dismiss")}
        data-testid="update-banner-dismiss"
      >
        <X className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}
