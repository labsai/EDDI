import "@/styles/advisory.css";

import { useEffect, useState, useCallback } from "react";
import { Outlet } from "react-router-dom";
import { cn } from "@/lib/utils";
import { BoardroomSidebar } from "./boardroom-sidebar";
import { BoardroomTopbar } from "./boardroom-topbar";
import { BoardroomBottomTabs } from "./boardroom-bottom-tabs";

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
  // Viewport detection
  const [viewport, setViewport] = useState<Viewport>(() =>
    getViewport(window.innerWidth),
  );

  useEffect(() => {
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

  const openDrawer = useCallback(() => setDrawerOpen(true), []);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  // Close drawer on Escape key
  useEffect(() => {
    if (!drawerOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeDrawer();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [drawerOpen, closeDrawer]);

  // ─── Mobile ──────────────────────────────────────────────────
  if (viewport === "mobile") {
    return (
      <div className="boardroom flex h-screen flex-col overflow-hidden">
        <BoardroomTopbar />
        <main
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
        <BoardroomTopbar onMenuClick={openDrawer} />

        <main
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
              aria-hidden
            />

            {/* Sidebar drawer */}
            <div
              className={cn(
                "fixed inset-y-0 start-0 z-50 w-72",
                "animate-[br-sheet-up_300ms_ease-out]",
              )}
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
      <BoardroomSidebar collapsed={collapsed} onToggle={toggleCollapsed} />

      <div className="flex flex-1 flex-col overflow-hidden">
        <BoardroomTopbar />
        <main
          className="flex-1 overflow-auto"
          style={{ backgroundColor: "var(--br-bg)" }}
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
