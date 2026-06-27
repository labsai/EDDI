import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import {
  Users, Trash2, MessageSquareQuote, Clock,
  PanelRightOpen, PanelRightClose,
  PanelLeftOpen, PanelLeftClose,
  Maximize2, Minimize2, History,
} from "lucide-react";
import { toast } from "sonner";
import {
  useGroup,
  useGroupConversations,
  useGroupConversation,
  useDeleteGroupConversation,
} from "@/hooks/use-groups";
import { useGroupDiscussionStream } from "@/hooks/use-group-discussion-stream";
import { DiscussionTranscript } from "@/components/groups/discussion-transcript";
import { DiscussionInput } from "@/components/groups/discussion-input";
import { GroupConfigPanel } from "@/components/groups/group-config-panel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { BackLink } from "@/components/shared/back-link";
import { ErrorState } from "@/components/shared/error-state";
import { cn } from "@/lib/utils";
import { STYLE_INFO, type DiscussionStyle, type AgentGroupConfiguration } from "@/lib/api/groups";
import { STYLE_THEME } from "@/components/groups/discussion-transcript";
import { safeFormatDate } from "@/components/groups/group-utils";

const DEFAULT_STATE = { label: "Created", color: "text-muted-foreground", dot: "bg-muted-foreground" } as const;

const STATE_CONFIG: Record<string, { label: string; color: string; dot: string }> = {
  COMPLETED: { label: "Completed", color: "text-emerald-500", dot: "bg-emerald-500" },
  IN_PROGRESS: { label: "In Progress", color: "text-amber-500", dot: "bg-amber-500" },
  SYNTHESIZING: { label: "Synthesizing", color: "text-amber-500", dot: "bg-amber-500" },
  FAILED: { label: "Failed", color: "text-destructive", dot: "bg-destructive" },
  CREATED: DEFAULT_STATE,
  AWAITING_APPROVAL: { label: "Awaiting Approval", color: "text-orange-500", dot: "bg-orange-500" },
  ERROR: { label: "Error", color: "text-destructive", dot: "bg-destructive" },
};

