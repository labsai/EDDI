import "@/styles/advisory.css";

import { useEffect, useState, useCallback, useRef } from "react";
import { Outlet } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { BoardroomSidebar } from "./boardroom-sidebar";
import { BoardroomTopbar } from "./boardroom-topbar";
import { BoardroomBottomTabs } from "./boardroom-bottom-tabs";
import { BoardroomShortcuts } from "./boardroom-shortcuts";
import { ShortcutsDialog } from "./shortcuts-dialog";

// ─── Constants ───────────────────────────────────────────────────

const MOBILE_MAX = 640;
const TABLET_MAX = 1024;
const STORAGE_KEY = "boardroom-sidebar-collapsed";

type Viewport = "mobile" | "tablet" | "desktop";

function getViewport(width: number): Viewport {
  if (width < MOBILE_MAX) return "mobile";
  if (width < TABLET_MAX) return "tablet";
  return "desktop";
}

// ─── Component ───────────────────────────────────────────────────

export function BoardroomLayout() {
  const { t } = useTranslation();

  // Viewport detection
  const [viewport, setViewport] = useState<Viewport>("desktop");

  useEffect(() => {
    setViewport(getViewport(window.innerWidth));
    const onResize = () => setViewport(getViewport(window.innerWidth));
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  // Sidebar collapsed state (desktop)
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(STORAGE_KEY) === "true";
    } catch {
      return false;
    }
  });

  const toggleCollapsed = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(STORAGE_KEY, String(next));
      } catch {
        /* no-op */
      }
      return next;
    });
  }, []);

  // Tablet drawer state
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLElement | null>(null);

  const openDrawer = useCallback(() => {
    triggerRef.current = document.activeElement as HTMLElement;
    setDrawerOpen(true);
  }, []);
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
    requestAnimationFrame(() => {
      triggerRef.current?.focus();
    });
  }, []);

  // Focus trap handler for drawer
  const handleDrawerKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key !== 'Tab') return;
    const focusable = drawerRef.current?.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }, []);

  // Close drawer on Escape key
  useEffect(() => {
    if (!drawerOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeDrawer();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen, closeDrawer]);

  // Auto-focus first element in drawer
  useEffect(() => {
    if (!drawerOpen) return;
    requestAnimationFrame(() => {
      const first = drawerRef.current?.querySelector<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      first?.focus();
    });
  }, [drawerOpen]);

  // ─── Mobile ──────────────────────────────────────────────────
  if (viewport === "mobile") {
    return (
      <div className="boardroom flex h-screen flex-col overflow-hidden">
        <BoardroomShortcuts />
        <ShortcutsDialog />
        <a
          href="#boardroom-main"
          className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:start-2 focus:z-50 focus:bg-indigo-500 focus:text-white focus:ps-4 focus:pe-4 focus:py-2 focus:rounded-lg"
        >
          {t("boardroom.skipToContent", "Skip to content")}
        </a>
        <BoardroomTopbar />
        <main
          id="boardroom-main"
          className="flex-1 overflow-auto pb-20"
          style={{ backgroundColor: "var(--br-bg)" }}
        >
          <Outlet />
        </main>
        <BoardroomBottomTabs />
      </div>
    );
  }

  // ─── Tablet ──────────────────────────────────────────────────
  if (viewport === "tablet") {
    return (
      <div className="boardroom flex h-screen flex-col overflow-hidden">
        <BoardroomShortcuts />
        <ShortcutsDialog />
        <a
          href="#boardroom-main"
          className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:start-2 focus:z-50 focus:bg-indigo-500 focus:text-white focus:ps-4 focus:pe-4 focus:py-2 focus:rounded-lg"
        >
          {t("boardroom.skipToContent", "Skip to content")}
        </a>
        <BoardroomTopbar onMenuClick={openDrawer} />

        <main
          id="boardroom-main"
          className="flex-1 overflow-auto"
          style={{ backgroundColor: "var(--br-bg)" }}
        >
          <Outlet />
        </main>

        {/* Drawer overlay */}
        {drawerOpen && (
          <>
            {/* Backdrop */}
            <div
              className="fixed inset-0 z-40 bg-black/50"
              onClick={closeDrawer}
              aria-hidden="true"
            />

            {/* Sidebar drawer */}
            <div
              ref={drawerRef}
              role="dialog"
              aria-modal="true"
              aria-label={t("boardroom.sidebarDrawer", "Sidebar navigation")}
              onKeyDown={handleDrawerKeyDown}
              className={cn(
                "fixed inset-y-0 start-0 z-50 w-72",
              )}
              style={{
                animation: `${document.documentElement.dir === 'rtl' ? 'br-drawer-in-rtl' : 'br-drawer-in'} 300ms ease-out`,
              }}
            >
              <BoardroomSidebar
                collapsed={false}
                onToggle={closeDrawer}
                onClose={closeDrawer}
              />
            </div>
          </>
        )}
      </div>
    );
  }

  // ─── Desktop ─────────────────────────────────────────────────
  return (
    <div className="boardroom flex h-screen overflow-hidden">
      <BoardroomShortcuts />
      <ShortcutsDialog />
      <a
        href="#boardroom-main"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:start-2 focus:z-50 focus:bg-indigo-500 focus:text-white focus:ps-4 focus:pe-4 focus:py-2 focus:rounded-lg"
      >
        {t("boardroom.skipToContent", "Skip to content")}
      </a>
      <BoardroomSidebar collapsed={collapsed} onToggle={toggleCollapsed} />

      <div className="flex flex-1 flex-col overflow-hidden">
        <BoardroomTopbar />
        <main
          id="boardroom-main"
          className="flex-1 overflow-auto"
          style={{ backgroundColor: "var(--br-bg)" }}
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
