import { useState, useEffect, useCallback, useRef } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import {
  Bot,
  Wrench,
  MessageCircle,
  BarChart3,
  ArrowRight,
  ArrowLeft,
  Sparkles,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useOnboarding } from "@/hooks/use-onboarding";

const CAPABILITIES = [
  {
    icon: Bot,
    titleKey: "onboarding.welcome.buildAgents",
    descKey: "onboarding.welcome.buildAgentsDesc",
    gradient: "from-violet-500 to-indigo-600",
  },
  {
    icon: Wrench,
    titleKey: "onboarding.welcome.designWorkflows",
    descKey: "onboarding.welcome.designWorkflowsDesc",
    gradient: "from-emerald-500 to-teal-600",
  },
  {
    icon: MessageCircle,
    titleKey: "onboarding.welcome.testRealtime",
    descKey: "onboarding.welcome.testRealtimeDesc",
    gradient: "from-blue-500 to-cyan-600",
  },
  {
    icon: BarChart3,
    titleKey: "onboarding.welcome.monitorOperate",
    descKey: "onboarding.welcome.monitorOperateDesc",
    gradient: "from-amber-500 to-orange-600",
  },
];

const TOTAL_PANELS = 3;

export function WelcomeModal() {
  const { t } = useTranslation();
  const showWelcome = useOnboarding((s) => s.showWelcome);
  const dismissWelcome = useOnboarding((s) => s.dismissWelcome);
  const startChapter = useOnboarding((s) => s.startChapter);

  const [panel, setPanel] = useState(0);

  const handleStartTour = useCallback(() => {
    dismissWelcome();
    startChapter("dashboard");
  }, [dismissWelcome, startChapter]);

  const handleExplore = useCallback(() => {
    dismissWelcome();
  }, [dismissWelcome]);

  const cardRef = useRef<HTMLDivElement>(null);

  // Keyboard navigation + focus trap
  useEffect(() => {
    if (!showWelcome) return;

    // Lock body scroll
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const handleKeyDown = (e: KeyboardEvent) => {
      switch (e.key) {
        case "ArrowRight":
          e.preventDefault();
          setPanel((p) => Math.min(p + 1, TOTAL_PANELS - 1));
          break;
        case "ArrowLeft":
          e.preventDefault();
          setPanel((p) => Math.max(p - 1, 0));
          break;
        case "Escape":
          e.preventDefault();
          handleExplore();
          break;
        case "Tab": {
          // Basic focus trap: cycle within the modal card
          if (!cardRef.current) break;
          const focusable = cardRef.current.querySelectorAll<HTMLElement>(
            'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
          );
          if (focusable.length === 0) break;
          const first = focusable[0] as HTMLElement | undefined;
          const last = focusable[focusable.length - 1] as HTMLElement | undefined;
          if (!first || !last) break;
          if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
          } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
          }
          break;
        }
      }
    };

    document.addEventListener("keydown", handleKeyDown);

    // Auto-focus the card on mount
    cardRef.current?.focus();

    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = prev;
    };
  }, [showWelcome, handleExplore]);

  if (!showWelcome) return null;

  return createPortal(
    <div
      className="welcome-modal-backdrop fixed inset-0 z-10000 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label={t("onboarding.welcome.title")}
      data-testid="welcome-modal"
    >
      <div
        ref={cardRef}
        className="welcome-modal-card w-full max-w-xl rounded-2xl shadow-2xl outline-none"
        tabIndex={-1}
      >
        {/* Panel content */}
        <div className="relative min-h-[380px] p-8">
          {/* Panel 1: Welcome */}
          {panel === 0 && (
            <div className="flex flex-col items-center text-center animate-[fadeIn_300ms_ease-out]">
              {/* Logo area with glow */}
              <div className="relative mb-6">
                <div className="absolute -inset-4 rounded-full bg-primary/10 blur-xl" />
                <div className="relative flex h-20 w-20 items-center justify-center rounded-2xl bg-linear-to-br from-primary to-primary/60 shadow-lg shadow-primary/25">
                  <Sparkles className="h-10 w-10 text-primary-foreground" />
                </div>
              </div>
              <h2 className="text-2xl font-bold text-white">
                {t("onboarding.welcome.title")}
              </h2>
              <p className="mt-2 text-lg text-sidebar-foreground/70">
                {t("onboarding.welcome.subtitle")}
              </p>
              <div className="mt-4 h-0.5 w-16 rounded-full bg-linear-to-r from-transparent via-primary to-transparent" />
              <p className="mt-4 max-w-sm text-sm leading-relaxed text-sidebar-foreground/50">
                {t(
                  "onboarding.welcome.introText",
                  "EDDI Manager is your command center for building, testing, and operating conversational AI agents."
                )}
              </p>
            </div>
          )}

          {/* Panel 2: Capabilities */}
          {panel === 1 && (
            <div className="animate-[fadeIn_300ms_ease-out]">
              <h2 className="mb-5 text-center text-xl font-bold text-white">
                {t("onboarding.welcome.capabilitiesTitle")}
              </h2>
              <div className="grid grid-cols-2 gap-3">
                {CAPABILITIES.map((cap) => {
                  const Icon = cap.icon;
                  return (
                    <div
                      key={cap.titleKey}
                      className="group rounded-xl border border-sidebar-border bg-sidebar-border/20 p-4 transition-all hover:border-primary/30 hover:-translate-y-0.5"
                    >
                      <div
                        className={cn(
                          "mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-linear-to-br text-white shadow-md",
                          cap.gradient
                        )}
                      >
                        <Icon className="h-5 w-5" />
                      </div>
                      <p className="text-sm font-semibold text-white">
                        {t(cap.titleKey)}
                      </p>
                      <p className="mt-1 text-xs leading-relaxed text-sidebar-foreground/60">
                        {t(cap.descKey)}
                      </p>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* Panel 3: Ready to Start */}
          {panel === 2 && (
            <div className="flex flex-col items-center text-center animate-[fadeIn_300ms_ease-out]">
              <div className="relative mb-6">
                <div className="absolute -inset-4 rounded-full bg-emerald-500/10 blur-xl" />
                <div className="relative flex h-20 w-20 items-center justify-center rounded-2xl bg-linear-to-br from-emerald-500 to-teal-600 shadow-lg">
                  <ArrowRight className="h-10 w-10 text-white" />
                </div>
              </div>
              <h2 className="text-2xl font-bold text-white">
                {t("onboarding.welcome.readyTitle")}
              </h2>
              <p className="mt-2 text-sm text-sidebar-foreground/60">
                {t("onboarding.welcome.readySubtitle")}
              </p>

              <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row">
                <button
                  onClick={handleStartTour}
                  className="inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/25 transition-all hover:bg-primary/90 hover:shadow-xl hover:shadow-primary/30 active:scale-[0.97]"
                  data-testid="welcome-start-tour"
                >
                  <Sparkles className="h-4 w-4" />
                  {t("onboarding.welcome.startTour")}
                </button>
                <button
                  onClick={handleExplore}
                  className="inline-flex items-center gap-2 rounded-xl border border-sidebar-border px-6 py-3 text-sm font-medium text-sidebar-foreground/70 transition-colors hover:border-sidebar-foreground/30 hover:text-sidebar-foreground"
                  data-testid="welcome-explore"
                >
                  {t("onboarding.welcome.exploreOwn")}
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Carousel dots + arrow navigation */}
        <div className="flex items-center justify-between border-t border-sidebar-border px-6 py-4">
          <button
            onClick={() => setPanel((p) => Math.max(p - 1, 0))}
            disabled={panel === 0}
            className={cn(
              "rounded-lg p-1.5 transition-colors",
              panel === 0
                ? "invisible"
                : "text-sidebar-foreground/50 hover:text-sidebar-foreground hover:bg-sidebar-accent/10"
            )}
            aria-label={t("onboarding.tour.back")}
          >
            <ArrowLeft className="h-4 w-4" />
          </button>

          {/* Dots */}
          <div className="flex items-center gap-2">
            {Array.from({ length: TOTAL_PANELS }).map((_, i) => (
              <button
                key={i}
                onClick={() => setPanel(i)}
                className={cn(
                  "carousel-dot h-2 rounded-full transition-all",
                  i === panel
                    ? "carousel-dot-active w-6 bg-primary"
                    : "w-2 bg-sidebar-foreground/30 hover:bg-sidebar-foreground/50"
                )}
                aria-label={`Panel ${i + 1}`}
              />
            ))}
          </div>

          <button
            onClick={() => setPanel((p) => Math.min(p + 1, TOTAL_PANELS - 1))}
            disabled={panel === TOTAL_PANELS - 1}
            className={cn(
              "rounded-lg p-1.5 transition-colors",
              panel === TOTAL_PANELS - 1
                ? "invisible"
                : "text-sidebar-foreground/50 hover:text-sidebar-foreground hover:bg-sidebar-accent/10"
            )}
            aria-label={t("onboarding.tour.next")}
          >
            <ArrowRight className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
