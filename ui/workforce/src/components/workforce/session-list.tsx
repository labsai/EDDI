import { useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { MessageSquare, Pencil, Pin, PinOff, Trash2, Users } from "lucide-react";
import { AlertDialog } from "@eddi/app-kit/components/ui/alert-dialog";
import { cn } from "@eddi/app-kit/lib/utils";
import { getErrorMessage } from "@eddi/app-kit/lib/api-client";
import { MAX_CONVERSATION_TITLE_LENGTH } from "@eddi/app-kit/lib/api/conversations";
import { isImeComposing } from "@eddi/app-kit/lib/ime";
import {
  groupSessions,
  sessionLabel,
  type SessionGroup,
  type WorkforceSession,
} from "@/lib/workforce-sessions";
import {
  useDeleteChat,
  useDeleteTeamSession,
  usePatchChat,
  useWorkforceSessions,
} from "@/hooks/use-workforce-sessions";
import { ConversationSearch } from "@/components/workforce/conversation-search";

/**
 * The sidebar's list of the signed-in person's chats and team sessions
 * (T1.1): pinned on top, then Today, Yesterday, Previous 7 days, Previous 30
 * days and one group per older month. A chat can be renamed inline, pinned and
 * deleted; a team session can be deleted (the backend keeps no title or pin
 * for one — its question is its title).
 */
export function SessionList() {
  const { t, i18n } = useTranslation();
  const { sessions, isLoading, isError, refetch } = useWorkforceSessions();
  const groups = useMemo(() => groupSessions(sessions, Date.now()), [sessions]);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<WorkforceSession | null>(null);
  const patchChat = usePatchChat();
  const deleteChat = useDeleteChat();
  const deleteTeam = useDeleteTeamSession();
  const location = useLocation();
  const activeHref = location.pathname + location.search;

  const formatDate = (ms: number) =>
    new Intl.DateTimeFormat(i18n.language, { month: "short", day: "numeric" }).format(new Date(ms));

  const groupTitle = (group: SessionGroup): string => {
    switch (group.bucket) {
      case "pinned":
        return t("Workforce.sessions.pinned", "Pinned");
      case "today":
        return t("Workforce.sessions.today", "Today");
      case "yesterday":
        return t("Workforce.sessions.yesterday", "Yesterday");
      case "previous7":
        return t("Workforce.sessions.previous7", "Previous 7 days");
      case "previous30":
        return t("Workforce.sessions.previous30", "Previous 30 days");
      default:
        return new Intl.DateTimeFormat(i18n.language, { month: "long", year: "numeric" }).format(
          group.month ?? new Date(),
        );
    }
  };

  const rename = (session: WorkforceSession, title: string) => {
    setRenamingId(null);
    const next = title.replace(/\s+/g, " ").trim();
    if (!next || next === session.title) return;
    patchChat.mutate(
      { conversationId: session.id, patch: { title: next } },
      {
        onError: (err) =>
          toast.error(t("Workforce.sessions.renameFailed", "Couldn't rename: {{error}}", { error: getErrorMessage(err) })),
      },
    );
  };

  const togglePin = (session: WorkforceSession) => {
    patchChat.mutate(
      { conversationId: session.id, patch: { pinned: !session.pinned } },
      {
        onError: (err) =>
          toast.error(t("Workforce.sessions.pinFailed", "Couldn't change the pin: {{error}}", { error: getErrorMessage(err) })),
      },
    );
  };

  const confirmDelete = () => {
    const session = deleting;
    setDeleting(null);
    if (!session) return;
    const onError = (err: unknown) =>
      toast.error(t("Workforce.sessions.deleteFailed", "Couldn't delete: {{error}}", { error: getErrorMessage(err) }));
    if (session.kind === "chat") deleteChat.mutate({ conversationId: session.id }, { onError });
    else deleteTeam.mutate({ groupId: session.ownerId, conversationId: session.id }, { onError });
  };

  return (
    <section aria-label={t("Workforce.sessions.title", "Your conversations")} data-testid="workforce-sessions">
      <p className="ps-2 pe-2 pb-1 pt-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
        {t("Workforce.sessions.title", "Your conversations")}
      </p>
      {/* T1.3: while the box holds text, its results replace the list. */}
      <ConversationSearch>
      {isLoading ? (
        <p className="ps-2 pe-2 py-2 text-xs text-muted-foreground" data-testid="workforce-sessions-loading">
          {t("Workforce.sessions.loading", "Loading…")}
        </p>
      ) : isError ? (
        <button
          type="button"
          onClick={refetch}
          className="ps-2 pe-2 py-2 text-start text-xs text-destructive hover:underline"
          data-testid="workforce-sessions-error"
        >
          {t("Workforce.sessions.loadError", "Couldn't load your conversations. Retry")}
        </button>
      ) : groups.length === 0 ? (
        <p className="ps-2 pe-2 py-2 text-xs text-muted-foreground" data-testid="workforce-sessions-empty">
          {t("Workforce.sessions.empty", "No conversations yet. Start one from Home.")}
        </p>
      ) : (
        groups.map((group) => (
          <div key={group.key} data-testid={`session-group-${group.key}`} className="pb-1">
            <p className="ps-2 pe-2 pt-2 pb-0.5 text-[11px] font-medium text-muted-foreground">{groupTitle(group)}</p>
            <ul className="flex flex-col gap-0.5">
              {group.sessions.map((session) => (
                <SessionRow
                  key={`${session.kind}-${session.id}`}
                  session={session}
                  label={sessionLabel(session, formatDate)}
                  active={activeHref === session.href}
                  renaming={renamingId === session.id}
                  onStartRename={() => setRenamingId(session.id)}
                  onRename={(title) => rename(session, title)}
                  onCancelRename={() => setRenamingId(null)}
                  onTogglePin={() => togglePin(session)}
                  onDelete={() => setDeleting(session)}
                />
              ))}
            </ul>
          </div>
        ))
      )}
      </ConversationSearch>

      <AlertDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t("Workforce.sessions.deleteTitle", "Delete this conversation?")}
        description={t(
          "Workforce.sessions.deleteDescription",
          "“{{title}}” disappears from your list. This cannot be undone here.",
          { title: deleting ? sessionLabel(deleting, formatDate) : "" },
        )}
        confirmLabel={t("Workforce.sessions.delete", "Delete")}
        onConfirm={confirmDelete}
        variant="destructive"
      />
    </section>
  );
}

