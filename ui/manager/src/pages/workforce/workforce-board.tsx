import { useState, useCallback, useEffect, useRef } from "react";
import { useParams, useSearchParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronLeft, PanelRightOpen, PanelRightClose } from "lucide-react";
import { cn } from "@/lib/utils";
import { useGroup, useGroupConversations, useGroupConversation } from "@/hooks/use-groups";
import { useGroupDiscussionStream } from "@/hooks/use-group-discussion-stream";
import { BoardTranscript } from "@/components/workforce/board-transcript";
import { BoardInput } from "@/components/workforce/board-input";
import { SessionHistory } from "@/components/workforce/session-history";
import { MembersSheet } from "@/components/workforce/members-sheet";
import { ExportMenu } from "@/components/workforce/export-menu";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { GroupConfigPanel } from "@/components/groups/group-config-panel";

// ─── Icons ───────────────────────────────────────────────────────

function UsersIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-4 w-4"
    >
      <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
      <circle cx="9" cy="7" r="4" />
      <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
      <path d="M16 3.13a4 4 0 0 1 0 7.75" />
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
      <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function HistoryIcon() {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
      <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
      <path d="M3 3v5h5" />
      <path d="M12 7v5l4 2" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-4 w-4"
    >
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}

function StopIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-4 w-4"
    >
      <rect x="6" y="6" width="12" height="12" rx="2" />
    </svg>
  );
}

// ─── Component ───────────────────────────────────────────────────

