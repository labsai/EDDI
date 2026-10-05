import { Link, NavLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { usePendingApprovals } from "@/hooks/use-hitl";
import {
  PanelLeftClose,
  PanelLeft,
  LogOut,
  UserRound,
  ExternalLink,
  BookOpen,
  FileJson,
  HelpCircle,
  Check,
  RotateCcw,
  ChevronRight,
  Languages,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";
import { userDisplayName, userInitials, userSecondaryEmail } from "@/lib/user-display";
import { useState, useRef, useEffect, useCallback } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useOnboarding, ALL_CHAPTERS, type TourChapterId } from "@/hooks/use-onboarding";
import { TOUR_CHAPTERS } from "@/components/onboarding/tour-chapters";
import { useEddiVersion } from "@/hooks/use-update-check";
import { UNKNOWN_VERSION } from "@/lib/api/system";
import { ModeSwitcher } from "@/components/shared/mode-switcher";
import { useLanguageSwitcher } from "@/hooks/use-language-switcher";
import { NAV_SECTIONS, pageLabel } from "@/lib/route-registry";
// Imported rather than referenced as "/logo_eddi.png" from public/: at 2 KB it
// is under Vite's 4 KB assetsInlineLimit, so the app inlines it as a data URI
// (same pixels, one fewer request) — and the design-system bundle, which cannot
// ship public/ assets, inlines it too instead of rendering a broken image in
// every design built with Sidebar.
import logoEddi from "@/assets/logo_eddi.png";

const externalLinks = [
  {
    href: "/q/swagger-ui",
    icon: FileJson,
    labelKey: "nav.openapi",
    fallback: "OpenAPI",
  },
  {
    href: "https://docs.labs.ai",
    icon: BookOpen,
    labelKey: "nav.docs",
    fallback: "Documentation",
  },
] as const;

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
  /**
   * Show the language selector in the footer. The top bar hides its own below
   * `sm`, so the phone navigation drawer is where it has to live.
   */
  showLanguage?: boolean;
}

