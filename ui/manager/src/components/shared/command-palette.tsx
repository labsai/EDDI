import { useEffect, useLayoutEffect, useCallback, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Command } from "cmdk";
import { useCommandPalette } from "@/hooks/use-command-palette";
import { getAgentDescriptors, parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { agentKeys } from "@/lib/query-keys";
import { ALL_PAGES, pageLabel } from "@/lib/route-registry";
import {
  LayoutDashboard,
  Bot,
  MessageCircle,
  Sparkles,
  Search,
  Keyboard,
  Clock,
} from "lucide-react";

// ==================== Agent search ====================

/** How many agents one search asks the server for. */
const AGENT_SEARCH_LIMIT = 30;
/** Quiet period after the last keystroke before the server is asked. */
const SEARCH_DEBOUNCE_MS = 200;

type PaletteAgent = AgentDescriptor & { agentId: string | null };

/**
 * One row per agent: a listing can carry several versions of the same agent, and
 * each rendered as its own palette entry. Keyed by the id in the resource URI;
 * a resource that does not parse is kept as-is rather than throwing, because
 * `parseResourceUri` builds a `new URL()` that rejects a malformed value.
 */
function dedupeAgents(agents: AgentDescriptor[]): PaletteAgent[] {
  const seen = new Set<string>();
  const out: PaletteAgent[] = [];
  for (const agent of agents) {
    let agentId: string | null = null;
    try {
      // parseResourceUri falls back to returning the WHOLE resource string when
      // the URI carries no path, so a value with a slash or colon is not an id.
      const { id } = parseResourceUri(agent.resource);
      if (id && !id.includes("/") && !id.includes(":")) agentId = id;
    } catch {
      /* leave null — selecting it falls back to the agent list */
    }
    const key = agentId ?? agent.resource;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ ...agent, agentId });
  }
  return out;
}

/** Whether a keystroke came from inside a code editor, where Ctrl+K is the editor's chord prefix. */
function isInsideCodeEditor(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(".monaco-editor") !== null;
}

const itemClass =
  "flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-foreground transition-colors data-[selected=true]:bg-primary/10 data-[selected=true]:text-primary";

// ==================== Component ====================