export function GroupDetailPage() {
  const { id: groupId } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  // Backend requires version — default to 1 if missing from URL (e.g. wizard link).
  // To update after a save: pull setSearchParams from useSearchParams() and call
  // setSearchParams(p => { p.set("version", String(newVersion)); return p }, { replace: true })
  const version = useMemo(
    () => (searchParams.get("version") ? Number(searchParams.get("version")) : 1),
    [searchParams],
  );
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [selectedConvId, setSelectedConvId] = useState<string | null>(null);
  const [showConfig, setShowConfig] = useState(true);
  const [showDiscussions, setShowDiscussions] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

  const {
    data: groupConfig,
    isLoading: configLoading,
    isError: configError,
    refetch: refetchConfig,
  } = useGroup(groupId || "", version);

  const {
    data: conversations,
    isLoading: convsLoading,
  } = useGroupConversations(groupId || "");

  const {
    data: selectedConversation,
    isLoading: convLoading,
  } = useGroupConversation(groupId || "", selectedConvId || "");

  // SSE streaming hook
  const { streamState, startStream, abortStream } = useGroupDiscussionStream();

  const deleteConvMutation = useDeleteGroupConversation();

  // Auto-select the first conversation if none selected and not streaming
  useEffect(() => {
    if (!selectedConvId && !streamState.isStreaming && conversations && conversations.length > 0) {
      setSelectedConvId(conversations[0]!.id);
    }
  }, [conversations, selectedConvId, streamState.isStreaming]);

  const handleStartDiscussion = useCallback((question: string) => {
    if (!groupId) return;
    // Clear the selected conversation so we show the stream instead
    setSelectedConvId(null);
    startStream(groupId, question);
    toast.success(t("groups.discussionStarted", "Discussion started — streaming live"));
  }, [groupId, startStream, t]);

  // Invalidate conversation list when stream starts (so the new entry appears in sidebar)
  // AND when it completes (so the state updates to COMPLETED)
  useEffect(() => {
    if (
      streamState.conversationId &&
      groupId &&
      (streamState.state === "IN_PROGRESS" || streamState.state === "COMPLETED")
    ) {
      queryClient.invalidateQueries({ queryKey: ["groupConversations", groupId] });
    }
    // Auto-select the completed conversation
    if (streamState.state === "COMPLETED" && streamState.conversationId) {
      setSelectedConvId(streamState.conversationId);
    }
  }, [streamState.state, streamState.conversationId, groupId, queryClient]);

  function handleDeleteConversation(convId: string) {
    if (!groupId) return;
    deleteConvMutation.mutate(
      { groupId, conversationId: convId },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          if (selectedConvId === convId) setSelectedConvId(null);
        },
      }
    );
  }

  function handleSelectConversation(convId: string) {
    if (streamState.isStreaming) abortStream();
    setSelectedConvId(convId);
    setHistoryOpen(false);
  }

  if (configLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-[70vh] w-full" />
      </div>
    );
  }

  if (configError || !groupConfig) {
    return (
      <div className="space-y-4">
        <BackLink to="/manage/groups" label={t("groups.backToGroups", "Back to Groups")} />
        <ErrorState message={t("common.error")} onRetry={() => refetchConfig()} retryLabel={t("common.retry")} />
      </div>
    );
  }

  const styleInfo = STYLE_INFO[groupConfig.style] ?? STYLE_INFO.ROUND_TABLE;
  const styleTheme = STYLE_THEME[groupConfig.style as DiscussionStyle] ?? STYLE_THEME.ROUND_TABLE;

  // Normalize: ensure members is always an array so downstream components
  // (GroupConfigPanel, badge, etc.) never crash on null/undefined.
  const safeConfig: AgentGroupConfiguration = groupConfig.members
    ? groupConfig
    : { ...groupConfig, members: [] };

  // Determine whether to show streaming or static transcript
  const isStreamActive = streamState.isStreaming || (streamState.state !== "CREATED" && !selectedConvId);

  const conversationCount = conversations?.length ?? 0;

  // ─── Discussion history list (reused in sidebar + popover) ─────
  const discussionListContent = (
    <>
      {convsLoading ? (
        <div className="p-3 space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : conversationCount > 0 ? (
        <div className="p-1 space-y-0.5">
          {conversations!.map((conv) => (
            <button
              key={conv.id}
              onClick={() => handleSelectConversation(conv.id)}
              className={cn(
                "w-full text-start rounded-lg px-3 py-2 transition-all group/item",
                streamState.isStreaming && conv.id === streamState.conversationId
                  ? "bg-primary/10 border border-primary/30"
                  : selectedConvId === conv.id && !isStreamActive
                    ? "bg-primary/10 border border-primary/30"
                    : "hover:bg-secondary/50 border border-transparent"
              )}
              aria-current={
                (streamState.isStreaming && conv.id === streamState.conversationId) ||
                (selectedConvId === conv.id && !isStreamActive)
                  ? true
                  : undefined
              }
              data-testid={`discussion-item-${conv.id}`}
            >
              <p className="text-xs font-medium text-foreground line-clamp-2">
                {conv.originalQuestion}
              </p>
              <div className="flex items-center gap-1.5 mt-1">
                {(() => {
                  const cfg = STATE_CONFIG[conv.state] ?? DEFAULT_STATE;
                  const isLive = streamState.isStreaming && conv.id === streamState.conversationId;
                  return (
                    <span
                      className={cn("text-[10px] font-medium flex items-center gap-1", cfg.color)}
                      aria-live={isLive ? "polite" : undefined}
                      aria-label={isLive ? t("groups.liveDiscussion", "Live") : t(`groups.state.${conv.state}`, cfg.label)}
                      data-testid={isLive ? "discussion-live-badge" : `discussion-state-${conv.id}`}
                    >
                      <span
                        className={cn("h-1.5 w-1.5 rounded-full shrink-0", isLive ? "bg-current animate-pulse" : cfg.dot)}
                        data-testid={`state-dot-${conv.id}`}
                      />
                      {isLive
                        ? t("groups.liveDiscussion", "Live")
                        : t(`groups.state.${conv.state}`, cfg.label)
                      }
                    </span>
                  );
                })()}
                <span className="text-[10px] text-muted-foreground">
                  {safeFormatDate(conv.created, "date")}
                </span>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteConversation(conv.id);
                  }}
                  className="ms-auto opacity-0 group-hover/item:opacity-100 rounded p-0.5 text-muted-foreground hover:text-destructive transition-all"
                  title={t("common.delete")}
                  aria-label={t("common.delete")}
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </div>
            </button>
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-8 px-4 text-center">
          <MessageSquareQuote className="h-8 w-8 text-muted-foreground/20 mb-2" />
          <p className="text-xs text-muted-foreground">
            {t("groups.noDiscussions", "No discussions yet")}
          </p>
          <p className="text-[10px] text-muted-foreground/50 mt-1">
            {t("groups.askBelow", "Ask a question below to start")}
          </p>
        </div>
      )}
    </>
  );

  return (
    <div className={cn(
      "flex flex-col",
      isFullscreen
        ? "fixed inset-0 z-50 bg-background p-4"
        : "h-[calc(100vh-(--spacing(16))-(--spacing(12)))]"
    )}>
      {/* Header */}
      <div className="flex items-center gap-3 pb-3 border-b border-border shrink-0">
        {!isFullscreen && <BackLink to="/manage/groups" label="" />}
        <Users className="h-6 w-6 text-primary shrink-0" />
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-bold text-foreground truncate">{groupConfig.name}</h1>
          {groupConfig.description && (
            <p className="text-xs text-muted-foreground truncate">{groupConfig.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {styleInfo && (
            <Badge variant="outline" className={cn("hidden sm:inline-flex", styleTheme.flowText)} title={styleInfo.flow}>
              {styleInfo.icon} {styleInfo.label}
            </Badge>
          )}
          <Badge variant="secondary">
            <Users className="me-1 h-3 w-3" />
            {safeConfig.members.length}
          </Badge>

          {/* History dropdown — visible on mobile, and on all sizes when fullscreen */}
          <HistoryDropdown
            historyOpen={historyOpen}
            setHistoryOpen={setHistoryOpen}
            conversationCount={conversationCount}
            isFullscreen={isFullscreen}
          >
            {discussionListContent}
          </HistoryDropdown>

          {/* Discussions panel toggle */}
          {!isFullscreen && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowDiscussions(!showDiscussions)}
              title={showDiscussions ? t("groups.hideDiscussions", "Hide discussions panel") : t("groups.showDiscussions", "Show discussions panel")}
              className="max-lg:hidden"
            >
              {showDiscussions ? (
                <PanelLeftClose className="h-4 w-4" />
              ) : (
                <PanelLeftOpen className="h-4 w-4" />
              )}
            </Button>
          )}

          {/* Config panel toggle */}
          {!isFullscreen && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowConfig(!showConfig)}
              title={showConfig ? t("groups.hideConfig", "Hide config panel") : t("groups.showConfig", "Show config panel")}
              className="max-xl:hidden"
            >
              {showConfig ? (
                <PanelRightClose className="h-4 w-4" />
              ) : (
                <PanelRightOpen className="h-4 w-4" />
              )}
            </Button>
          )}

          {/* Fullscreen toggle */}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setIsFullscreen(!isFullscreen)}
            title={isFullscreen
              ? t("groups.exitFullscreen", "Exit fullscreen")
              : t("groups.enterFullscreen", "Fullscreen")}
          >
            {isFullscreen ? (
              <Minimize2 className="h-4 w-4" />
            ) : (
              <Maximize2 className="h-4 w-4" />
            )}
          </Button>
        </div>
      </div>

      {/* Three-panel layout */}
      <div className="flex flex-1 min-h-0 mt-2 gap-2">
        {/* LEFT: Discussion history — hidden on small screens and in fullscreen */}
        {!isFullscreen && showDiscussions && (
          <div className="w-64 shrink-0 flex flex-col rounded-xl border border-border bg-card overflow-hidden max-lg:hidden">
            <div className="p-3 border-b border-border">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                <Clock className="h-3 w-3" />
                {t("groups.discussions", "Discussions")}
              </h3>
            </div>
            <div className="flex-1 overflow-y-auto">
              {discussionListContent}
            </div>
          </div>
        )}

        {/* CENTER: Transcript + Input */}
        <div className="flex-1 min-w-0 rounded-xl border border-border bg-card overflow-hidden flex flex-col">
          <div className="flex-1 min-h-0 overflow-hidden">
            <DiscussionTranscript
              conversation={isStreamActive ? null : (selectedConversation ?? null)}
              streamState={isStreamActive ? streamState : undefined}
              isLoading={convLoading && !!selectedConvId}
              discussionStyle={groupConfig.style as DiscussionStyle}
            />
          </div>
          {/* Input always at the bottom of the transcript panel */}
          <DiscussionInput
            onSubmit={handleStartDiscussion}
            isLoading={streamState.isStreaming}
          />
        </div>

        {/* RIGHT: Config panel — hidden on small screens and in fullscreen */}
        {showConfig && !isFullscreen && (
          <div className="w-72 shrink-0 rounded-xl border border-border bg-card overflow-hidden flex flex-col max-xl:hidden">
            <GroupConfigPanel config={safeConfig} groupId={groupId} groupVersion={version} className="flex-1 min-h-0" />
          </div>
        )}
      </div>
    </div>
  );
}

