import { useId, useMemo, useState, type KeyboardEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Bot, MessageSquare, MessageSquarePlus, Search, Users } from "lucide-react";
import { cn } from "@eddi/app-kit/lib/utils";
import { isImeComposing } from "@eddi/app-kit/lib/ime";
import { useDeployedAgents } from "@eddi/app-kit/hooks/use-chat";
import { useWorkforceSessions } from "@/hooks/use-workforce-sessions";
import { sessionLabel } from "@/lib/workforce-sessions";
import { NEW_CHAT_STATE } from "@/lib/workforce-keyboard";

/** One row of the switcher: where it goes and how it reads. */
interface SwitcherItem {
  id: string;
  kind: "new" | "chat" | "team" | "agent";
  label: string;
  detail?: string;
  go: () => void;
}

/** Rows shown without a query, and at most with one. */
const MAX_ITEMS = 30;

/**
 * The ⌘K quick switcher (T1.10): one search box over the sidebar's
 * conversations and the agents the person may use, plus "New chat". Arrows
 * move, Enter opens, Esc closes and puts focus back where it was.
 */
export function QuickSwitcherDialog({ onClose }: { onClose: (restoreFocus: boolean) => void }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const listId = useId();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const { sessions } = useWorkforceSessions();
  const { data: agents } = useDeployedAgents();

  const items = useMemo<SwitcherItem[]>(() => {
    const formatDate = (ms: number) => new Date(ms).toLocaleDateString(i18n.language);
    const open = (to: string, state?: unknown) => () => {
      onClose(false);
      navigate(to, state ? { state } : undefined);
    };
    const all: SwitcherItem[] = [
      { id: "new", kind: "new", label: t("Workforce.switcher.newChat", "New chat"), go: open("/workforce", NEW_CHAT_STATE) },
      ...sessions.map<SwitcherItem>((s) => ({
        id: `${s.kind}:${s.id}`,
        kind: s.kind,
        label: sessionLabel(s, formatDate),
        detail: s.ownerName || s.ownerId,
        go: open(s.href),
      })),
      ...(agents ?? []).map<SwitcherItem>((a) => ({
        id: `agent:${a.id}`,
        kind: "agent",
        label: a.name || a.id,
        detail: t("Workforce.switcher.chatWith", "Chat with this agent"),
        go: open(`/workforce/chat?agentId=${encodeURIComponent(a.id)}`),
      })),
    ];
    const q = query.trim().toLocaleLowerCase();
    const matches = q
      ? all.filter((item) => `${item.label} ${item.detail ?? ""}`.toLocaleLowerCase().includes(q))
      : all;
    return matches.slice(0, MAX_ITEMS);
  }, [sessions, agents, query, t, i18n.language, navigate, onClose]);

  const current = Math.min(active, Math.max(items.length - 1, 0));

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (isImeComposing(e)) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive(Math.min(current + 1, items.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive(Math.max(current - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      items[current]?.go();
    } else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      onClose(true);
    } else if (e.key === "Tab") {
      // One control: Tab would leave a modal dialog for the page behind it.
      e.preventDefault();
    }
  };

  const icon = (kind: SwitcherItem["kind"]) =>
    kind === "new" ? MessageSquarePlus : kind === "team" ? Users : kind === "agent" ? Bot : MessageSquare;

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/40" onClick={() => onClose(true)} aria-hidden="true" />
      <div className="pointer-events-none fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]">
        <div
          role="dialog"
          aria-modal="true"
          aria-label={t("Workforce.switcher.title", "Go to a conversation or agent")}
          className="pointer-events-auto w-full max-w-lg overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
          data-testid="quick-switcher"
        >
          <div className="flex items-center gap-2 border-b border-border px-3">
            <Search className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input
              autoFocus
              type="text"
              role="combobox"
              aria-expanded="true"
              aria-controls={listId}
              aria-autocomplete="list"
              aria-activedescendant={items[current] ? `${listId}-${current}` : undefined}
              aria-label={t("Workforce.switcher.search", "Search conversations and agents")}
              placeholder={t("Workforce.switcher.search", "Search conversations and agents")}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setActive(0);
              }}
              onKeyDown={onKeyDown}
              dir="auto"
              className="h-12 w-full bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
              data-testid="quick-switcher-input"
            />
            <kbd className="rounded border border-border bg-muted px-1.5 text-[10px] text-muted-foreground">Esc</kbd>
          </div>
          <ul id={listId} role="listbox" aria-label={t("Workforce.switcher.results", "Results")} className="max-h-[50vh] overflow-y-auto p-1">
            {items.length === 0 ? (
              <li className="px-3 py-6 text-center text-sm text-muted-foreground" data-testid="quick-switcher-empty">
                {t("Workforce.switcher.noResults", "Nothing matches")}
              </li>
            ) : (
              items.map((item, i) => {
                const Icon = icon(item.kind);
                return (
                  <li
                    key={item.id}
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={i === current}
                    onMouseEnter={() => setActive(i)}
                    onClick={item.go}
                    className={cn(
                      "flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-sm",
                      i === current ? "bg-primary/10 text-foreground" : "text-foreground/90",
                    )}
                    data-testid={`quick-switcher-item-${item.kind}`}
                  >
                    <Icon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span className="min-w-0 flex-1 truncate" dir="auto">
                      {item.label}
                    </span>
                    {item.detail && item.kind !== "new" && (
                      <span className="max-w-[40%] truncate text-xs text-muted-foreground" dir="auto">
                        {item.detail}
                      </span>
                    )}
                  </li>
                );
              })
            )}
          </ul>
        </div>
      </div>
    </>
  );
}