export function CommandPalette() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { isOpen, open, close, toggle, recentPages, addRecentPage } = useCommandPalette();

  // The page list comes from the same registry as the sidebar and the
  // breadcrumb, so a page added there is searchable here without a second edit.
  const pages = useMemo(
    () => ALL_PAGES.map((entry) => ({ ...entry, label: pageLabel(t, entry) })),
    [t],
  );

  // Agents are searched on the server. Filtering the first page of the list in the
  // browser — and only the first ten of that — meant any agent beyond it could
  // not be found at all.
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search]);
  useEffect(() => {
    if (!isOpen) {
      setSearch("");
      setDebouncedSearch("");
    }
  }, [isOpen]);

  // Only while the palette is open: it is mounted on every screen, so an
  // unconditional query fetched the agent list on each page load.
  const { data: agentRows } = useQuery({
    queryKey: agentKeys.descriptors(AGENT_SEARCH_LIMIT, 0, debouncedSearch),
    queryFn: () => getAgentDescriptors(AGENT_SEARCH_LIMIT, 0, debouncedSearch),
    enabled: isOpen,
    staleTime: 30_000,
  });
  const agents = useMemo(() => dedupeAgents(agentRows ?? []), [agentRows]);

  // Focus return. Radix hands focus back to the dialog's *trigger* on close, and
  // this dialog has none (it opens from a global hotkey), so without this focus
  // fell to <body> and a keyboard user lost their place. A layout effect, because
  // it has to read the active element before the dialog's own focus scope moves it.
  const openerRef = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (isOpen) {
      openerRef.current = document.activeElement as HTMLElement | null;
      return;
    }
    const opener = openerRef.current;
    openerRef.current = null;
    if (!opener) return;
    const timer = setTimeout(() => {
      const active = document.activeElement;
      if (opener.isConnected && (!active || active === document.body)) opener.focus();
    }, 0);
    return () => clearTimeout(timer);
  }, [isOpen]);

  // Global Ctrl+K / ⌘+K handler. Escape, the focus trap and focus return belong
  // to the dialog.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        // A focused code editor keeps its own Ctrl+K chords.
        if (isInsideCodeEditor(e.target)) return;
        e.preventDefault();
        toggle();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [toggle]);

  const handleSelect = useCallback(
    (path: string, label: string) => {
      close();
      addRecentPage(path, label);
      navigate(path);
    },
    [close, addRecentPage, navigate],
  );

  const placeholder = t("commandPalette.placeholder", "Search pages, agents, actions…");

  return (
    <Command.Dialog
      open={isOpen}
      onOpenChange={(next) => (next ? open() : close())}
      label={placeholder}
      overlayClassName="fixed inset-0 z-[100] bg-black/50 backdrop-blur-sm animate-in fade-in duration-150"
      contentClassName="fixed inset-x-0 top-[15%] z-[100] mx-auto w-full max-w-[580px] animate-in fade-in slide-in-from-top-2 duration-200"
      className="overflow-hidden rounded-xl border border-border bg-popover shadow-2xl"
    >
      {/* Search input */}
      <div className="flex items-center gap-2 border-b border-border px-4">
        <Search className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <Command.Input
          placeholder={placeholder}
          value={search}
          onValueChange={setSearch}
          className="h-12 flex-1 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
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
                <Clock className="h-3 w-3" aria-hidden="true" />
                {t("commandPalette.recent", "Recent")}
              </span>
            }
          >
            {recentPages.map((page) => {
              const nav = pages.find((p) => p.path === page.path);
              const Icon = nav?.icon ?? Clock;
              return (
                <Command.Item
                  key={`recent-${page.path}`}
                  value={`recent ${page.label} ${page.path}`}
                  onSelect={() => handleSelect(page.path, page.label)}
                  className={itemClass}
                >
                  <Icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
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
              <LayoutDashboard className="h-3 w-3" aria-hidden="true" />
              {t("commandPalette.navigate", "Navigate")}
            </span>
          }
        >
          {pages.map((page) => (
            <Command.Item
              key={page.path}
              value={`navigate ${page.label} ${page.path}`}
              onSelect={() => handleSelect(page.path, page.label)}
              className={itemClass}
            >
              <page.icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <span>{page.label}</span>
              <span className="ms-auto font-mono text-[10px] text-muted-foreground/50" dir="ltr">
                {page.path.replace(/^\/(manage)?/, "/")}
              </span>
            </Command.Item>
          ))}
        </Command.Group>

        {/* Agents */}
        {agents.length > 0 && (
          <Command.Group
            heading={
              <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/70">
                <Bot className="h-3 w-3" aria-hidden="true" />
                {t("commandPalette.agents", "Agents")}
              </span>
            }
          >
            {agents.map((agent) => (
              <Command.Item
                key={agent.agentId ?? agent.resource}
                // The agent id is in the value so two agents with the same name
                // stay distinct entries.
                value={`agent ${agent.name} ${agent.description ?? ""} ${agent.agentId ?? ""}`}
                onSelect={() => {
                  // Agent detail lives at /manage/agentview/:id, and :id is the
                  // trailing segment of the resource URI — not the URI itself.
                  // An id that did not parse falls back to the agent list.
                  const target = agent.agentId
                    ? `/manage/agentview/${encodeURIComponent(agent.agentId)}`
                    : "/manage/agents";
                  handleSelect(target, agent.name);
                }}
                className={itemClass}
              >
                <Bot className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">{agent.name}</p>
                  {agent.description && (
                    <p className="truncate text-xs text-muted-foreground">{agent.description}</p>
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
              <Sparkles className="h-3 w-3" aria-hidden="true" />
              {t("commandPalette.actions", "Quick Actions")}
            </span>
          }
        >
          <Command.Item
            value="action new agent create"
            onSelect={() =>
              handleSelect("/manage/agents/wizard", t("commandPalette.createAgent", "Create New Agent"))
            }
            className={itemClass}
          >
            <Bot className="h-4 w-4 shrink-0 text-emerald-500" aria-hidden="true" />
            <span>{t("commandPalette.createAgent", "Create New Agent")}</span>
          </Command.Item>
          <Command.Item
            value="action open chat start conversation"
            onSelect={() => handleSelect("/manage/chat", t("commandPalette.openChat", "Open Chat"))}
            className={itemClass}
          >
            <MessageCircle className="h-4 w-4 shrink-0 text-blue-500" aria-hidden="true" />
            <span>{t("commandPalette.openChat", "Open Chat")}</span>
          </Command.Item>
        </Command.Group>
      </Command.List>

      {/* Footer */}
      <div className="flex items-center justify-between border-t border-border px-4 py-2 text-[10px] text-muted-foreground/60">
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1">
            <Keyboard className="h-3 w-3" aria-hidden="true" />
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
    </Command.Dialog>
  );
}
