import { useState, useEffect } from "react";
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

import { cn } from "@/lib/utils";

export function AppLayout() {
  const { t } = useTranslation();
  useDocumentTitle();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(false);

  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth < 768);
    checkMobile();
    window.addEventListener("resize", checkMobile);
    return () => window.removeEventListener("resize", checkMobile);
  }, []);

  // Close mobile sidebar when switching to desktop
  useEffect(() => {
    if (!isMobile) setMobileSidebarOpen(false);
  }, [isMobile]);

  // M5: Close mobile sidebar on Escape key
  useEffect(() => {
    if (!mobileSidebarOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMobileSidebarOpen(false);
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [mobileSidebarOpen]);

  return (
    <div className="flex h-screen overflow-hidden" data-testid="app-layout">
      {/* Skip to main content — keyboard accessibility */}
      <a href="#main-content" className="skip-to-main">
        {t("common.skipToMain", "Skip to main content")}
      </a>
      {/* Desktop sidebar */}
      {!isMobile && (
        <Sidebar
          collapsed={sidebarCollapsed}
          onToggle={() => setSidebarCollapsed((prev) => !prev)}
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
          <div className="fixed inset-y-0 start-0 z-50 w-64">
            <Sidebar
              collapsed={false}
              onToggle={() => setMobileSidebarOpen(false)}
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
