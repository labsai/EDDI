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
  BarChart3,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
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
      aria-label={t("boardroom.sidebar.boards", "Board navigation")}
      className={cn(
        "flex h-full flex-col border-e transition-all duration-250 ease-in-out",
        "border-border bg-card",
        collapsed ? "w-16" : "w-70",
      )}
    >
      {/* ── Header ────────────────────────────────────────────── */}
      <div className="flex h-14 shrink-0 items-center gap-2 border-b border-border ps-3 pe-3">
        {!collapsed && (
          <Link to="/boardroom" className="flex flex-1 items-center gap-2 truncate">
            <img
              src="/logo_eddi.png"
              alt="EDDI"
              className="h-6 w-auto"
            />
          </Link>
        )}
        {collapsed && (
          <Link to="/boardroom" className="flex items-center justify-center mx-auto" aria-label="EDDI">
            <img
              src="/eddi-icon.svg"
              alt="EDDI"
              className="h-7 w-7 rounded-md"
            />
          </Link>
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
      <div className="shrink-0 ps-3 pe-3 py-3">
        <Button
          asChild
          className={cn(
            "w-full bg-primary text-primary-foreground hover:bg-primary/90",
            collapsed && "ps-0 pe-0",
          )}
          size={collapsed ? "icon" : "md"}
        >
          <Link to="/boardroom/new">
            <Plus className="h-4 w-4" />
            {!collapsed && (
              <span>{t("boardroom.newBoard", "Assemble Task Force")}</span>
            )}
          </Link>
        </Button>
      </div>

      {/* ── Insights Link ─────────────────────────────────────── */}
      <div className="shrink-0 ps-3 pe-3 pb-1">
        <Link
          to="/boardroom/analytics"
          className={cn(
            "flex w-full items-center gap-2 rounded-lg ps-2 pe-2 py-2 text-sm transition-colors",
            "text-muted-foreground hover:bg-muted hover:text-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
            collapsed && "justify-center ps-0 pe-0",
          )}
          title={collapsed ? t("boardroom.insights", "Insights") : undefined}
        >
          <BarChart3 className="h-4 w-4 shrink-0" />
          {!collapsed && (
            <span>{t("boardroom.insights", "Insights")}</span>
          )}
        </Link>
      </div>

      {/* ── Workforce ──────────────────────────────────────────── */}
      <WorkforceSection collapsed={collapsed} boardId={boardId} />

      {/* ── Boards List ───────────────────────────────────────── */}
      <nav aria-label={t("boardroom.boardList", "Task Force list")} className="flex-1 overflow-y-auto ps-2 pe-2 pb-2">
        {!collapsed && (
          <p className="ps-2 pe-2 pb-1 pt-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {t("boardroom.boardsLabel", "Task Forces")}
          </p>
        )}

        <ul className="flex flex-col gap-0.5" aria-label={t("boardroom.boardList", "Task Force list")}>
          {boards?.map((board) => {
            const isActive = board.id === boardId;
            const styleKey = (board.style ?? "ROUND_TABLE") as DiscussionStyle;
            const style = STYLE_INFO[styleKey] ?? STYLE_INFO.ROUND_TABLE;

            return (
              <li key={board.id}>
                <button
                  type="button"
                  onClick={() =>
                    navigate(`/boardroom/${board.id}?version=${board.version ?? 1}`)
                  }
                  title={collapsed ? board.name : undefined}
                  aria-current={isActive ? "page" : undefined}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-lg ps-2 pe-2 py-2 text-start text-sm transition-colors",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                    isActive
                      ? "bg-primary/10 text-primary"
                      : "text-foreground/80 hover:bg-muted",
                    collapsed && "justify-center ps-0 pe-0",
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
                      <span className="flex shrink-0 items-center gap-0.5 text-xs text-muted-foreground">
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
      </nav>

      {/* ── Footer: Theme Toggle ──────────────────────────────── */}
      <div className="shrink-0 border-t border-border ps-3 pe-3 py-2">
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

// ─── Workforce Sub-Component ─────────────────────────────────────

function WorkforceSection({ collapsed, boardId }: { collapsed: boolean; boardId?: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: rawAgents } = useAgentDescriptors(50);

  const agents = rawAgents ? groupAgentsByName(rawAgents).slice(0, 10) : [];

  if (agents.length === 0) return null;

  return (
    <div className="shrink-0 border-b border-border ps-2 pe-2 pb-2">
      {!collapsed && (
        <p className="ps-2 pe-2 pb-1 pt-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
          {t("boardroom.workforce", "Workforce")}
        </p>
      )}
      <ul className="flex max-h-48 flex-col gap-0.5 overflow-y-auto">
        {agents.map((agent) => {
          const agentId = agent.resource.match(
            /\/agentstore\/agents\/([^?]+)/,
          )?.[1] ?? agent.id;

          return (
            <li key={agent.id}>
              <button
                type="button"
                onClick={() =>
                  boardId
                    ? navigate(`/boardroom/${boardId}/thread/${agentId}`)
                    : navigate("/boardroom/new")
                }
                title={collapsed ? agent.name : undefined}
                className={cn(
                  "flex w-full items-center gap-2 rounded-lg ps-2 pe-2 py-1.5 text-start text-sm transition-colors",
                  "text-foreground/80 hover:bg-muted",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  collapsed && "justify-center ps-0 pe-0",
                )}
              >
                <span
                  className="inline-block h-2 w-2 shrink-0 rounded-full bg-muted-foreground/40"
                  aria-hidden
                />
                {!collapsed && (
                  <span className="min-w-0 flex-1 truncate">
                    {agent.name || agentId}
                  </span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
