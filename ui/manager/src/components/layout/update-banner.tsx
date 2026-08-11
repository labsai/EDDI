import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import { ExternalLink, Gift, X } from "lucide-react";
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
  const { autoCheck, status, latest, knownInstalledVersion } = useUpdateCheck();
  const [dismissed, setDismissed] = useState(false);
  const { pathname } = useLocation();

  if (!autoCheck || status !== "update-available" || !latest || dismissed) {
    return null;
  }

  // Silent on the page it points at. The Updates screen already says which
  // release is out and how to get it, so a strip above the top bar repeating it
  // is noise — and its "How to update" link would navigate to where you are.
  if (pathname === "/manage/updates") return null;

  return (
    <div
      className="relative flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-primary/10 px-10 py-1.5 text-xs font-medium text-foreground"
      // The banner appears asynchronously, once the check resolves — well after
      // the page has settled. Without a live region a screen reader user is
      // simply never told, since nothing moves focus here.
      role="status"
      aria-live="polite"
      data-testid="update-banner"
    >
      {/* A release is a delivery, not an alarm — the icon should read that way. */}
      <Gift className="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
      <span>
        {t("updates.updateAvailable", "EDDI {{version}} is available", {
          version: latest.version,
        })}
        {knownInstalledVersion && (
          <>
            {" "}
            {t("updates.updateAvailableDetail", "You are running {{installed}}.", {
              installed: knownInstalledVersion,
            })}
          </>
        )}
      </span>
      <Link to="/manage/updates" className="underline underline-offset-2 hover:no-underline">
        {t("updates.howToUpdate", "How to update")}
      </Link>
      <a
        href={latest.url}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-1 underline underline-offset-2 hover:no-underline"
        data-testid="update-banner-notes-link"
      >
        {t("updates.releaseNotes", "Release notes")}
        <ExternalLink className="h-3 w-3" aria-hidden="true" />
        <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
      </a>
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
