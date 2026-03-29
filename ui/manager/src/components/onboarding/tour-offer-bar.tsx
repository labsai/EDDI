import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { Sparkles, X } from "lucide-react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { TOUR_CHAPTERS } from "./tour-chapters";

/**
 * Subtle, dismissible bottom bar that offers to run the tour for the current page.
 * Only shows for non-dashboard chapters (dashboard auto-starts after welcome).
 * Slides in from the bottom with a brief delay, auto-fades after 8 seconds.
 */
export function TourOfferBar() {
  const { t } = useTranslation();
  const offeredChapter = useOnboarding((s) => s.offeredChapter);
  const acceptOffer = useOnboarding((s) => s.acceptOffer);
  const dismissOffer = useOnboarding((s) => s.dismissOffer);
  const dismissAllOffers = useOnboarding((s) => s.dismissAllOffers);

  const [visible, setVisible] = useState(false);
  const [exiting, setExiting] = useState(false);

  const chapter = offeredChapter ? TOUR_CHAPTERS[offeredChapter] : null;

  // Slide in after a brief delay so the page content renders first
  useEffect(() => {
    if (!offeredChapter) {
      setVisible(false);
      setExiting(false);
      return;
    }

    const showTimer = setTimeout(() => setVisible(true), 600);

    // Auto-dismiss after 12 seconds if user hasn't interacted
    const autoTimer = setTimeout(() => {
      handleDismiss();
    }, 12000);

    return () => {
      clearTimeout(showTimer);
      clearTimeout(autoTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [offeredChapter]);

  const handleDismiss = () => {
    setExiting(true);
    setTimeout(() => {
      dismissOffer();
      setExiting(false);
      setVisible(false);
    }, 300);
  };

  const handleDismissAll = () => {
    setExiting(true);
    setTimeout(() => {
      dismissAllOffers();
      setExiting(false);
      setVisible(false);
    }, 300);
  };

  const handleAccept = () => {
    setVisible(false);
    acceptOffer();
  };

  if (!offeredChapter || !chapter || !visible) return null;

  return createPortal(
    <div
      className={`fixed bottom-6 inset-x-0 z-50 flex justify-center pointer-events-none transition-all duration-300 ${
        exiting
          ? "translate-y-4 opacity-0"
          : "translate-y-0 opacity-100"
      }`}
      data-testid="tour-offer-bar"
    >
      <div className="pointer-events-auto flex items-center gap-3 rounded-xl border border-primary/20 bg-sidebar px-4 py-2.5 shadow-xl shadow-black/20 backdrop-blur-sm">
        <Sparkles className="h-4 w-4 text-primary shrink-0" />

        <span className="text-sm text-sidebar-foreground/80">
          {t("onboarding.offer.message", {
            page: t(chapter.titleKey),
            defaultValue: "New here? Take the {{page}}",
          })}
        </span>

        {/* Accept button */}
        <button
          onClick={handleAccept}
          className="rounded-lg bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground transition-all hover:bg-primary/90 active:scale-[0.97]"
          data-testid="tour-offer-accept"
        >
          {t("onboarding.offer.accept", "Show me")}
        </button>

        {/* Don't show again */}
        <button
          onClick={handleDismissAll}
          className="text-[11px] text-sidebar-foreground/40 hover:text-sidebar-foreground/70 transition-colors whitespace-nowrap"
          data-testid="tour-offer-dismiss-all"
        >
          {t("onboarding.offer.dontAsk", "Don't ask again")}
        </button>

        {/* Close */}
        <button
          onClick={handleDismiss}
          className="rounded p-0.5 text-sidebar-foreground/30 hover:text-sidebar-foreground/60 transition-colors"
          aria-label={t("common.close", "Close")}
          data-testid="tour-offer-dismiss"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>,
    document.body
  );
}
