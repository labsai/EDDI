import { useCallback, useId, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Bot, Brain, Globe, Lock, RefreshCw, Search, Trash2, Users } from "lucide-react";
import { Button } from "@eddi/ui";
import { cn } from "@eddi/app-kit/lib/utils";
import { getErrorMessage } from "@eddi/app-kit/lib/api-client";
import { AlertDialog } from "@eddi/app-kit/components/ui/alert-dialog";
import { Skeleton } from "@eddi/app-kit/components/ui/skeleton";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import {
  useDeleteAllMemories,
  useDeleteMemory,
  useUserMemories,
} from "@eddi/app-kit/hooks/use-user-memory";
import { useCatalogAgents, useCatalogGroups } from "@/hooks/use-catalog";
import {
  filterMemoryRows,
  memoryKeyLabel,
  toMemoryRows,
  type MemoryRow,
} from "@/lib/memory-entries";

/**
 * "What I remember about you" (frontend unification plan, T2.4): the signed-in
 * person's own persistent memory — what their agents saved about them with
 * `rememberFact` or a `longTerm` property — with who saved it, who may read
 * it, search, forget one, and forget everything.
 *
 * It reads `GET /usermemorystore/memories/{userId}` for the caller's own
 * principal name; the backend answers 403 for anyone else's
 * (`OwnershipValidator`), so the page can only ever show the person their own.
 * The person may delete any of their entries — private, team and shared ones
 * alike — except the GDPR bookkeeping rows, which are not memories and are not
 * listed (`isReservedMemoryKey`).
 */
export function WorkforceMemory() {
  const { t } = useTranslation();
  const { user, method } = useAuth();
  // The backend's principal name is the token's preferred_username here; with
  // sign-in off there is no person, and so no personal memory.
  const userId = method === "none" ? "" : (user?.username ?? "").trim();

  if (!userId) {
    return (
      <MemoryFrame>
        <p className="rounded-lg border border-border bg-muted/40 p-4 text-sm text-muted-foreground" data-testid="memory-no-user">
          {t("Workforce.memory.noSignIn")}
        </p>
      </MemoryFrame>
    );
  }
  return <MemoryList userId={userId} />;
}

function MemoryFrame({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="mx-auto w-full max-w-3xl space-y-5 px-4 py-6" data-testid="workforce-memory">
      <header>
        <h1 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <Brain className="h-6 w-6 text-primary" aria-hidden="true" />
          {t("Workforce.memory.title")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">{t("Workforce.memory.subtitle")}</p>
      </header>
      {children}
    </div>
  );
}

function MemoryList({ userId }: { userId: string }) {
  const { t, i18n } = useTranslation();
  const searchId = useId();
  const { data: memories, isLoading, isError, refetch, isFetching } = useUserMemories(userId);
  const { data: agents } = useCatalogAgents();
  const { data: teams } = useCatalogGroups();
  const deleteOne = useDeleteMemory();
  const deleteAll = useDeleteAllMemories();
  const [query, setQuery] = useState("");
  const [confirmAll, setConfirmAll] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const agentNames = useMemo(
    () => new Map((agents ?? []).map((a) => [a.agentId, a.name?.trim() || a.agentId])),
    [agents],
  );
  const teamNames = useMemo(
    () => new Map((teams ?? []).map((g) => [g.groupId, g.name?.trim() || g.groupId])),
    [teams],
  );
  const { rows, reservedCount } = useMemo(
    () => toMemoryRows(memories ?? [], agentNames, teamNames),
    [memories, agentNames, teamNames],
  );
  const shown = useMemo(() => filterMemoryRows(rows, query), [rows, query]);
  const dateFormat = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { dateStyle: "medium" }),
    [i18n.language],
  );

  /** After a row goes, focus the row that took its place, else the search box. */
  const focusAfterDelete = useCallback((index: number) => {
    requestAnimationFrame(() => {
      const buttons = listRef.current?.querySelectorAll<HTMLButtonElement>('[data-testid="memory-forget"]');
      if (buttons && buttons.length > 0) buttons[Math.min(index, buttons.length - 1)]?.focus();
      else searchRef.current?.focus();
    });
  }, []);

  const forget = useCallback(
    (row: MemoryRow, index: number) => {
      if (!row.id || deletingId) return;
      setDeletingId(row.id);
      deleteOne.mutate(row.id, {
        onSuccess: () => {
          toast.success(t("Workforce.memory.forgotten", { key: memoryKeyLabel(row.entry.key) }));
          focusAfterDelete(index);
        },
        onError: (err) => toast.error(t("Workforce.memory.forgetFailed", { message: getErrorMessage(err) })),
        onSettled: () => setDeletingId(null),
      });
    },
    [deleteOne, deletingId, focusAfterDelete, t],
  );

  const forgetEverything = useCallback(() => {
    deleteAll.mutate(userId, {
      onSuccess: () => {
        setConfirmAll(false);
        setQuery("");
        toast.success(t("Workforce.memory.forgetAllDone"));
      },
      onError: (err) => toast.error(t("Workforce.memory.forgetFailed", { message: getErrorMessage(err) })),
    });
  }, [deleteAll, userId, t]);

  return (
    <MemoryFrame>
      <p className="text-sm text-muted-foreground" data-testid="memory-explainer">
        {t("Workforce.memory.explainer")}
      </p>

      {isLoading ? (
        <div className="space-y-2" data-testid="memory-loading">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : isError ? (
        <div className="flex items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm" role="alert" data-testid="memory-error">
          <span className="flex-1">{t("Workforce.memory.loadFailed")}</span>
          <Button variant="outline" size="sm" onClick={() => void refetch()} disabled={isFetching}>
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            {t("Workforce.memory.retry")}
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <>
          <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground" data-testid="memory-empty">
            {t("Workforce.memory.empty")}
          </p>
          {reservedCount > 0 && <ReservedNote />}
        </>
      ) : (
        <>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <label htmlFor={searchId} className="sr-only">
                {t("Workforce.memory.search")}
              </label>
              <Search className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <input
                id={searchId}
                ref={searchRef}
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("Workforce.memory.search")}
                dir="auto"
                className="h-10 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="memory-search"
              />
            </div>
            <Button
              variant="outline"
              className="gap-1.5 text-destructive hover:text-destructive"
              onClick={() => setConfirmAll(true)}
              data-testid="memory-forget-all"
            >
              <Trash2 className="h-4 w-4" aria-hidden="true" />
              {t("Workforce.memory.forgetAll")}
            </Button>
          </div>

          <p className="text-xs text-muted-foreground" role="status" aria-live="polite" data-testid="memory-count">
            {query.trim()
              ? t("Workforce.memory.shown", { shown: shown.length, total: rows.length })
              : t("Workforce.memory.total", { n: rows.length })}
          </p>

          {shown.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground" data-testid="memory-no-match">
              {t("Workforce.memory.noMatch")}
            </p>
          ) : (
            <ul ref={listRef} className="space-y-2" aria-label={t("Workforce.memory.title")} data-testid="memory-list">
              {shown.map((row, index) => (
                <MemoryItem
                  key={row.id ?? `${row.entry.key}-${index}`}
                  row={row}
                  updated={row.updatedMs ? dateFormat.format(row.updatedMs) : null}
                  deleting={deletingId !== null && deletingId === row.id}
                  disabled={deletingId !== null || deleteAll.isPending}
                  onForget={() => forget(row, index)}
                />
              ))}
            </ul>
          )}
          {reservedCount > 0 && <ReservedNote />}
        </>
      )}

      <AlertDialog
        open={confirmAll}
        onOpenChange={setConfirmAll}
        title={t("Workforce.memory.forgetAllTitle")}
        description={t("Workforce.memory.forgetAllDescription", { n: rows.length })}
        confirmLabel={t("Workforce.memory.forgetAllConfirm")}
        onConfirm={forgetEverything}
        isPending={deleteAll.isPending}
        variant="destructive"
      />
    </MemoryFrame>
  );
}