interface SessionRowProps {
  session: WorkforceSession;
  label: string;
  active: boolean;
  renaming: boolean;
  onStartRename: () => void;
  onRename: (title: string) => void;
  onCancelRename: () => void;
  onTogglePin: () => void;
  onDelete: () => void;
}

function SessionRow({
  session,
  label,
  active,
  renaming,
  onStartRename,
  onRename,
  onCancelRename,
  onTogglePin,
  onDelete,
}: SessionRowProps) {
  const { t } = useTranslation();
  // Enter commits and unmounts the field, and the blur that follows must not
  // commit a second time.
  const committed = useRef(false);
  const commit = (value: string) => {
    if (committed.current) return;
    committed.current = true;
    onRename(value);
  };
  const cancel = () => {
    committed.current = true;
    onCancelRename();
  };
  const Icon = session.kind === "team" ? Users : MessageSquare;
  const testId = `session-${session.kind}-${session.id}`;

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (isImeComposing(e)) return;
    if (e.key === "Enter") {
      e.preventDefault();
      commit(e.currentTarget.value);
    } else if (e.key === "Escape") {
      e.preventDefault();
      cancel();
    }
  };

  if (renaming) {
    return (
      <li className="ps-1 pe-1">
        <input
          autoFocus
          defaultValue={session.title ?? ""}
          maxLength={MAX_CONVERSATION_TITLE_LENGTH}
          onKeyDown={onKeyDown}
          onFocus={() => {
            committed.current = false;
          }}
          onBlur={(e) => commit(e.currentTarget.value)}
          aria-label={t("Workforce.sessions.renameLabel", "Conversation title")}
          className="w-full rounded-md border border-input bg-background ps-2 pe-2 py-1 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          data-testid={`${testId}-rename-input`}
        />
      </li>
    );
  }

  return (
    <li className="group/session relative">
      <Link
        to={session.href}
        className={cn(
          "flex w-full items-center gap-2 rounded-lg ps-2 pe-2 py-1.5 text-sm transition-colors",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          active ? "bg-primary/10 text-primary" : "text-foreground/80 hover:bg-muted",
        )}
        title={label}
        {...(active ? { "aria-current": "page" as const } : {})}
        data-testid={testId}
      >
        <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden />
        <span className="min-w-0 flex-1 truncate" dir="auto" data-testid={`${testId}-label`}>
          {label}
        </span>
      </Link>
      {/* Row actions: visible on hover and on keyboard focus, so a keyboard
          user reaches them by tabbing past the row. */}
      <div
        className={cn(
          "absolute inset-y-0 end-1 flex items-center gap-0.5 rounded-md bg-card/95 ps-1",
          "opacity-0 group-hover/session:opacity-100 focus-within:opacity-100",
        )}
      >
        {session.kind === "chat" && (
          <>
            <RowAction
              label={t("Workforce.sessions.rename", "Rename")}
              onClick={onStartRename}
              testId={`${testId}-rename`}
            >
              <Pencil className="h-3.5 w-3.5" />
            </RowAction>
            <RowAction
              label={session.pinned ? t("Workforce.sessions.unpin", "Unpin") : t("Workforce.sessions.pin", "Pin")}
              onClick={onTogglePin}
              testId={`${testId}-pin`}
              pressed={session.pinned}
            >
              {session.pinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
            </RowAction>
          </>
        )}
        <RowAction label={t("Workforce.sessions.delete", "Delete")} onClick={onDelete} testId={`${testId}-delete`}>
          <Trash2 className="h-3.5 w-3.5" />
        </RowAction>
      </div>
    </li>
  );
}

function RowAction({
  label,
  onClick,
  testId,
  pressed,
  children,
}: {
  label: string;
  onClick: () => void;
  testId: string;
  pressed?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      {...(pressed !== undefined ? { "aria-pressed": pressed } : {})}
      className="flex h-6 w-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      data-testid={testId}
    >
      {children}
    </button>
  );
}
