import { useState, useCallback, useEffect, useMemo, useRef } from "react";
import { useParams, useSearchParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  Search,
  Trash2,
  MessageSquare,
  ChevronDown,
} from "lucide-react";
import { cn, formatRelativeTime } from "@/lib/utils";
import {
  useGroupConversations,
  useDeleteGroupConversation,
} from "@/hooks/use-groups";
import { ConversationViewer } from "@/components/workforce/conversation-viewer";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import type { GroupConversation, GroupConversationState } from "@/lib/api/groups";

// ─── State Badge Config ──────────────────────────────────────────

const STATE_BADGE: Record<
  GroupConversationState,
  { label: string; variant: "success" | "warning" | "destructive" | "secondary" }
> = {
  COMPLETED: { label: "Completed", variant: "success" },
  IN_PROGRESS: { label: "In Progress", variant: "warning" },
  SYNTHESIZING: { label: "Synthesizing", variant: "warning" },
  CREATED: { label: "Created", variant: "secondary" },
  FAILED: { label: "Failed", variant: "destructive" },
  CANCELLED: { label: "Cancelled", variant: "secondary" },
  AWAITING_APPROVAL: { label: "Awaiting Approval", variant: "warning" },
};

function stateI18nKey(state: GroupConversationState): string {
  const map: Record<string, string> = {
    COMPLETED: "Workforce.history.completed",
    IN_PROGRESS: "Workforce.history.inProgress",
    SYNTHESIZING: "Workforce.history.synthesizing",
    CREATED: "Workforce.history.created",
    FAILED: "Workforce.history.failed",
    CANCELLED: "Workforce.history.cancelled",
    AWAITING_APPROVAL: "Workforce.history.awaitingApproval",
  };
  return map[state] ?? "Workforce.history.created";
}

// ─── Page Size ───────────────────────────────────────────────────

const PAGE_SIZE = 20;

// ─── Conversation List Item ──────────────────────────────────────

function ConversationItem({
  conversation,
  isSelected,
  onSelect,
  onDelete,
}: {
  conversation: GroupConversation;
  isSelected: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  const badgeConfig = STATE_BADGE[conversation.state] ?? STATE_BADGE.CREATED;
  const timestamp = conversation.lastModified
    ? new Date(conversation.lastModified).getTime()
    : conversation.created
      ? new Date(conversation.created).getTime()
      : 0;

  return (
    <div
      role="option"
      aria-selected={isSelected}
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onSelect();
        }
      }}
      className={cn(
        "group relative w-full text-start ps-4 pe-4 py-3 cursor-pointer transition-colors",
        "hover:bg-muted",
        "border-b border-border",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring",
        isSelected &&
          "bg-primary/10 border-s-2 border-s-primary",
      )}
    >
      {/* Question */}
      <p className="text-sm text-foreground line-clamp-2 pe-8">
        {conversation.originalQuestion ||
          t("Workforce.history.untitled", "Untitled Conversation")}
      </p>

      {/* Meta row */}
      <div className="flex items-center gap-2 mt-1.5">
        <Badge variant={badgeConfig.variant} className="text-[10px]">
          {t(stateI18nKey(conversation.state), badgeConfig.label)}
        </Badge>
        {timestamp > 0 && (
          <span className="text-xs text-muted-foreground">
            {formatRelativeTime(timestamp)}
          </span>
        )}
      </div>

      {/* Delete button */}
      <Button
        variant="ghost"
        size="icon"
        className={cn(
          "absolute inset-e-2 top-1/2 -translate-y-1/2 h-7 w-7",
          "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100",
          "text-muted-foreground hover:text-destructive",
          "transition-opacity",
        )}
        onClick={(e) => {
          e.stopPropagation();
          onDelete();
        }}
        aria-label={t("Workforce.history.delete", "Delete conversation")}
      >
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
    </div>
  );
}

// ─── List Skeleton ───────────────────────────────────────────────

function ListSkeleton() {
  return (
    <div className="p-4 space-y-4">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="space-y-2">
          <Skeleton className="h-4 w-3/4" />
          <div className="flex gap-2">
            <Skeleton className="h-4 w-16 rounded-full" />
            <Skeleton className="h-3 w-12" />
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Empty States ────────────────────────────────────────────────

function EmptyList({ hasFilter }: { hasFilter: boolean }) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col items-center justify-center py-16 ps-4 pe-4">
      <div
        className={cn(
          "h-12 w-12 rounded-full flex items-center justify-center mb-4",
          "bg-muted",
        )}
      >
        <MessageSquare className="h-6 w-6 text-muted-foreground" />
      </div>
      <p className="text-sm font-medium text-muted-foreground text-center">
        {hasFilter
          ? t("Workforce.history.noResults", "No conversations match your search")
          : t("Workforce.history.noConversations", "No conversations yet")}
      </p>
      {hasFilter && (
        <p className="text-xs text-muted-foreground mt-1">
          {t("Workforce.history.tryDifferent", "Try a different search term")}
        </p>
      )}
    </div>
  );
}