function ReservedNote() {
  const { t } = useTranslation();
  return (
    <p className="text-xs text-muted-foreground" data-testid="memory-reserved-note">
      {t("Workforce.memory.reserved")}
    </p>
  );
}

const VISIBILITY_ICON = { self: Lock, group: Users, global: Globe, unknown: Lock } as const;

function MemoryItem({
  row,
  updated,
  deleting,
  disabled,
  onForget,
}: {
  row: MemoryRow;
  updated: string | null;
  deleting: boolean;
  disabled: boolean;
  onForget: () => void;
}) {
  const { t } = useTranslation();
  const label = memoryKeyLabel(row.entry.key);
  const Icon = VISIBILITY_ICON[row.visibility];
  const badge =
    row.visibility === "self"
      ? t("Workforce.memory.badge.self")
      : row.visibility === "group"
        ? t("Workforce.memory.badge.group")
        : row.visibility === "global"
          ? t("Workforce.memory.badge.global")
          : row.entry.visibility;
  const scope =
    row.visibility === "self"
      ? t("Workforce.memory.visibility.self")
      : row.visibility === "group"
        ? row.teamNames.length > 0
          ? t("Workforce.memory.visibility.group", { teams: row.teamNames.join(", ") })
          : t("Workforce.memory.visibility.groupUnknown")
        : row.visibility === "global"
          ? t("Workforce.memory.visibility.global")
          : t("Workforce.memory.visibility.unknown", { value: row.entry.visibility });

  return (
    <li
      className="flex items-start gap-3 rounded-lg border border-border bg-card p-3"
      data-testid="memory-item"
      data-visibility={row.visibility}
    >
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium text-foreground" dir="auto">
            {label}
          </span>
          <span
            className={cn(
              "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium",
              row.visibility === "global"
                ? "bg-primary/10 text-primary"
                : row.visibility === "group"
                  ? "bg-accent text-accent-foreground"
                  : "bg-muted text-muted-foreground",
            )}
            data-testid="memory-visibility"
          >
            <Icon className="h-3 w-3" aria-hidden="true" />
            {badge}
          </span>
        </div>
        {/* A text child: a stored value is shown, never interpreted. */}
        <p className="whitespace-pre-wrap break-words text-sm text-foreground/90" dir="auto" data-testid="memory-value">
          {row.valueText}
        </p>
        <p className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1" data-testid="memory-agent">
            <Bot className="h-3 w-3" aria-hidden="true" />
            {row.agentName
              ? t("Workforce.memory.writtenBy", { agent: row.agentName })
              : t("Workforce.memory.writtenByUnknown")}
          </span>
          <span data-testid="memory-scope">{scope}</span>
          {updated && <span>{t("Workforce.memory.updated", { date: updated })}</span>}
        </p>
      </div>
      {row.id && (
        <Button
          variant="ghost"
          size="sm"
          className="shrink-0 gap-1 text-muted-foreground hover:text-destructive"
          onClick={onForget}
          disabled={disabled}
          aria-busy={deleting || undefined}
          aria-label={t("Workforce.memory.forgetAria", { key: label })}
          data-testid="memory-forget"
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
          <span className="hidden sm:inline">{t("Workforce.memory.forget")}</span>
        </Button>
      )}
    </li>
  );
}
