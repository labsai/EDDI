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
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useSimpleConversation,
  useDeleteConversation,
} from "@/hooks/use-conversations";
import type {
  ConversationState,
  SimpleConversationStep,
} from "@/lib/api/conversations";
import { useNavigate } from "react-router-dom";

const stateConfig: Record<
  ConversationState,
  { icon: typeof Circle; label: string; color: string; bg: string }
> = {
  READY: {
    icon: Circle,
    label: "Active",
    color: "text-emerald-500",
    bg: "bg-emerald-500/10",
  },
  IN_PROGRESS: {
    icon: Clock,
    label: "In Progress",
    color: "text-amber-500",
    bg: "bg-amber-500/10",
  },
  ERROR: {
    icon: AlertTriangle,
    label: "Error",
    color: "text-destructive",
    bg: "bg-destructive/10",
  },
  ENDED: {
    icon: CheckCircle2,
    label: "Ended",
    color: "text-muted-foreground",
    bg: "bg-muted",
  },
};

export function ConversationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const deleteMutation = useDeleteConversation();

  const { data: conversation, isLoading, isError, refetch } =
    useSimpleConversation(id!, true, false);

  async function handleDelete() {
    if (
      window.confirm(
        t(
          "conversations.confirmDelete",
          "Are you sure you want to delete this conversation?"
        )
      )
    ) {
      await deleteMutation.mutateAsync({ id: id! });
      navigate("/manage/conversations");
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20">
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
  const config = stateConfig[state];
  const StateIcon = config.icon;

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
            {config.label}
          </span>

          {/* Bot info */}
          <span className="rounded-full bg-secondary px-3 py-1.5 text-sm font-medium text-secondary-foreground">
            {conversation.botId} v{conversation.botVersion}
          </span>

          {/* Delete */}
          <button
            onClick={handleDelete}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Conversation Properties */}
      {conversation.conversationProperties &&
        Object.keys(conversation.conversationProperties).length > 0 && (
          <PropertiesSection
            properties={conversation.conversationProperties}
          />
        )}

      {/* Conversation Steps */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center gap-2 border-b border-border p-5">
          <MessageSquare className="h-5 w-5 text-primary" />
          <h2 className="text-lg font-semibold text-foreground">
            {t("conversationDetail.steps", "Conversation Steps")}
          </h2>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {conversation.conversationSteps?.length ?? 0}
          </span>
        </div>

        <div className="divide-y divide-border">
          {(!conversation.conversationSteps ||
            conversation.conversationSteps.length === 0) && (
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

          {conversation.conversationSteps?.map((step, index) => (
            <ConversationStepRow
              key={index}
              step={step}
              stepNumber={index + 1}
            />
          ))}
        </div>
      </section>
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

function ConversationStepRow({
  step,
  stepNumber,
}: {
  step: SimpleConversationStep;
  stepNumber: number;
}) {
  const [expanded, setExpanded] = useState(false);

  // Extract common fields
  const input = step.input;
  const output = step.output;
  const actions = step.actions;

  return (
    <div className="px-5 py-4">
      <div className="flex items-start gap-3">
        {/* Step number */}
        <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
          {stepNumber}
        </span>

        <div className="flex-1 min-w-0 space-y-2">
          {/* User input */}
          {input && (
            <div className="flex items-start gap-2">
              <User className="mt-0.5 h-4 w-4 shrink-0 text-blue-500" />
              <p className="text-sm text-foreground">{input}</p>
            </div>
          )}

          {/* Actions */}
          {actions && actions.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {actions.map((action, i) => (
                <span
                  key={i}
                  className="rounded-md bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600 dark:text-amber-400"
                >
                  {action}
                </span>
              ))}
            </div>
          )}

          {/* Bot output */}
          {output && (
            <div className="flex items-start gap-2">
              <Bot className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              <p className="text-sm text-foreground whitespace-pre-wrap">
                {output}
              </p>
            </div>
          )}
        </div>

        {/* Expand/collapse raw data */}
        <button
          onClick={() => setExpanded(!expanded)}
          className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
          title="View raw data"
        >
          {expanded ? (
            <ChevronUp className="h-4 w-4" />
          ) : (
            <ChevronDown className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Raw data view */}
      {expanded && (
        <div className="mt-3 ms-9 rounded-lg bg-secondary p-3">
          <pre className="overflow-x-auto text-xs text-foreground">
            {JSON.stringify(step, null, 2)}
          </pre>
        </div>
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
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm text-foreground">
            {JSON.stringify(properties, null, 2)}
          </pre>
        </div>
      )}
    </section>
  );
}