/** Mobile-friendly dropdown for discussion history (replaces Popover) */
function HistoryDropdown({
  historyOpen,
  setHistoryOpen,
  conversationCount,
  isFullscreen,
  children,
}: {
  historyOpen: boolean;
  setHistoryOpen: (v: boolean) => void;
  conversationCount: number;
  isFullscreen?: boolean;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Click-outside to close
  useEffect(() => {
    if (!historyOpen) return;
    function handleClick(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setHistoryOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [historyOpen, setHistoryOpen]);

  return (
    <div ref={dropdownRef} className={cn("relative", !isFullscreen && "lg:hidden")}>
      <Button
        variant="outline"
        size="sm"
        className="relative"
        onClick={() => setHistoryOpen(!historyOpen)}
      >
        <History className="h-4 w-4" />
        {conversationCount > 0 && (
          <span className="absolute -top-1 -end-1 flex h-4 w-4 items-center justify-center rounded-full bg-primary text-[9px] font-bold text-primary-foreground">
            {conversationCount}
          </span>
        )}
      </Button>
      {historyOpen && (
        <div className="absolute end-0 top-full mt-1 z-50 w-72 rounded-xl border border-border bg-card shadow-lg max-h-80 overflow-y-auto animate-in fade-in-0 zoom-in-95 duration-150">
          <div className="p-3 border-b border-border">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
              <Clock className="h-3 w-3" />
              {t("groups.discussions", "Discussions")}
            </h3>
          </div>
          {children}
        </div>
      )}
    </div>
  );
}