function WorkforceBoard() {
  const { t } = useTranslation();
  const { boardId } = useParams<{ boardId: string }>();
  const [searchParams] = useSearchParams();
  const version = Number(searchParams.get("version")) || 1;

  // ─── State ─────────────────────────────────────────────────────
  const [selectedConvId, setSelectedConvId] = useState<string | null>(null);
  const [showMembers, setShowMembers] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showConfig, setShowConfig] = useState(false);
  const membersRef = useRef<HTMLDivElement>(null);
  const historyRef = useRef<HTMLDivElement>(null);
  const panelTriggerRef = useRef<HTMLElement | null>(null);

  // ─── Data ──────────────────────────────────────────────────────
  const { data: groupConfig, isLoading: configLoading } = useGroup(boardId ?? "", version);
  const { data: conversations } = useGroupConversations(boardId ?? "");
  const { data: selectedConversation } = useGroupConversation(
    boardId ?? "",
    selectedConvId ?? "",
  );
  const { streamState, startStream, abortStream } = useGroupDiscussionStream();

  // ─── Auto-select conversation when stream completes ────────────
  useEffect(() => {
    if (streamState.state === "COMPLETED" && streamState.conversationId) {
      setSelectedConvId(streamState.conversationId);
    }
  }, [streamState.state, streamState.conversationId]);

  // Close slide-over panels on Escape + restore focus
  useEffect(() => {
    if (!showMembers && !showHistory) return;
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (showMembers) setShowMembers(false);
        else if (showHistory) setShowHistory(false);
        requestAnimationFrame(() => panelTriggerRef.current?.focus());
      }
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [showMembers, showHistory]);

  // Auto-focus first element in slide-over panels
  useEffect(() => {
    const ref = showMembers ? membersRef : showHistory ? historyRef : null;
    if (!ref) return;
    requestAnimationFrame(() => {
      const first = ref.current?.querySelector<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      first?.focus();
    });
  }, [showMembers, showHistory]);

  // Focus trap handler for slide-over panels
  const handlePanelKeyDown = useCallback((e: React.KeyboardEvent, ref: React.RefObject<HTMLDivElement | null>) => {
    if (e.key !== 'Tab') return;
    const focusable = ref.current?.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    );
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!first || !last) return;
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }, []);

  // ─── Handlers ──────────────────────────────────────────────────
  const handleSend = useCallback(
    (question: string) => {
      if (!boardId) return;
      startStream(boardId, question);
    },
    [boardId, startStream],
  );

  const handleSelectConversation = useCallback((convId: string) => {
    setSelectedConvId(convId);
    setShowHistory(false);
  }, []);

  // ─── Derived state ─────────────────────────────────────────────
  const isStreaming = streamState.isStreaming;
  const hasStreamTranscript = streamState.transcript.length > 0;

  // Use stream transcript when available, otherwise fall back to selected conversation
  const displayTranscript = hasStreamTranscript
    ? streamState.transcript
    : selectedConversation?.transcript ?? [];

  const displaySynthesis = hasStreamTranscript
    ? streamState.synthesizedAnswer
    : selectedConversation?.synthesizedAnswer ?? null;

  const members = groupConfig?.members ?? [];

  // ─── Loading state ─────────────────────────────────────────────
  if (configLoading || !boardId) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="w-full max-w-md space-y-4">
          <Skeleton className="h-8 w-3/4 mx-auto" />
          <Skeleton className="h-40 w-full rounded-xl" />
          <Skeleton className="h-12 w-full rounded-xl" />
        </div>
      </div>
    );
  }

  // ─── Error state ───────────────────────────────────────────────
  if (streamState.error && !hasStreamTranscript) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="text-center max-w-md">
          <p className="text-sm text-destructive mb-2">
            {t("Workforce.board.error", "Something went wrong")}
          </p>
          <p className="text-xs text-muted-foreground">{streamState.error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-1 min-h-0 flex-col relative">
      {/* Action bar */}
      <div className="flex items-center justify-between ps-4 pe-4 py-2 border-b border-border">
        <div className="flex items-center gap-2">
          <Link
            to="/workforce"
            className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            aria-label={t("Workforce.back", "Back")}
          >
            <ChevronLeft className="h-4 w-4" />
          </Link>
          <h2 className="text-sm font-semibold text-foreground truncate">
            {groupConfig?.name ?? t("Workforce.board.title", "Task Force")}
          </h2>
          {isStreaming && (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse" />
              {t("Workforce.board.live", "Live")}
            </span>
          )}
        </div>

        <div className="flex items-center gap-1">
          {isStreaming && (
            <Button
              variant="ghost"
              size="sm"
              onClick={abortStream}
              className="text-destructive gap-1"
            >
              <StopIcon />
              {t("Workforce.board.stop", "Stop")}
            </Button>
          )}
          <Button
            variant="ghost"
            size="icon"
            onClick={() => {
              if (!showMembers) panelTriggerRef.current = document.activeElement as HTMLElement;
              setShowMembers((v) => !v);
              setShowHistory(false);
            }}
            className={cn("h-8 w-8", showMembers && "bg-primary/10")}
            aria-label={t("Workforce.board.members", "Team")}
            aria-expanded={showMembers}
          >
            <UsersIcon />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            onClick={() => {
              if (!showHistory) panelTriggerRef.current = document.activeElement as HTMLElement;
              setShowHistory((v) => !v);
              setShowMembers(false);
            }}
            className={cn("h-8 w-8", showHistory && "bg-primary/10")}
            aria-label={t("Workforce.board.sessions", "Sessions")}
            aria-expanded={showHistory}
          >
            <ClockIcon />
          </Button>
          <Link
            to={`/workforce/${boardId}/history`}
            className={cn(
              "inline-flex items-center justify-center rounded-md h-8 w-8 transition-colors",
              "hover:bg-muted text-muted-foreground",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            )}
            aria-label={t("Workforce.board.viewHistory", "View history")}
          >
            <HistoryIcon />
          </Link>
          <ExportMenu
              conversation={selectedConversation ?? null}
              groupName={groupConfig?.name}
            />
            <Link
              to={`/workforce/${boardId}/settings?version=${version}`}
              className={cn(
                "inline-flex items-center justify-center rounded-md h-8 w-8 transition-colors",
                "hover:bg-muted text-muted-foreground",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              )}
              aria-label={t("Workforce.settings.title", "Settings")}
            >
              <SettingsIcon />
            </Link>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setShowConfig((v) => !v)}
              className={cn("h-8 w-8 max-xl:hidden", showConfig && "bg-primary/10")}
              aria-label={t("Workforce.board.toggleConfig", "Toggle details panel")}
              aria-expanded={showConfig}
            >
              {showConfig ? <PanelRightClose className="h-4 w-4" /> : <PanelRightOpen className="h-4 w-4" />}
            </Button>
          </div>
      </div>

      <div className="flex flex-1 min-h-0">
        {/* Main content — transcript + input */}
        <div className="flex flex-1 min-h-0 flex-col">
          {/* Transcript area */}
          <div className="flex-1 overflow-y-auto ps-4 pe-4 pt-4 pb-4">
        {displayTranscript.length > 0 ? (
          <BoardTranscript
            transcript={displayTranscript}
            boardId={boardId}
            synthesizedAnswer={displaySynthesis}
          />
        ) : (
          <div className="flex h-full items-center justify-center">
            <div className="text-center max-w-sm">
              <p className="text-lg font-medium text-foreground mb-1">
                {t("Workforce.board.emptyTitle", "Ready for discussion")}
              </p>
              <p className="text-sm text-muted-foreground">
                {t(
                  "Workforce.board.emptyDescription",
                  "Ask a question and your task force will discuss it.",
                )}
              </p>
              {conversations && conversations.length > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="mt-3 text-primary"
                  onClick={() => setShowHistory(true)}
                >
                  {t("Workforce.board.viewHistory", "View history")}
                </Button>
              )}
            </div>
          </div>
        )}

        {/* Streaming error banner (inline, when transcript is visible) */}
        {streamState.error && hasStreamTranscript && (
          <div className="mt-3 rounded-xl border border-destructive/30 bg-destructive/10 p-3">
            <p className="text-xs text-destructive">{streamState.error}</p>
          </div>
        )}
      </div>

      {/* Input bar */}
      <BoardInput onSend={handleSend} disabled={isStreaming} />
        </div>

        {/* Config panel — right sidebar, read-only */}
        {showConfig && groupConfig && (
          <div className="w-72 shrink-0 border-s border-border bg-card overflow-hidden flex flex-col max-xl:hidden">
            <div className="p-3 border-b border-border flex items-center justify-between">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("Workforce.board.details", "Details")}
              </h3>
              <button
                type="button"
                onClick={() => setShowConfig(false)}
                className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                aria-label={t("Workforce.board.hideConfig", "Hide details panel")}
              >
                <PanelRightClose className="h-3.5 w-3.5" />
              </button>
            </div>
            <GroupConfigPanel
              config={groupConfig}
              className="flex-1 min-h-0"
            />
          </div>
        )}
      </div>

      {/* Members sheet slide-over */}
      {showMembers && (
        <>
          <div
            className="fixed inset-0 z-30 bg-black/30"
            onClick={() => {
              setShowMembers(false);
              requestAnimationFrame(() => panelTriggerRef.current?.focus());
            }}
            aria-hidden="true"
          />
          <div
            ref={membersRef}
            role="dialog"
            aria-modal="true"
            aria-label={t("Workforce.board.membersPanel", "Team panel")}
            onKeyDown={(e) => handlePanelKeyDown(e, membersRef)}
            className={cn(
              "fixed inset-y-0 end-0 z-40 w-80",
              "bg-card",
              "border-s border-border",
              "shadow-xl",
              "animate-[br-fade-in_200ms_ease-out]",
            )}
          >
            <MembersSheet
              members={members}
              boardId={boardId}
              moderatorId={groupConfig?.moderatorAgentId}
              onClose={() => {
                setShowMembers(false);
                requestAnimationFrame(() => panelTriggerRef.current?.focus());
              }}
            />
          </div>
        </>
      )}

      {/* Session history slide-over */}
      {showHistory && (
        <>
          <div
            className="fixed inset-0 z-30 bg-black/30"
            onClick={() => {
              setShowHistory(false);
              requestAnimationFrame(() => panelTriggerRef.current?.focus());
            }}
            aria-hidden="true"
          />
          <div
            ref={historyRef}
            role="dialog"
            aria-modal="true"
            aria-label={t("Workforce.board.sessionsPanel", "Sessions panel")}
            onKeyDown={(e) => handlePanelKeyDown(e, historyRef)}
            className={cn(
              "fixed inset-y-0 end-0 z-40 w-80",
              "bg-card",
              "border-s border-border",
              "shadow-xl",
              "animate-[br-fade-in_200ms_ease-out]",
            )}
          >
            <SessionHistory
              groupId={boardId}
              selectedId={selectedConvId}
              onSelect={handleSelectConversation}
              onClose={() => {
                setShowHistory(false);
                requestAnimationFrame(() => panelTriggerRef.current?.focus());
              }}
            />
          </div>
        </>
      )}
    </div>
  );
}

export { WorkforceBoard };
