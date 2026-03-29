import { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Outlet } from "react-router-dom";
import { Sidebar } from "./sidebar";
import { TopBar } from "./top-bar";
import { ChatDrawer } from "@/components/chat/chat-drawer";
import { WelcomeModal } from "@/components/onboarding/welcome-modal";
import { GuidedTour } from "@/components/onboarding/guided-tour";
import { TourOfferBar } from "@/components/onboarding/tour-offer-bar";
import { cn } from "@/lib/utils";

export function AppLayout() {
  const { t } = useTranslation();
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
        <TopBar
          onMenuClick={() => setMobileSidebarOpen(true)}
          sidebarVisible={mobileSidebarOpen}
        />
        <main
          id="main-content"
          className={cn(
            "flex-1 overflow-auto bg-background p-6",
            "transition-all duration-300"
          )}
        >
          <div className="mx-auto max-w-screen-2xl">
            <Outlet />
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
