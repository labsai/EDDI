import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, Link } from "react-router-dom";
import {
  ArrowLeft,
  MessageSquare,
  RefreshCw,
  AlertCircle,
  Settings,
  User,
  Bot,
  ChevronDown,
  ChevronUp,
  Trash2,
  Circle,
  CheckCircle2,
  Clock,
  AlertTriangle,
  Zap,
  Code,
  MessageCircle,
  Download,
  Search,
  HandMetal,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { AttachmentsSection } from "@/components/conversations/attachments-section";
import {
  useSimpleConversation,
  useDeleteConversation,
} from "@/hooks/use-conversations";
import type {
  ConversationState,
  ConversationOutput,
  SimpleConversationStep,
} from "@/lib/api/conversations";
import { extractInput, extractOutput, extractActions } from "@/lib/api/conversations";
import { useNavigate } from "react-router-dom";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import {
  useResumeConversation,
  useCancelConversation,
  useApprovalStatus,
} from "@/hooks/use-hitl";
import type { HitlVerdict, ToolCallDecision } from "@/lib/api/hitl";

// Status icons — labels resolved via i18n in component
const stateIcons: Record<
  ConversationState,
  { icon: typeof Circle; color: string; bg: string }
> = {
  READY: { icon: Circle, color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10" },
  ENDED: { icon: CheckCircle2, color: "text-muted-foreground", bg: "bg-muted" },
  EXECUTION_INTERRUPTED: { icon: AlertTriangle, color: "text-amber-500", bg: "bg-amber-500/10" },
  AWAITING_HUMAN: { icon: HandMetal, color: "text-orange-500", bg: "bg-orange-500/10" },
};

export function ConversationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [deletePermanent, setDeletePermanent] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");

  // i18n labels
  const stateLabels: Record<ConversationState, string> = {
    READY: t("conversations.stateActive", "Active"),
    IN_PROGRESS: t("conversations.stateInProgress", "In Progress"),
    ERROR: t("status.error", "Error"),
    ENDED: t("conversations.stateEnded", "Ended"),
    EXECUTION_INTERRUPTED: t("conversations.stateInterrupted", "Interrupted"),
    AWAITING_HUMAN: t("hitl.awaitingHuman", "Awaiting Human"),
  };

  const deleteMutation = useDeleteConversation();
  const resumeMutation = useResumeConversation();
  const cancelMutation = useCancelConversation();

  const { data: conversation, isLoading, isError, refetch } =
    useSimpleConversation(id!, true, false);

  // Structured pause details (incl. TOOL_CALL per-call tool names + redacted
  // arguments) — fetched only while the conversation is actually paused.
  const { data: approvalStatus } = useApprovalStatus(
    id,
    conversation?.conversationState === "AWAITING_HUMAN",
  );

  function handleDelete() {
    deleteMutation.mutate(
      { id: id!, permanent: deletePermanent },
      {
        onSuccess: () => {
          toast.success(
            deletePermanent
              ? t("conversations.permanentDeleteSuccess", "Permanently deleted")
              : t("conversations.softDeleteSuccess", "Conversation deleted")
          );
          navigate("/manage/conversations");
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
    setShowDeleteDialog(false);
    setDeletePermanent(false);
  }

  function handleExportMarkdown() {
    if (!conversation) return;
    const lines: string[] = [
      `# Conversation ${id}`,
      `**Agent**: ${conversation.agentId} v${conversation.agentVersion}`,
      `**State**: ${conversation.conversationState}`,
      `**Steps**: ${conversation.conversationSteps?.length ?? 0}`,
      "",
    ];
    conversation.conversationSteps?.forEach((step, i) => {
      const input = extractInput(step);
      const output = extractOutput(conversation.conversationOutputs?.[i]);
      if (input) lines.push(`**User**: ${input}`, "");
      if (output) lines.push(`**Agent**: ${output}`, "");
    });
    const blob = new Blob([lines.join("\n")], { type: "text/markdown" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `conversation-${id?.slice(0, 8)}.md`;
    a.click();
    URL.revokeObjectURL(url);
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20" data-testid="conversation-loading">
        <RefreshCw className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (isError || !conversation) {
    return (
      <div className="space-y-4">
        <BackLink />
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">
            {t("common.error")}
          </p>
          <button
            onClick={() => refetch()}
            className="mt-4 rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20"
          >
            {t("common.retry")}
          </button>
        </div>
      </div>
    );
  }

  const state = conversation.conversationState || "READY";
  const config = stateIcons[state];
  const StateIcon = config.icon;
  const stateLabel = stateLabels[state];
  const stepCount = conversation.conversationSteps?.length ?? 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-2">
          <BackLink />
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <MessageSquare className="h-8 w-8 text-primary" />
            {t("conversationDetail.title", "Conversation")}
          </h1>
          <p className="font-mono text-sm text-muted-foreground">
            ID: {id}
          </p>
        </div>

        {/* Meta + Actions */}
        <div className="flex flex-wrap items-center gap-2">
          {/* State badge */}
          <span
            className={cn(
              "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium",
              config.bg,
              config.color
            )}
          >
            <StateIcon className="h-4 w-4" />
            {stateLabel}
          </span>

          {/* Agent info */}
          <span className="rounded-full bg-secondary px-3 py-1.5 text-sm font-medium text-secondary-foreground">
            {conversation.agentId} v{conversation.agentVersion}
          </span>

          {/* Step count */}
          <span className="rounded-full bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary">
            {stepCount} {t("conversationDetail.steps", "steps")}
          </span>

          {/* Delete */}
          <button
            onClick={() => setShowDeleteDialog(true)}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
            data-testid="delete-conversation-btn"
          >
            <Trash2 className="h-4 w-4" />
          </button>

          {/* Continue in Chat */}
          {(state === "READY" || state === "IN_PROGRESS") && (
            <button
              onClick={() => navigate(`/manage/chat?agentId=${conversation.agentId}&conversationId=${id}`)}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-4 py-2 text-sm font-medium text-primary hover:bg-primary/20 transition-colors"
              data-testid="continue-in-chat"
            >
              <MessageCircle className="h-4 w-4" />
              {t("conversationDetail.continueChat", "Continue in Chat")}
            </button>
          )}

          {/* Export as Markdown */}
          <button
            onClick={() => handleExportMarkdown()}
            className="inline-flex items-center gap-1.5 rounded-lg bg-secondary px-4 py-2 text-sm font-medium text-secondary-foreground hover:bg-secondary/80 transition-colors"
            data-testid="export-md"
          >
            <Download className="h-4 w-4" />
            {t("conversationDetail.exportMd", "Export")}
          </button>
        </div>
      </div>

      {/* HITL Approval Banner */}
      {state === "AWAITING_HUMAN" && (
        <ApprovalBanner
          surface="regular"
          pauseReason={conversation.hitlPauseReason}
          pausedAt={conversation.hitlPausedAt}
          timeoutPolicy={conversation.hitlTimeoutPolicy}
          approvalTimeout={conversation.hitlApprovalTimeout}
          pauseDetails={approvalStatus?.pauseDetails ?? null}
          pauseDetailsPending={!approvalStatus}
          isSubmitting={resumeMutation.isPending || cancelMutation.isPending}
          onDecide={(
            verdict: HitlVerdict,
            note?: string,
            _taskApprovals?: Record<string, string>,
            toolDecisions?: Record<string, ToolCallDecision>,
          ) => {
            resumeMutation.mutate(
              { conversationId: id!, decision: { verdict, note, toolDecisions } },
              {
                onSuccess: () => {
                  toast.success(verdict === "APPROVED"
                    ? t("hitl.approved", "Approved")
                    : t("hitl.rejected", "Rejected"));
                  refetch();
                },
                onError: (err) => toast.error(getErrorMessage(err)),
              }
            );
          }}
          onCancel={() => {
            cancelMutation.mutate(id!, {
              onSuccess: () => {
                toast.success(t("hitl.cancelled", "Cancelled"));
                refetch();
              },
              onError: (err) => toast.error(getErrorMessage(err)),
            });
          }}
        />
      )}

      {/* Conversation Properties */}
      {conversation.conversationProperties &&
        Object.keys(conversation.conversationProperties).length > 0 && (
          <PropertiesSection
            properties={conversation.conversationProperties}
          />
        )}

      {/* Attachments the conversation owns (browse / download / delete) */}
      {id && (
        <section className="rounded-xl border bg-card p-5 shadow-sm">
          <AttachmentsSection conversationId={id} />
        </section>
      )}

      {/* Chat-style conversation */}
      <section className="rounded-xl border bg-card shadow-sm" data-testid="conversation-chat">
        <div className="flex items-center gap-2 border-b border-border p-5">
          <MessageSquare className="h-5 w-5 text-primary" />
          <h2 className="text-lg font-semibold text-foreground">
            {t("conversationDetail.chatLog", "Chat Log")}
          </h2>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {stepCount}
          </span>
          <div className="ms-auto">
            <div className="relative">
              <Search className="absolute start-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder={t("conversationDetail.searchTranscript", "Search…")}
                className="h-8 w-48 rounded-lg border border-input bg-background ps-8 pe-3 text-xs placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                data-testid="transcript-search"
              />
            </div>
          </div>
        </div>

        <div className="p-4 sm:p-6 space-y-1">
          {stepCount === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <MessageSquare className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">
                {t(
                  "conversationDetail.noSteps",
                  "No conversation steps recorded"
                )}
              </p>
            </div>
          )}

          {conversation.conversationSteps?.map((step, index) => {
            // Filter by search query
            if (searchQuery) {
              const q = searchQuery.toLowerCase();
              const input = extractInput(step)?.toLowerCase() ?? "";
              const output = extractOutput(conversation.conversationOutputs?.[index])?.toLowerCase() ?? "";
              if (!input.includes(q) && !output.includes(q)) return null;
            }
            return (
              <ChatBubbleStep
                key={index}
                step={step}
                conversationOutput={conversation.conversationOutputs?.[index]}
                stepNumber={index + 1}
                isLast={index === stepCount - 1}
                highlight={searchQuery}
              />
            );
          })}
        </div>
      </section>

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={showDeleteDialog}
        onOpenChange={(open) => {
          setShowDeleteDialog(open);
          if (!open) setDeletePermanent(false);
        }}
        title={t("conversations.confirmDelete", "Delete Conversation")}
        description={t(
          "conversations.confirmDeleteSoft",
          "By default this is a soft delete — the conversation is hidden from listings but its stored data is kept on the server."
        )}
        confirmLabel={
          deletePermanent
            ? t("conversations.deletePermanentAction", "Delete permanently")
            : t("common.delete")
        }
        cancelLabel={t("common.cancel")}
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      >
        <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-border/60 bg-muted/30 p-3 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 h-4 w-4 accent-destructive"
            checked={deletePermanent}
            onChange={(e) => setDeletePermanent(e.target.checked)}
            data-testid="delete-permanent-checkbox"
          />
          <span className="text-muted-foreground">
            {t(
              "conversations.deletePermanentLabel",
              "Permanently delete — also erases attachments, the memory snapshot and approval history. This cannot be undone."
            )}
          </span>
        </label>
      </AlertDialog>
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/manage/conversations"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {t("conversationDetail.backToConversations", "Back to Conversations")}
    </Link>
  );
}

function HighlightText({ text, highlight }: { text: string; highlight?: string }) {
  if (!highlight || !highlight.trim()) return <>{text}</>;
  const regex = new RegExp(`(${highlight.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")})`, "gi");
  const parts = text.split(regex);
  return (
    <>
      {parts.map((part, i) =>
        regex.test(part) ? (
          <mark key={i} className="bg-amber-300/60 dark:bg-amber-500/40 rounded-sm px-0.5">
            {part}
          </mark>
        ) : (
          <span key={i}>{part}</span>
        ),
      )}
    </>
  );
}

function ChatBubbleStep({
  step,
  conversationOutput,
  stepNumber,
  isLast,
  highlight,
}: {
  step: SimpleConversationStep;
  conversationOutput?: ConversationOutput;
  stepNumber: number;
  isLast: boolean;
  highlight?: string;
}) {
  const { t } = useTranslation();
  const [showRaw, setShowRaw] = useState(false);

  // Parse input/output/actions from the conversationStep key/value pairs
  const input = extractInput(step);
  const output = extractOutput(conversationOutput);
  const actions = extractActions(step);

  // Calculate processing time from timestamps of conversationStep data entries
  const processingTime = (() => {
    const timestamps = step.conversationStep
      ?.map((d) => d.timestamp ? new Date(d.timestamp).getTime() : 0)
      .filter((t) => t > 0);
    if (!timestamps || timestamps.length < 2) return null;
    const min = Math.min(...timestamps);
    const max = Math.max(...timestamps);
    const diffMs = max - min;
    if (diffMs < 1000) return `${diffMs}ms`;
    return `${(diffMs / 1000).toFixed(1)}s`;
  })();

  return (
    <div className={cn("space-y-3", !isLast && "pb-3")}>
      {/* User message — right aligned */}
      {input && (
        <div className="flex justify-end">
          <div className="flex items-end gap-2 max-w-[80%] sm:max-w-[70%]">
            <div className="rounded-2xl rounded-be-md bg-primary px-4 py-2.5 text-primary-foreground shadow-sm">
              <p className="text-sm whitespace-pre-wrap"><HighlightText text={input} highlight={highlight} /></p>
            </div>
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/20">
              <User className="h-3.5 w-3.5 text-primary" />
            </div>
          </div>
        </div>
      )}

      {/* Actions — centered */}
      {actions.length > 0 && (
        <div className="flex justify-center">
          <div className="flex flex-wrap items-center justify-center gap-1">
            <Zap className="h-3 w-3 text-amber-500" />
            {actions.map((action, i) => (
              <span
                key={i}
                className="rounded-md bg-amber-500/10 px-2 py-0.5 text-[10px] font-medium text-amber-600 dark:text-amber-400"
              >
                {action}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Agent message — left aligned */}
      {output && (
        <div className="flex justify-start">
          <div className="flex items-end gap-2 max-w-[80%] sm:max-w-[70%]">
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-secondary">
              <Bot className="h-3.5 w-3.5 text-primary" />
            </div>
            <div className="rounded-2xl rounded-bs-md bg-secondary px-4 py-2.5 shadow-sm">
              <p className="text-sm text-foreground whitespace-pre-wrap"><HighlightText text={output} highlight={highlight} /></p>
            </div>
          </div>
        </div>
      )}

      {/* Raw data toggle + processing time */}
      <div className="flex justify-center">
        <button
          onClick={() => setShowRaw(!showRaw)}
          className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-[10px] text-muted-foreground hover:bg-secondary transition-colors"
          title={t("conversationDetail.rawData", "View raw data")}
        >
          <Code className="h-3 w-3" />
          {t("conversationDetail.step", "Step")} {stepNumber}
          {processingTime && (
            <span className="inline-flex items-center gap-0.5 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
              <Clock className="h-2.5 w-2.5" />
              {processingTime}
            </span>
          )}
          {showRaw ? (
            <ChevronUp className="h-3 w-3" />
          ) : (
            <ChevronDown className="h-3 w-3" />
          )}
        </button>
      </div>

      {showRaw && (
        <div className="mx-4 sm:mx-8 rounded-lg bg-secondary/50 p-3 border border-border overflow-x-auto">
          <pre
            className="text-xs leading-relaxed"
            data-testid={`step-raw-${stepNumber}`}
            dangerouslySetInnerHTML={{ __html: syntaxHighlightJson(JSON.stringify(step, null, 2)) }}
          />
        </div>
      )}

      {/* Divider between steps */}
      {!isLast && (
        <div className="border-b border-border/50" />
      )}
    </div>
  );
}

function PropertiesSection({
  properties,
}: {
  properties: Record<string, unknown>;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between p-5 text-start"
      >
        <div className="flex items-center gap-2">
          <Settings className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-lg font-semibold text-foreground">
            {t("conversationDetail.properties", "Conversation Properties")}
          </h2>
        </div>
        <span className="text-sm text-muted-foreground">
          {expanded ? "▲" : "▼"}
        </span>
      </button>
      {expanded && (
        <div className="border-t border-border p-5">
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm text-foreground" data-testid="properties-json">
            {JSON.stringify(properties, null, 2)}
          </pre>
        </div>
      )}
    </section>
  );
}

/** Simple JSON syntax highlighter using regex — returns HTML string.
 *
 * The output is injected via dangerouslySetInnerHTML, and `json` can contain
 * attacker-controlled conversation content (user input / agent output), so we
 * HTML-escape first — otherwise a string value like `<img src=x onerror=…>`
 * would execute as markup (stored XSS). `&` must be escaped before `<`/`>`. */
function syntaxHighlightJson(json: string): string {
  const escaped = json
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  return escaped.replace(
    /("(\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g,
    (match) => {
      let cls = "color: var(--color-amber-400)"; // number
      if (/^"/.test(match)) {
        if (/:$/.test(match)) {
          cls = "color: var(--color-primary)"; // key
        } else {
          cls = "color: var(--color-emerald-400)"; // string
        }
      } else if (/true|false/.test(match)) {
        cls = "color: var(--color-sky-400)"; // boolean
      } else if (/null/.test(match)) {
        cls = "color: var(--color-rose-400)"; // null
      }
      return `<span style="${cls}">${match}</span>`;
    }
  );
}
