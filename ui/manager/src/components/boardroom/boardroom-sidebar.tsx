import { useParams, useNavigate, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Sun,
  Moon,
  X,
  Users,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { useTheme } from "@/components/layout/theme-provider";
import { STYLE_INFO, type DiscussionStyle } from "@/lib/api/groups";

// ─── Types ───────────────────────────────────────────────────────

export interface BoardroomSidebarProps {
  collapsed: boolean;
  onToggle: () => void;
  onClose?: () => void;
}

// ─── Component ───────────────────────────────────────────────────

export function BoardroomSidebar({
  collapsed,
  onToggle,
  onClose,
}: BoardroomSidebarProps) {
  const { boardId } = useParams<{ boardId: string }>();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { theme, setTheme } = useTheme();

  const { data: boards } = useEnrichedGroupDescriptors(50);

  const cycleTheme = () => {
    const order: Array<"light" | "dark" | "system"> = [
      "light",
      "dark",
      "system",
    ];
    const idx = order.indexOf(theme);
    setTheme(order[(idx + 1) % order.length]!);
  };

  return (
    <aside
      className={cn(
        "flex h-full flex-col border-e transition-all duration-250 ease-in-out",
        "border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900",
        collapsed ? "w-16" : "w-70",
      )}
    >
      {/* ── Header ────────────────────────────────────────────── */}
      <div className="flex h-14 shrink-0 items-center gap-2 border-b border-slate-200 px-3 dark:border-slate-800">
        {!collapsed && (
          <span className="flex-1 truncate text-lg font-semibold text-slate-900 dark:text-slate-50">
            <span className="text-indigo-500 dark:text-indigo-400">✦</span>{" "}
            {t("boardroom.title", "Boardroom")}
          </span>
        )}

        {onClose ? (
          <Button
            variant="ghost"
            size="icon"
            className="ms-auto h-8 w-8"
            onClick={onClose}
            aria-label={t("boardroom.closeSidebar", "Close sidebar")}
          >
            <X className="h-4 w-4" />
          </Button>
        ) : (
          <Button
            variant="ghost"
            size="icon"
            className={cn("h-8 w-8", collapsed && "ms-auto")}
            onClick={onToggle}
            aria-label={t("boardroom.toggleSidebar", "Toggle sidebar")}
          >
            {collapsed ? (
              <PanelLeftOpen className="h-4 w-4" />
            ) : (
              <PanelLeftClose className="h-4 w-4" />
            )}
          </Button>
        )}
      </div>

      {/* ── New Boardroom Button ───────────────────────────────── */}
      <div className="shrink-0 px-3 py-3">
        <Button
          asChild
          className={cn(
            "w-full bg-indigo-500 text-white hover:bg-indigo-600",
            collapsed && "px-0",
          )}
          size={collapsed ? "icon" : "md"}
        >
          <Link to="/boardroom/new">
            <Plus className="h-4 w-4" />
            {!collapsed && (
              <span>{t("boardroom.newBoard", "New Boardroom")}</span>
            )}
          </Link>
        </Button>
      </div>

      {/* ── Boards List ───────────────────────────────────────── */}
      <div className="flex-1 overflow-y-auto px-2 pb-2">
        {!collapsed && (
          <p className="px-2 pb-1 pt-2 text-xs font-medium uppercase tracking-wider text-slate-500 dark:text-slate-400">
            {t("boardroom.boardsLabel", "Boardrooms")}
          </p>
        )}

        <ul className="flex flex-col gap-0.5">
          {boards?.map((board) => {
            const isActive = board.id === boardId;
            const styleKey = (board.style ?? "ROUND_TABLE") as DiscussionStyle;
            const style = STYLE_INFO[styleKey] ?? STYLE_INFO.ROUND_TABLE;

            return (
              <li key={board.id}>
                <button
                  type="button"
                  onClick={() =>
                    navigate(`/boardroom/${board.id}?version=${board.version}`)
                  }
                  title={collapsed ? board.name : undefined}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-lg px-2 py-2 text-start text-sm transition-colors",
                    isActive
                      ? "bg-indigo-50 text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300"
                      : "text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800",
                    collapsed && "justify-center px-0",
                  )}
                >
                  <span className="shrink-0 text-base" role="img" aria-hidden>
                    {style.icon}
                  </span>

                  {!collapsed && (
                    <>
                      <span className="min-w-0 flex-1 truncate">
                        {board.name || t("boardroom.untitled", "Untitled")}
                      </span>
                      <span className="flex shrink-0 items-center gap-0.5 text-xs text-slate-400 dark:text-slate-500">
                        <Users className="h-3 w-3" />
                        {board.memberCount}
                      </span>
                    </>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      {/* ── Footer: Theme Toggle ──────────────────────────────── */}
      <div className="shrink-0 border-t border-slate-200 px-3 py-2 dark:border-slate-800">
        <Button
          variant="ghost"
          size={collapsed ? "icon" : "sm"}
          className={cn("w-full", !collapsed && "justify-start gap-2")}
          onClick={cycleTheme}
          aria-label={t("boardroom.toggleTheme", "Toggle theme")}
        >
          {theme === "dark" ? (
            <Moon className="h-4 w-4" />
          ) : (
            <Sun className="h-4 w-4" />
          )}
          {!collapsed && (
            <span className="capitalize">
              {theme === "system"
                ? t("boardroom.themeSystem", "System")
                : theme === "dark"
                  ? t("boardroom.themeDark", "Dark")
                  : t("boardroom.themeLight", "Light")}
            </span>
          )}
        </Button>
      </div>
    </aside>
  );
}
