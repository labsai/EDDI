import { useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Command } from "cmdk";
import { useCommandPalette } from "@/hooks/use-command-palette";
import { useAgentDescriptors } from "@/hooks/use-agents";
import {
  LayoutDashboard,
  Bot,
  Workflow,
  Boxes,
  FileCode,
  MessageCircle,
  Sparkles,
  ScrollText,
  MessagesSquare,
  ShieldCheck,
  Users,
  Search,
  Keyboard,
  Clock,
  type LucideIcon,
} from "lucide-react";

// ==================== Navigation Items ====================

interface NavItem {
  path: string;
  label: string;
  icon: LucideIcon;
  section: string;
}

// ==================== Component ====================

export function CommandPalette() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { isOpen, close, toggle, recentPages, addRecentPage } = useCommandPalette();
  const { data: agents } = useAgentDescriptors();

  const PAGES: NavItem[] = [
    { path: "/manage", label: t("nav.dashboard", "Dashboard"), icon: LayoutDashboard, section: "pages" },
    { path: "/manage/agents", label: t("nav.agents", "Agents"), icon: Bot, section: "pages" },
    { path: "/manage/workflows", label: t("nav.packages", "Workflows"), icon: Workflow, section: "pages" },
    { path: "/manage/groups", label: t("nav.groups", "Groups"), icon: Boxes, section: "pages" },
    { path: "/manage/resources", label: t("nav.resources", "Resources"), icon: FileCode, section: "pages" },
    { path: "/manage/chat", label: t("nav.chat", "Chat"), icon: MessageCircle, section: "pages" },

    { path: "/manage/conversations", label: t("nav.conversations", "Conversations"), icon: MessagesSquare, section: "pages" },
    { path: "/manage/logs", label: t("nav.logs", "Logs"), icon: ScrollText, section: "pages" },
    { path: "/manage/audit", label: t("nav.audit", "Audit Trail"), icon: ShieldCheck, section: "pages" },
    { path: "/manage/userdata", label: t("userData.title", "User Data"), icon: Users, section: "pages" },
  ];

  // Global Ctrl+K / ⌘+K handler
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        toggle();
      }
      if (e.key === "Escape" && isOpen) {
        close();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [toggle, close, isOpen]);

  const handleSelect = useCallback(
    (path: string, label: string) => {
      close();
      addRecentPage(path, label);
      navigate(path);
    },
    [close, addRecentPage, navigate],
  );

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[100]">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm animate-in fade-in duration-150"
        onClick={close}
        aria-hidden="true"
      />

      {/* Dialog */}
      <div className="absolute inset-x-0 top-[15%] mx-auto w-full max-w-[580px] animate-in fade-in slide-in-from-top-2 duration-200">
        <Command
          className="rounded-xl border border-border bg-popover shadow-2xl overflow-hidden"
          label={t("commandPalette.placeholder", "Search pages, agents, actions\u2026")}
        >
          {/* Search input */}
          <div className="flex items-center gap-2 border-b border-border px-4">
            <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
            <Command.Input
              placeholder={t("commandPalette.placeholder", "Search pages, agents, actions\u2026")}
              className="h-12 flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
              autoFocus
            />
            <kbd className="hidden shrink-0 rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground sm:inline">
              Esc
            </kbd>
          </div>

          <Command.List className="max-h-[360px] overflow-y-auto p-2">
            <Command.Empty className="py-8 text-center text-sm text-muted-foreground">
              {t("commandPalette.noResults", "No results found.")}
            </Command.Empty>

            {/* Recent */}
            {recentPages.length > 0 && (
              <Command.Group
                heading={
                  <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                    <Clock className="h-3 w-3" />
                    {t("commandPalette.recent", "Recent")}
                  </span>
                }
              >
                {recentPages.map((page) => {
                  const nav = PAGES.find((p) => p.path === page.path);
                  const Icon = nav?.icon ?? Clock;
                  return (
                    <Command.Item
                      key={`recent-${page.path}`}
                      value={`recent ${page.label} ${page.path}`}
                      onSelect={() => handleSelect(page.path, page.label)}
                      className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary"
                    >
                      <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <span>{page.label}</span>
                    </Command.Item>
                  );
                })}
              </Command.Group>
            )}

            {/* Pages */}
            <Command.Group
              heading={
                <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                  <LayoutDashboard className="h-3 w-3" />
                  {t("commandPalette.navigate", "Navigate")}
                </span>
              }
            >
              {PAGES.map((page) => (
                <Command.Item
                  key={page.path}
                  value={`navigate ${page.label} ${page.path}`}
                  onSelect={() => handleSelect(page.path, page.label)}
                  className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary"
                >
                  <page.icon className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <span>{page.label}</span>
                  <span className="ms-auto text-[10px] text-muted-foreground/50 font-mono">
                    {page.path.replace("/manage", "")}
                  </span>
                </Command.Item>
              ))}
            </Command.Group>

            {/* Agents */}
            {agents && agents.length > 0 && (
              <Command.Group
                heading={
                  <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                    <Bot className="h-3 w-3" />
                    {t("commandPalette.agents", "Agents")}
                  </span>
                }
              >
                {agents.slice(0, 10).map((agent) => (
                  <Command.Item
                    key={agent.resource}
                    value={`agent ${agent.name} ${agent.description ?? ""}`}
                    onSelect={() =>
                      handleSelect(
                        `/manage/agents/${encodeURIComponent(agent.resource)}`,
                        agent.name,
                      )
                    }
                    className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary"
                  >
                    <Bot className="h-4 w-4 shrink-0 text-muted-foreground" />
                    <div className="flex-1 min-w-0">
                      <p className="truncate font-medium">{agent.name}</p>
                      {agent.description && (
                        <p className="truncate text-xs text-muted-foreground">
                          {agent.description}
                        </p>
                      )}
                    </div>
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {/* Quick Actions */}
            <Command.Group
              heading={
                <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                  <Sparkles className="h-3 w-3" />
                  {t("commandPalette.actions", "Quick Actions")}
                </span>
              }
            >
              <Command.Item
                value="action new agent create"
                onSelect={() => handleSelect("/manage/agents?action=create", "Create Agent")}
                className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary"
              >
                <Bot className="h-4 w-4 shrink-0 text-emerald-500" />
                <span>{t("commandPalette.createAgent", "Create New Agent")}</span>
              </Command.Item>
              <Command.Item
                value="action open chat start conversation"
                onSelect={() => handleSelect("/manage/chat", "Open Chat")}
                className="flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary"
              >
                <MessageCircle className="h-4 w-4 shrink-0 text-blue-500" />
                <span>{t("commandPalette.openChat", "Open Chat")}</span>
              </Command.Item>
            </Command.Group>
          </Command.List>

          {/* Footer */}
          <div className="flex items-center justify-between border-t border-border px-4 py-2 text-[10px] text-muted-foreground/60">
            <div className="flex items-center gap-3">
              <span className="flex items-center gap-1">
                <Keyboard className="h-3 w-3" />
                <kbd className="rounded border border-border/50 px-1 font-mono">↑↓</kbd>
                {t("commandPalette.kbdNavigate", "navigate")}
              </span>
              <span className="flex items-center gap-1">
                <kbd className="rounded border border-border/50 px-1 font-mono">Enter</kbd>
                {t("commandPalette.kbdSelect", "select")}
              </span>
              <span className="flex items-center gap-1">
                <kbd className="rounded border border-border/50 px-1 font-mono">Esc</kbd>
                {t("commandPalette.kbdClose", "close")}
              </span>
            </div>
            <span className="font-mono">Ctrl+K</span>
          </div>
        </Command>
      </div>
    </div>
  );
}
