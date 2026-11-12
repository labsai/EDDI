import { useId, useState, type KeyboardEvent, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Loader2, MessageSquare, Search, X } from "lucide-react";
import { cn } from "@eddi/app-kit/lib/utils";
import { isImeComposing } from "@eddi/app-kit/lib/ime";
import { MAX_CONVERSATION_SEARCH_LENGTH } from "@eddi/app-kit/lib/api/conversations";
import { useConversationSearch, useSearchedSessions } from "@/hooks/use-conversation-search";
import { sessionLabel, type WorkforceSession } from "@/lib/workforce-sessions";

/**
 * "Search my conversations" in the sidebar (T1.3): a combobox over the
 * person's conversation titles. While it holds text, the results take the
 * place of the date-grouped list (`children`); clearing it brings the list
 * back. Arrows move, Enter opens, Esc clears. The count is announced politely
 * once a search settles, and the line under the box says that only titles are
 * searched.
 */
export function ConversationSearch({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const activeHref = location.pathname + location.search;
  const [text, setText] = useState("");
  const [active, setActive] = useState(0);
  const search = useConversationSearch(text);
  const sessions = useSearchedSessions(search);
  const listId = useId();
  const hintId = useId();
  const searching = search.query !== "";
  const current = Math.min(active, Math.max(sessions.length - 1, 0));

  const formatDate = (ms: number) =>
    new Intl.DateTimeFormat(i18n.language, { month: "short", day: "numeric" }).format(new Date(ms));

  const open = (session: WorkforceSession | undefined) => {
    if (session) navigate(session.href);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (isImeComposing(e)) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive(Math.min(current + 1, Math.max(sessions.length - 1, 0)));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive(Math.max(current - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      open(sessions[current]);
    } else if (e.key === "Escape" && text) {
      e.preventDefault();
      e.stopPropagation();
      setText("");
      setActive(0);
    }
  };

  // Said once a search settles, not on every keystroke.
  const announcement =
    search.status === "done"
      ? sessions.length === 0
        ? t("Workforce.search.empty", "No conversation titles match.")
        : t("Workforce.search.found", "Conversations found: {{n}}", { n: sessions.length })
      : search.status === "error"
        ? t("Workforce.search.failed", "The search failed.")
        : "";

  return (
    <>
      <div role="search" className="ps-1 pe-1 pb-1 pt-1" data-testid="conversation-search">
        <div className="relative">
          <Search
            className="pointer-events-none absolute start-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <input
            type="text"
            role="combobox"
            aria-expanded={searching && sessions.length > 0}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={searching && sessions[current] ? `${listId}-${current}` : undefined}
            aria-describedby={hintId}
            aria-label={t("Workforce.search.label", "Search my conversations")}
            placeholder={t("Workforce.search.placeholder", "Search conversation titles")}
            maxLength={MAX_CONVERSATION_SEARCH_LENGTH}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            dir="auto"
            className="h-8 w-full rounded-md border border-input bg-background ps-7 pe-7 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid="conversation-search-input"
          />
          {text && (
            <button
              type="button"
              onClick={() => {
                setText("");
                setActive(0);
              }}
              aria-label={t("Workforce.search.clear", "Clear search")}
              className="absolute end-1 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              data-testid="conversation-search-clear"
            >
              <X className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          )}
        </div>
        <p id={hintId} className="ps-1 pe-1 pt-0.5 text-[11px] text-muted-foreground" data-testid="conversation-search-hint">
          {t("Workforce.search.hint", "Searches titles only, not what was said.")}
        </p>
        <p role="status" aria-live="polite" className="sr-only" data-testid="conversation-search-status">
          {announcement}
        </p>
      </div>

      {!searching ? (
        children
      ) : (
        <section aria-label={t("Workforce.search.results", "Search results")} data-testid="conversation-search-results">
          {search.status === "loading" ? (
            <p className="flex items-center gap-1.5 ps-2 pe-2 py-2 text-xs text-muted-foreground" data-testid="conversation-search-loading">
              <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
              {t("Workforce.search.loading", "Searching…")}
            </p>
          ) : search.status === "error" ? (
            <button
              type="button"
              onClick={search.retry}
              className="ps-2 pe-2 py-2 text-start text-xs text-destructive hover:underline"
              data-testid="conversation-search-error"
            >
              {t("Workforce.search.error", "Couldn't search your conversations. Retry")}
            </button>
          ) : sessions.length === 0 ? (
            <p className="ps-2 pe-2 py-2 text-xs text-muted-foreground" data-testid="conversation-search-empty">
              {t("Workforce.search.empty", "No conversation titles match.")}
            </p>
          ) : (
            <ul
              id={listId}
              role="listbox"
              aria-label={t("Workforce.search.results", "Search results")}
              className="flex flex-col gap-0.5"
            >
              {sessions.map((session, i) => {
                const label = sessionLabel(session, formatDate);
                return (
                  <li
                    key={session.id}
                    id={`${listId}-${i}`}
                    role="option"
                    aria-selected={i === current}
                    aria-current={activeHref === session.href ? "page" : undefined}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => open(session)}
                    title={label}
                    className={cn(
                      "flex cursor-pointer items-center gap-2 rounded-lg ps-2 pe-2 py-1.5 text-sm transition-colors",
                      i === current ? "bg-muted text-foreground" : "text-foreground/80 hover:bg-muted",
                      activeHref === session.href && "text-primary",
                    )}
                    data-testid={`conversation-search-result-${session.id}`}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span className="min-w-0 flex-1 truncate" dir="auto">
                      {label}
                    </span>
                    <span className="max-w-[40%] shrink-0 truncate text-[11px] text-muted-foreground" dir="auto">
                      {session.ownerName}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          {search.status === "done" && (search.hasMore || search.moreFailed) && (
            <button
              type="button"
              onClick={search.moreFailed ? search.retry : search.loadMore}
              disabled={search.loadingMore}
              className={cn(
                "mt-1 flex w-full items-center justify-center gap-1.5 rounded-md ps-2 pe-2 py-1.5 text-xs hover:bg-muted disabled:opacity-60",
                search.moreFailed ? "text-destructive" : "text-muted-foreground",
              )}
              data-testid="conversation-search-more"
            >
              {search.loadingMore && <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />}
              {search.moreFailed
                ? t("Workforce.search.moreFailed", "Couldn't load more. Retry")
                : t("Workforce.search.more", "More results")}
            </button>
          )}
        </section>
      )}
    </>
  );
}