export function Sidebar({ collapsed, onToggle, showLanguage = false }: SidebarProps) {
  const { t } = useTranslation();
  const { language, languages, changeLanguage } = useLanguageSwitcher();
  const { method, user, logout } = useAuth();
  const showUser = method === "keycloak" && user;

  const { data: serverVersion, isLoading } = useEddiVersion();

  const versionLabel = isLoading
    ? t("nav.checkingVersion", "Checking version...")
    : serverVersion && serverVersion !== UNKNOWN_VERSION
      ? `EDDI ${serverVersion}`
      : `EDDI Demo ${__APP_VERSION__}`;

  // Shares its query key with the approvals page, so mounting this in the
  // sidebar adds an observer rather than a second poll. The endpoint allows
  // eddi-approver alongside admin/editor/user, so every role that can act on
  // an approval can also see that one is waiting.
  const { data: pendingApprovals } = usePendingApprovals();
  const pendingApprovalCount = pendingApprovals?.length ?? 0;

  /** Avatar initials and label — both empty when the token carries no profile claims */
  const initials = showUser ? userInitials(user) : "";
  const displayName = showUser ? userDisplayName(user) : "";
  const secondaryEmail = showUser ? userSecondaryEmail(user) : "";
  const userLabel = displayName || t("auth.signedIn", "Signed in");

  // ── Collapsible section state (persisted in localStorage) ──
  // Keyed by the section's stable id. It used to be keyed by position, so adding
  // or reordering a section silently collapsed a different one for everybody who
  // had saved state. Older saves held positions; they are mapped onto ids below.
  const STORAGE_KEY = "eddi-sidebar-sections";
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(() => {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (!stored) return new Set();
      const parsed = JSON.parse(stored) as unknown;
      if (!Array.isArray(parsed)) return new Set();
      const ids = parsed
        .map((entry) => (typeof entry === "number" ? NAV_SECTIONS[entry]?.id : entry))
        .filter((id): id is string => typeof id === "string");
      return new Set(ids);
    } catch {
      return new Set();
    }
  });

  const toggleSection = useCallback((id: string) => {
    setCollapsedSections((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify([...next])); } catch { /* noop */ }
      return next;
    });
  }, []);

  return (
    <aside
      data-testid="sidebar"
      className={cn(
        "flex h-full flex-col border-e border-sidebar-border bg-sidebar transition-all duration-300",
        collapsed ? "w-16" : "w-64"
      )}
    >
      {/* Logo */}
      <div className="flex h-16 items-center justify-center border-b border-sidebar-border px-4">
        {collapsed ? (
          <svg
            width="28"
            height="28"
            viewBox="0 0 28 28"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            className="shrink-0"
            aria-label="EDDI"
          >
            <rect width="28" height="28" rx="6" className="fill-sidebar-accent" />
            <text
              x="5"
              y="20"
              fontFamily="'Noto Sans', sans-serif"
              fontWeight="700"
              fontSize="16"
              className="fill-sidebar"
            >
              E.
            </text>
          </svg>
        ) : (
          // The wordmark is a pure-white glyph: keep it white on the dark
          // sidebar (dark mode) but invert to near-black on the white sidebar
          // (light mode) so it stays visible.
          <img
            src={logoEddi}
            alt="EDDI"
            className="h-7 w-auto invert dark:invert-0"
          />
        )}
      </div>

      {/* Mode switcher (Manager ↔ Workforce) */}
      <div className="shrink-0 border-b border-sidebar-border p-1.5">
        <ModeSwitcher collapsed={collapsed} />
      </div>

      {/* Navigation with section groupings */}
      <nav className="flex-1 overflow-y-auto p-1.5" aria-label={t("nav.mainNavigation", "Main navigation")}>
        {NAV_SECTIONS.map((section, idx) => (
          <div key={section.id} className={cn(idx > 0 && "mt-2.5")}>
            {/* Section label — clickable toggle (hidden when sidebar is collapsed) */}
            {!collapsed && (
              <button
                type="button"
                onClick={() => toggleSection(section.id)}
                className="mb-1 flex w-full items-center gap-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/50 hover:text-sidebar-foreground/80 transition-colors"
                aria-expanded={!collapsedSections.has(section.id)}
                aria-controls={`sidebar-section-${section.id}`}
              >
                <ChevronRight
                  className={cn(
                    "h-3 w-3 shrink-0 transition-transform duration-200",
                    !collapsedSections.has(section.id) && "rotate-90"
                  )}
                  aria-hidden="true"
                />
                {t(section.labelKey)}
              </button>
            )}
            {collapsed && idx > 0 && (
              <div className="mx-3 mb-2 border-t border-sidebar-border" />
            )}
            {/* Section items — hidden when section is collapsed (only in expanded sidebar) */}
            {(!collapsed ? !collapsedSections.has(section.id) : true) && (
              <div id={`sidebar-section-${section.id}`} className="space-y-0.5">
                {section.items.map((item) => {
                  const label = pageLabel(t, item);
                  return (
                  <NavLink
                    key={item.path}
                    to={item.path}
                    // `end` on the parent conversations route so it isn't kept
                    // active on the /monitoring child route.
                    end={item.path === "/manage" || item.path === "/manage/conversations"}
                    className={({ isActive }) =>
                      cn(
                        "relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-all",
                        "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                        isActive
                          ? "border-s-2 border-sidebar-accent bg-sidebar-accent/10 text-sidebar-accent"
                          : "border-s-2 border-transparent text-sidebar-foreground",
                        collapsed && "justify-center px-2"
                      )
                    }
                    aria-label={collapsed ? label : undefined}
                    title={collapsed ? label : undefined}
                  >
                    <item.icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                    {!collapsed && <span>{label}</span>}
                    {/* Nothing else in the app says a decision is waiting on
                        you — an approval sits paused until someone happens to
                        open this page. The count is the whole point, so it is
                        rendered even collapsed, where it becomes a dot on the
                        icon. */}
                    {item.path === "/manage/approvals" && pendingApprovalCount > 0 && (
                      <span
                        className={cn(
                          "ms-auto inline-flex items-center justify-center rounded-full bg-amber-500 font-semibold text-white",
                          collapsed
                            ? "absolute top-1.5 end-1.5 h-2 w-2"
                            : "h-5 min-w-5 px-1.5 text-[11px]",
                        )}
                        data-testid="nav-approvals-badge"
                        aria-label={t("hitl.pendingCount", "{{count}} awaiting approval", {
                          count: pendingApprovalCount,
                        })}
                      >
                        {!collapsed && (pendingApprovalCount > 99 ? "99+" : pendingApprovalCount)}
                      </span>
                    )}
                  </NavLink>
                  );
                })}
              </div>
            )}
          </div>
        ))}
      </nav>

      {/* External links */}
      <div className="border-t border-sidebar-border p-1.5">
        {!collapsed && (
          <p className="mb-1 px-3 text-[11px] font-semibold uppercase tracking-wider text-sidebar-foreground/50">
            {t("nav.sectionExternal", "External")}
          </p>
        )}
        <div className="space-y-0.5">
          {externalLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-1.5 text-sm font-medium transition-all",
                "border-s-2 border-transparent text-sidebar-foreground",
                "hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
                collapsed && "justify-center px-2"
              )}
              aria-label={collapsed ? `${t(link.labelKey, link.fallback)} (${t("common.opensNewTab", "opens in new tab")})` : undefined}
              title={collapsed ? t(link.labelKey, link.fallback) : undefined}
            >
              <link.icon className="h-5 w-5 shrink-0" aria-hidden="true" />
              {!collapsed && (
                <span className="flex items-center gap-1.5">
                  {t(link.labelKey, link.fallback)}
                  <ExternalLink className="h-3 w-3 opacity-50" aria-hidden="true" />
                  <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
                </span>
              )}
            </a>
          ))}
        </div>
      </div>

      {/* User profile section (only when auth is enabled) */}
      {showUser && (
        <div className="border-t border-sidebar-border p-1.5">
          <div
            className={cn(
              "flex items-center gap-3 rounded-lg px-3 py-2",
              collapsed && "justify-center px-2"
            )}
            data-testid="sidebar-user"
          >
            {/* Avatar */}
            {/* text-sidebar-accent-foreground is the token that pairs with
                bg-sidebar-accent. Identical to the text-sidebar it replaces in light
                mode (#ffffff), and one shade off in dark (#0c0a09 vs #09090b) — both
                near-black on gold, so no visible change. It is also the app's only
                use of the token: without it Tailwind tree-shakes it out of the
                design-system bundle entirely (see .design-sync/NOTES.md). */}
            {/* Collapsed, the avatar is all that is left, so it carries the name
                for hover and screen readers. Expanded, the name is printed beside
                it, and announcing the initials first would only repeat it. */}
            <div
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-sidebar-accent text-xs font-bold text-sidebar-accent-foreground"
              data-testid="sidebar-user-avatar"
              {...(collapsed
                ? { role: "img", "aria-label": userLabel, title: userLabel }
                : { "aria-hidden": true })}
            >
              {initials || (
                <UserRound
                  className="h-4 w-4"
                  aria-hidden="true"
                  data-testid="sidebar-user-avatar-icon"
                />
              )}
            </div>
            {!collapsed && (
              <div className="flex min-w-0 flex-1 items-center justify-between">
                <div className="min-w-0">
                  <p
                    className="truncate text-sm font-medium text-sidebar-foreground"
                    title={displayName || undefined}
                  >
                    {userLabel}
                  </p>
                  {secondaryEmail && (
                    <p className="truncate text-xs text-sidebar-foreground/60" title={secondaryEmail}>
                      {secondaryEmail}
                    </p>
                  )}
                </div>
                <button
                  onClick={logout}
                  data-testid="sidebar-logout"
                  title={t("auth.logout", "Logout")}
                  aria-label={t("auth.logout", "Logout")}
                  className="ms-2 shrink-0 rounded-md p-1.5 text-sidebar-foreground/60 transition-colors hover:bg-sidebar-accent/10 hover:text-sidebar-accent"
                >
                  <LogOut className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Language — only in the phone drawer, see `showLanguage` */}
      {showLanguage && !collapsed && (
        <div className="flex items-center gap-2 border-t border-sidebar-border p-1.5 px-3 py-2">
          <Languages className="h-5 w-5 shrink-0 text-sidebar-foreground" aria-hidden="true" />
          <select
            value={language}
            onChange={(e) => void changeLanguage(e.target.value)}
            aria-label={t("language.label", "Language")}
            data-testid="sidebar-language-selector"
            className="min-w-0 flex-1 rounded-md bg-sidebar-accent/5 px-2 py-1.5 text-sm text-sidebar-foreground outline-none focus:ring-2 focus:ring-ring"
          >
            {languages.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.label}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Help & Tour menu */}
      <HelpMenu collapsed={collapsed} />

      {/* Version + Collapse toggle */}
      <div className="border-t border-sidebar-border p-1.5">
        {!collapsed && (
          // The version is where "am I current?" gets asked, and it is the only
          // place in the app that already states which release you are on — so
          // it links to the Updates page instead of sitting there inert. The
          // nav entry is the last item of the last section; this is the shortcut.
          <Link
            to="/manage/updates"
            className="mb-1 block px-3 text-center text-[10px] text-sidebar-foreground/30 transition-colors hover:text-sidebar-accent"
            title={
              serverVersion === UNKNOWN_VERSION
                ? `Standalone Demo Mode fallback`
                : t("updates.title", "EDDI Updates")
            }
            // The visible text is a version string, which says nothing about
            // where the link goes.
            aria-label={`${versionLabel} — ${t("updates.title", "EDDI Updates")}`}
            data-testid="sidebar-version"
          >
            {versionLabel}
          </Link>
        )}
        <button
          onClick={onToggle}
          data-testid="sidebar-toggle"
          aria-label={
            collapsed
              ? t("nav.expandSidebar", "Expand sidebar")
              : t("nav.collapseSidebar", "Collapse sidebar")
          }
          aria-expanded={!collapsed}
          className="flex w-full items-center justify-center rounded-lg p-2 text-sidebar-foreground transition-all hover:bg-sidebar-accent/10 hover:text-sidebar-accent active:scale-[0.98]"
        >
          {collapsed ? (
            <PanelLeft className="h-5 w-5" />
          ) : (
            <PanelLeftClose className="h-5 w-5" />
          )}
        </button>
      </div>
    </aside>
  );
}

/* ─── Help & Tour dropdown menu ──────────────────── */

const CHAPTER_ROUTES: Record<TourChapterId, string> = {
  dashboard: "/manage",
  agents: "/manage/agents",
  workflows: "/manage/workflows",
  chat: "/manage/chat",
  resources: "/manage/resources",
  conversations: "/manage/conversations",
  groups: "/manage/groups",
  logs: "/manage/logs",
  secrets: "/manage/secrets",
  audit: "/manage/audit",
  schedules: "/manage/schedules",
  quotas: "/manage/quotas",
  coordinator: "/manage/coordinator",
  orphans: "/manage/orphans",
};

/**
 * Sidebar "Help & Tour" popover: replays one onboarding chapter (jumping to
 * its page) or resets them all. Closes on an outside click or Escape.
 */
function HelpMenu({ collapsed }: { collapsed: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const completedChapters = useOnboarding((s) => s.completedChapters);
  const restartChapter = useOnboarding((s) => s.restartChapter);
  const resetAll = useOnboarding((s) => s.resetAll);

  // Close on outside click or Escape
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", handler);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handler);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  // The chapter to start once its page is showing. A fixed delay after
  // `navigate()` raced the lazy-loaded page chunk and missed targets that had not
  // rendered yet; waiting for the location to arrive is exact, and the tour itself
  // waits for each step's target (see GuidedTour).
  const { pathname } = useLocation();
  const [pendingChapter, setPendingChapter] = useState<TourChapterId | null>(null);
  useEffect(() => {
    if (pendingChapter && pathname === CHAPTER_ROUTES[pendingChapter]) {
      restartChapter(pendingChapter);
      setPendingChapter(null);
    }
  }, [pendingChapter, pathname, restartChapter]);

  const handleChapterClick = (id: TourChapterId) => {
    setOpen(false);
    if (pathname === CHAPTER_ROUTES[id]) {
      restartChapter(id);
      return;
    }
    setPendingChapter(id);
    navigate(CHAPTER_ROUTES[id]);
  };

  // Auto-focus first menu item when opened
  useEffect(() => {
    if (open) {
      requestAnimationFrame(() => {
        const firstItem = ref.current?.querySelector<HTMLElement>('[role="menuitem"]');
        firstItem?.focus();
      });
    }
  }, [open]);

  /** Arrow key navigation for the menu */
  const handleMenuKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    const items = ref.current?.querySelectorAll<HTMLElement>('[role="menuitem"]');
    if (!items || items.length === 0) return;
    const itemArray = Array.from(items);
    const currentIndex = itemArray.indexOf(document.activeElement as HTMLElement);

    let nextIndex: number;
    switch (e.key) {
      case "ArrowDown":
        nextIndex = (currentIndex + 1) % itemArray.length;
        break;
      case "ArrowUp":
        nextIndex = (currentIndex - 1 + itemArray.length) % itemArray.length;
        break;
      case "Home":
        nextIndex = 0;
        break;
      case "End":
        nextIndex = itemArray.length - 1;
        break;
      default:
        return;
    }
    e.preventDefault();
    itemArray[nextIndex]?.focus();
  }, []);

  return (
    <div ref={ref} className="relative border-t border-sidebar-border p-1.5">
      <button
        ref={triggerRef}
        onClick={() => setOpen((p) => !p)}
        className={cn(
          "flex w-full items-center rounded-lg px-3 py-2 text-sidebar-foreground transition-all hover:bg-sidebar-accent/10 hover:text-sidebar-accent",
          collapsed && "justify-center px-2"
        )}
        title={t("onboarding.help.title", "Help & Tour")}
        aria-label={t("onboarding.help.title", "Help & Tour")}
        aria-haspopup="true"
        aria-expanded={open}
        data-testid="sidebar-help"
      >
        <HelpCircle className="h-5 w-5 shrink-0" aria-hidden="true" />
        {!collapsed && (
          <span className="ms-3 text-sm font-medium">
            {t("onboarding.help.title", "Help & Tour")}
          </span>
        )}
      </button>

      {/* Dropdown */}
      {open && (
        <div
          className={cn(
            "absolute z-50 mb-2 w-56 rounded-xl border border-sidebar-border bg-sidebar p-1.5 shadow-xl shadow-black/20",
            collapsed ? "inset-s-14 bottom-0" : "inset-s-2 bottom-full"
          )}
          role="menu"
          aria-label={t("onboarding.help.platformTour", "Platform Tour")}
          onKeyDown={handleMenuKeyDown}
          data-testid="help-menu-dropdown"
        >
          <p className="px-3 py-1.5 text-[10px] font-bold uppercase tracking-widest text-sidebar-foreground/40" aria-hidden="true">
            {t("onboarding.help.platformTour", "Platform Tour")}
          </p>
          {ALL_CHAPTERS.map((id) => {
            const chapter = TOUR_CHAPTERS[id];
            const done = completedChapters.has(id);
            return (
              <button
                key={id}
                onClick={() => handleChapterClick(id)}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-start text-sm text-sidebar-foreground transition-colors hover:bg-sidebar-accent/10 focus:bg-sidebar-accent/10"
                role="menuitem"
                tabIndex={-1}
                data-testid={`help-chapter-${id}`}
              >
                <span className="flex-1 truncate">{t(chapter.titleKey)}</span>
                {done ? (
                  <>
                    <Check className="h-3.5 w-3.5 text-emerald-500 shrink-0" aria-hidden="true" />
                    <span className="sr-only">{t("onboarding.help.completed", "Completed")}</span>
                  </>
                ) : (
                  <span className="h-3.5 w-3.5 shrink-0 rounded-full border border-sidebar-foreground/30" aria-hidden="true" />
                )}
              </button>
            );
          })}
          <div className="mt-1 border-t border-sidebar-border pt-1">
            <button
              onClick={() => {
                setOpen(false);
                resetAll();
              }}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs text-sidebar-foreground/50 transition-colors hover:text-sidebar-foreground hover:bg-sidebar-accent/10 focus:bg-sidebar-accent/10"
              role="menuitem"
              tabIndex={-1}
              data-testid="help-reset-all"
            >
              <RotateCcw className="h-3.5 w-3.5" aria-hidden="true" />
              {t("onboarding.help.resetAll", "Reset All Tours")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
