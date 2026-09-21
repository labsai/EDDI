import { useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, X } from "lucide-react";

/**
 * Banner shown when MSW mock data is active (no real EDDI backend).
 * Can be hidden via `?hideMockBanner=true` query param or by clicking dismiss.
 */
export function MockDataBanner() {
  const { t } = useTranslation();
  const [dismissed, setDismissed] = useState(false);

  // Don't render if MSW is not active
  if (!(window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__) {
    return null;
  }

  // Allow hiding via query param
  const params = new URLSearchParams(window.location.search);
  if (params.get("hideMockBanner") === "true") {
    return null;
  }

  if (dismissed) return null;

  return (
    <div className="relative flex items-center justify-center gap-2 bg-amber-500/90 px-4 py-1.5 text-xs font-medium text-amber-950 dark:bg-amber-600/90 dark:text-amber-50">
      <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
      <span>
        {t(
          "mockBanner.message",
          "Demo Mode — Displaying sample data. Connect an EDDI backend for real data.",
        )}
      </span>
      <button
        onClick={() => setDismissed(true)}
        className="absolute inset-e-2 top-1/2 -translate-y-1/2 rounded p-0.5 hover:bg-amber-600/30 dark:hover:bg-amber-800/40 transition-colors"
        aria-label={t("common.dismiss", "Dismiss")}
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