function EmptyViewer() {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col items-center justify-center h-full ps-4 pe-4">
      <div
        className={cn(
          "h-16 w-16 rounded-full flex items-center justify-center mb-4",
          "bg-muted",
        )}
      >
        <MessageSquare className="h-8 w-8 text-muted-foreground" />
      </div>
      <p className="text-sm font-medium text-muted-foreground">
        {t(
          "Workforce.history.selectConversation",
          "Select a conversation to view",
        )}
      </p>
      <p className="text-xs text-muted-foreground mt-1">
        {t(
          "Workforce.history.selectHint",
          "Choose a conversation from the list",
        )}
      </p>
    </div>
  );
}

// ─── Main Page Component ─────────────────────────────────────────

function WorkforceHistory() {
  const { t } = useTranslation();
  const { boardId } = useParams<{ boardId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();

  // State
  const [selectedId, setSelectedId] = useState<string | null>(
    searchParams.get("conversation") ?? null,
  );
  const [showViewer, setShowViewer] = useState(false);
  const [filterText, setFilterText] = useState("");
  const [page, setPage] = useState(0);
  const [deleteTarget, setDeleteTarget] = useState<GroupConversation | null>(
    null,
  );
  const listRef = useRef<HTMLDivElement>(null);

  // Data
  const { data: conversations, isLoading, isError } = useGroupConversations(
    boardId ?? "",
    PAGE_SIZE * (page + 1),
    0,
  );
  const { mutate: deleteConversation, isPending: isDeleting } = useDeleteGroupConversation();

  // Sync selected ID to URL search params
  useEffect(() => {
    const current = searchParams.get("conversation");
    if (selectedId === current) return;
    setSearchParams((prev) => {
      if (selectedId) {
        prev.set("conversation", selectedId);
      } else {
        prev.delete("conversation");
      }
      return prev;
    }, { replace: true });
  }, [selectedId, searchParams, setSearchParams]);

  // Filter + sort conversations (newest first)
  const filteredConversations = useMemo(() => {
    if (!conversations) return [];
    const lower = filterText.toLowerCase().trim();
    const filtered = lower
      ? conversations.filter((c) =>
          c.originalQuestion?.toLowerCase().includes(lower),
        )
      : conversations;

    return [...filtered].sort((a, b) => {
      const aTime = a.lastModified
        ? new Date(a.lastModified).getTime()
        : a.created
          ? new Date(a.created).getTime()
          : 0;
      const bTime = b.lastModified
        ? new Date(b.lastModified).getTime()
        : b.created
          ? new Date(b.created).getTime()
          : 0;
      return bTime - aTime;
    });
  }, [conversations, filterText]);

  // Keyboard navigation
  const handleListKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!filteredConversations.length) return;
      const currentIdx = filteredConversations.findIndex(
        (c) => c.id === selectedId,
      );

      if (e.key === "ArrowDown") {
        e.preventDefault();
        const nextIdx = Math.min(
          currentIdx + 1,
          filteredConversations.length - 1,
        );
        const next = filteredConversations[nextIdx];
        if (next) {
          setSelectedId(next.id);
          setShowViewer(true);
        }
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        const prevIdx = Math.max(currentIdx - 1, 0);
        const prev = filteredConversations[prevIdx];
        if (prev) {
          setSelectedId(prev.id);
          setShowViewer(true);
        }
      }
    },
    [filteredConversations, selectedId],
  );

  const handleSelect = useCallback(
    (convId: string) => {
      setSelectedId(convId);
      setShowViewer(true);
    },
    [],
  );

  const handleDelete = useCallback(() => {
    if (!deleteTarget || !boardId) return;
    deleteConversation(
      { groupId: boardId, conversationId: deleteTarget.id },
      {
        onSuccess: () => {
          if (selectedId === deleteTarget.id) {
            setSelectedId(null);
            setShowViewer(false);
          }
          setDeleteTarget(null);
        },
      },
    );
  }, [deleteTarget, boardId, deleteConversation, selectedId]);

  const handleLoadMore = useCallback(() => {
    setPage((prev) => prev + 1);
  }, []);

  if (!boardId) return null;

  return (
    <div className="flex flex-col h-full">
      {/* ── Top Bar ──────────────────────────────────────────── */}
      <div
        className={cn(
          "flex items-center gap-3 ps-4 pe-4 py-3",
          "border-b border-border",
          "bg-card/80 backdrop-blur-sm",
        )}
      >
        <Button
          variant="ghost"
          size="sm"
          className="gap-1.5 text-muted-foreground"
          asChild
        >
          <Link to={`/workforce/${boardId}`}>
            <ArrowLeft className="h-4 w-4" />
            {t("Workforce.history.backToBoard", "Back to Task Force")}
          </Link>
        </Button>
        <div className="flex-1" />
        <h1 className="text-sm font-semibold text-foreground">
          {t("Workforce.history.title", "Conversation History")}
        </h1>
      </div>

      {/* ── Split View ───────────────────────────────────────── */}
      <div className="flex-1 min-h-0 lg:grid lg:grid-cols-[320px_1fr]">
        {/* ── Left Panel: Conversation List ─────────────────── */}
        <div
          ref={listRef}
          onKeyDown={handleListKeyDown}
          className={cn(
            "flex flex-col h-full border-e border-border",
            "bg-card",
            // On mobile, hide list when viewer is shown
            showViewer && "hidden lg:flex",
          )}
        >
          {/* Search input */}
          <div className="ps-3 pe-3 py-2 border-b border-border">
            <div
              className={cn(
                "flex items-center gap-2 ps-3 pe-3 py-1.5 rounded-lg",
                "bg-muted",
              )}
            >
              <Search className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
              <input
                type="text"
                placeholder={t(
                  "Workforce.history.searchPlaceholder",
                  "Search conversations…",
                )}
                value={filterText}
                aria-label={t("Workforce.history.searchPlaceholder", "Search conversations…")}
                onChange={(e) => {
                  setFilterText(e.target.value);
                  setPage(0);
                }}
                className={cn(
                  "flex-1 bg-transparent text-sm outline-none",
                  "text-foreground",
                  "placeholder:text-muted-foreground",
                )}
              />
            </div>
          </div>

          {/* Conversation list */}
          <div className="flex-1 overflow-y-auto" role="listbox" aria-label={t("Workforce.history.conversationList", "Conversation list")}>
            {isLoading && <ListSkeleton />}

            {!isLoading && isError && (
              <div className="flex flex-col items-center justify-center py-12 ps-4 pe-4">
                <p className="text-sm text-destructive">
                  {t("Workforce.history.loadError", "Failed to load conversations")}
                </p>
              </div>
            )}

            {!isLoading && filteredConversations.length === 0 && (
              <EmptyList hasFilter={filterText.trim().length > 0} />
            )}

            {!isLoading &&
              filteredConversations.map((conv) => (
                <ConversationItem
                  key={conv.id}
                  conversation={conv}
                  isSelected={conv.id === selectedId}
                  onSelect={() => handleSelect(conv.id)}
                  onDelete={() => setDeleteTarget(conv)}
                />
              ))}

            {/* Load more */}
            {!isLoading &&
              conversations &&
              conversations.length >= PAGE_SIZE * (page + 1) && (
                <div className="p-3">
                  <Button
                    variant="outline"
                    size="sm"
                    className="w-full gap-1.5"
                    onClick={handleLoadMore}
                  >
                    <ChevronDown className="h-3.5 w-3.5" />
                    {t("Workforce.history.loadMore", "Load more")}
                  </Button>
                </div>
              )}
          </div>
        </div>

        {/* ── Right Panel: Conversation Viewer ──────────────── */}
        <div
          className={cn(
            "flex flex-col h-full",
            "bg-card",
            // On mobile, hide viewer when list is shown
            !showViewer && "hidden lg:flex",
          )}
        >
          {/* Mobile back button */}
          {showViewer && (
            <div className="lg:hidden ps-3 pe-3 py-2 border-b border-border">
              <Button
                variant="ghost"
                size="sm"
                className="gap-1.5 text-muted-foreground"
                onClick={() => setShowViewer(false)}
              >
                <ArrowLeft className="h-4 w-4" />
                {t("Workforce.history.backToList", "Back to list")}
              </Button>
            </div>
          )}

          {selectedId ? (
            <ConversationViewer
              groupId={boardId}
              conversationId={selectedId}
              onClose={() => {
                setSelectedId(null);
                setShowViewer(false);
              }}
              className="flex-1 min-h-0"
            />
          ) : (
            <EmptyViewer />
          )}
        </div>
      </div>

      {/* ── Delete Confirmation Dialog ───────────────────────── */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title={t(
          "Workforce.history.deleteTitle",
          "Delete Conversation",
        )}
        description={t(
          "Workforce.history.deleteDescription",
          "Are you sure you want to delete this conversation? This action cannot be undone.",
        )}
        confirmLabel={t("Workforce.history.confirmDelete", "Delete")}
        cancelLabel={t("Workforce.history.cancel", "Cancel")}
        onConfirm={handleDelete}
        variant="destructive"
        isPending={isDeleting}
      />
    </div>
  );
}

export { WorkforceHistory };
