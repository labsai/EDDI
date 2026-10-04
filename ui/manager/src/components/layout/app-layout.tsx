import { useState, useEffect, useRef, useCallback } from "react";
import { useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Sidebar } from "./sidebar";
import { SuspendedOutlet } from "./suspended-outlet";
import { TopBar } from "./top-bar";
import { ChatDrawer } from "@/components/chat/chat-drawer";
import { WelcomeModal } from "@/components/onboarding/welcome-modal";
import { GuidedTour } from "@/components/onboarding/guided-tour";
import { TourOfferBar } from "@/components/onboarding/tour-offer-bar";
import { MockDataBanner } from "./mock-data-banner";
import { UpdateBanner } from "./update-banner";
import { useDocumentTitle } from "@/hooks/use-document-title";
import { useRouteEntityName } from "@/hooks/use-route-entity-name";

import { cn } from "@/lib/utils";

const MOBILE_MAX = 768;
const SIDEBAR_COLLAPSED_KEY = "eddi-sidebar-collapsed";

function readSidebarCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "true";
  } catch {
    return false;
  }
}

export function AppLayout() {
  const { t } = useTranslation();
  const location = useLocation();
  useDocumentTitle(useRouteEntityName());
  const [sidebarCollapsed, setSidebarCollapsed] = useState(readSidebarCollapsed);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  // Initialised from the real width: starting at `false` rendered the desktop
  // sidebar for one frame on every phone load before the effect corrected it.
  const [isMobile, setIsMobile] = useState(() => window.innerWidth < MOBILE_MAX);
  const drawerRef = useRef<HTMLDivElement>(null);
  const drawerReturnFocusRef = useRef<HTMLElement | null>(null);

  const toggleSidebarCollapsed = useCallback(() => {
    setSidebarCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(next));
      } catch {
        /* storage unavailable — the choice still applies for this session */
      }
      return next;
    });
  }, []);

  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth < MOBILE_MAX);
    checkMobile();
    window.addEventListener("resize", checkMobile);
    return () => window.removeEventListener("resize", checkMobile);
  }, []);

  // Close mobile sidebar when switching to desktop
  useEffect(() => {
    if (!isMobile) setMobileSidebarOpen(false);
  }, [isMobile]);

  // Close the drawer once a navigation lands. It used to stay open over the page
  // the user had just chosen, covering it until they dismissed it by hand.
  useEffect(() => {
    setMobileSidebarOpen(false);
  }, [location.pathname]);

  // Drawer focus: remember where focus was, move it into the drawer on open, and
  // put it back on close. Escape closes it.
  useEffect(() => {
    if (!mobileSidebarOpen) return;
    drawerReturnFocusRef.current = document.activeElement as HTMLElement | null;
    const frame = requestAnimationFrame(() => {
      const first = drawerRef.current?.querySelector<HTMLElement>("a[href], button:not([disabled])");
      (first ?? drawerRef.current)?.focus();
    });
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMobileSidebarOpen(false);
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("keydown", handleKeyDown);
      const target = drawerReturnFocusRef.current;
      const active = document.activeElement;
      // Only restore when focus was lost with the drawer's DOM, not when a
      // navigation has already put it somewhere deliberate.
      if (target?.isConnected && (!active || active === document.body)) target.focus();
    };
  }, [mobileSidebarOpen]);

  /** Keep Tab inside the open drawer — it is modal, so the page behind is inert. */
  const handleDrawerKeyDown = (e: React.KeyboardEvent) => {
    if (e.key !== "Tab") return;
    const focusable = drawerRef.current?.querySelectorAll<HTMLElement>(
      "a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex='-1'])",
    );
    if (!focusable || focusable.length === 0) return;
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    // `relative`: makes the shell a containing block for absolutely positioned
    // descendants. Without it an `absolute` element deep inside a page (Tailwind's
    // `sr-only` is `position: absolute`) resolves against the initial containing
    // block, so no `overflow-hidden`/`overflow-y-auto` ancestor can clip it, it
    // extends the document's scroll height, and focusing it scrolls the WHOLE
    // layout. The shell must never scroll — only <main> does.
    <div className="relative flex h-screen overflow-hidden" data-testid="app-layout">
      {/* Skip to main content — keyboard accessibility */}
      <a href="#main-content" className="skip-to-main">
        {t("common.skipToMain", "Skip to main content")}
      </a>
      {/* Desktop sidebar */}
      {!isMobile && (
        <Sidebar
          collapsed={sidebarCollapsed}
          onToggle={toggleSidebarCollapsed}
        />
      )}

      {/* Mobile sidebar overlay */}
      {isMobile && mobileSidebarOpen && (
        <>
          <div
            className="fixed inset-0 z-40 bg-black/50 transition-opacity"
            onClick={() => setMobileSidebarOpen(false)}
            aria-hidden="true"
            data-testid="sidebar-overlay"
          />
          <div
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label={t("nav.mainNavigation", "Main navigation")}
            tabIndex={-1}
            onKeyDown={handleDrawerKeyDown}
            className="fixed inset-y-0 start-0 z-50 w-64 outline-none"
            data-testid="mobile-nav-drawer"
          >
            <Sidebar
              collapsed={false}
              onToggle={() => setMobileSidebarOpen(false)}
              showLanguage
            />
          </div>
        </>
      )}

      {/* Main content area */}
      <div className="flex flex-1 flex-col overflow-hidden">
        <MockDataBanner />
        <UpdateBanner />
        <TopBar
          onMenuClick={() => setMobileSidebarOpen(true)}
          sidebarVisible={mobileSidebarOpen}
        />
        <main
          id="main-content"
          className={cn(
            // overflow-x-clip: the page never scrolls horizontally. Wide
            // content must wrap, truncate, or scroll inside its own container
            // — a page-level horizontal scrollbar is always a layout bug, and
            // clip turns the failure mode from "whole page pans" into "one
            // element is visibly clipped", which is both less harmful and
            // easier to spot and fix.
            "flex-1 overflow-y-auto overflow-x-clip bg-background p-6",
            "transition-all duration-300"
          )}
        >
          {/* h-full: gives pages a real height reference. Chat-style pages
              size themselves with h-full and scroll INTERNALLY — the old
              h-[calc(100vh-…)] guesses broke whenever a banner or wrapped
              header changed the arithmetic, leaving BOTH the page and <main>
              scrolling, with the scroll-to-bottom arrow tracking the wrong
              one. Pages taller than the viewport still overflow this wrapper
              and scroll <main>, exactly as before. */}
          <div className="@container/main mx-auto h-full max-w-screen-2xl">
            <SuspendedOutlet />
          </div>
        </main>
      </div>
      <ChatDrawer />
      <WelcomeModal />
      <GuidedTour />
      <TourOfferBar />

    </div>
  );
}
