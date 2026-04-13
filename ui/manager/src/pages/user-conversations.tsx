import { useState, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Link } from "react-router-dom";
import {
  Link2,
  Search,
  Trash2,
  Plus,
  X,
  MessageSquare,
  Server,
  Bot,
  RefreshCw,
  AlertCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ENVIRONMENTS } from "@/lib/constants";
import {
  useUserConversation,
  useCreateUserConversation,
  useDeleteUserConversation,
} from "@/hooks/use-user-conversations";
import { useDebounce } from "@/hooks/use-debounce";

export function UserConversationsPage({ embedded }: { embedded?: boolean } = {}) {
  const { t } = useTranslation();

  // Lookup state
  const [intent, setIntent] = useState("");
  const [userId, setUserId] = useState("");
  const debouncedIntent = useDebounce(intent, 400);
  const debouncedUserId = useDebounce(userId, 400);

  const {
    data: result,
    isLoading,
    isError,
    error,
  } = useUserConversation(debouncedIntent, debouncedUserId);

  const deleteMutation = useDeleteUserConversation();
  const [showCreate, setShowCreate] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{
    intent: string;
    userId: string;
  } | null>(null);

  const canSearch = intent.trim().length > 0 && userId.trim().length > 0;

  return (
    <div className="space-y-6" data-testid="user-conversations-page">
      {/* Header — hidden when embedded in tabbed UserDataPage */}
      {!embedded && (
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-500/10">
              <Link2 className="h-5 w-5 text-blue-500" />
            </div>
            {t("userConversations.title", "User Conversations")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "userConversations.subtitle",
              "Lookup and manage intent → user → conversation bindings used by managed agents",
            )}
          </p>
        </div>
        <Button
          onClick={() => setShowCreate(true)}
          className="gap-2"
          data-testid="create-user-conv-btn"
        >
          <Plus className="h-4 w-4" />
          {t("userConversations.create", "Create Binding")}
        </Button>
      </div>
      )}
      {/* Show create button inline when embedded (since the header+button combo is hidden) */}
      {embedded && (
        <div className="flex justify-end">
          <Button
            onClick={() => setShowCreate(true)}
            className="gap-2"
            data-testid="create-user-conv-btn-embedded"
          >
            <Plus className="h-4 w-4" />
            {t("userConversations.create", "Create Binding")}
          </Button>
        </div>
      )}

      {/* Lookup Form */}
      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 text-sm font-semibold text-foreground">
          <Search className="me-2 inline h-4 w-4 text-primary" />
          {t("userConversations.lookup", "Lookup")}
        </h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.intent", "Intent")}
            </label>
            <input
              type="text"
              value={intent}
              onChange={(e) => setIntent(e.target.value)}
              placeholder="e.g. booking_request"
              className="h-10 w-full rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="uc-intent-input"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.userId", "User ID")}
            </label>
            <input
              type="text"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder="e.g. user-123"
              className="h-10 w-full rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="uc-userid-input"
            />
          </div>
        </div>
      </div>

      {/* Results */}
      {!canSearch && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-20 text-muted-foreground">
          <Search className="h-12 w-12 opacity-30" />
          <p className="mt-4 text-sm">
            {t(
              "userConversations.enterBoth",
              "Enter both intent and user ID to search",
            )}
          </p>
        </div>
      )}

      {canSearch && isLoading && (
        <div className="flex items-center justify-center py-16">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {canSearch && isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-20 text-muted-foreground">
          <AlertCircle className="h-10 w-10 opacity-30" />
          <p className="mt-4 text-sm">
            {(error as { status?: number })?.status === 404
              ? t(
                  "userConversations.notFound",
                  "No binding found for this intent + user",
                )
              : t("common.error")}
          </p>
        </div>
      )}

      {canSearch && !isLoading && !isError && result && (
        <div
          className="rounded-xl border border-border bg-card"
          data-testid="uc-result-card"
        >
          <div className="border-b border-border px-5 py-4">
            <h3 className="text-sm font-semibold text-foreground">
              {t("userConversations.binding", "Active Binding")}
            </h3>
          </div>
          <div className="space-y-3 px-5 py-4">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <InfoItem
                icon={<Link2 className="h-3.5 w-3.5" />}
                label={t("userConversations.intent", "Intent")}
                value={result.intent}
              />
              <InfoItem
                icon={<Search className="h-3.5 w-3.5" />}
                label={t("userConversations.userId", "User ID")}
                value={result.userId}
              />
              <InfoItem
                icon={<Bot className="h-3.5 w-3.5" />}
                label={t("userConversations.agentId", "Agent ID")}
                value={result.agentId}
              />
              <InfoItem
                icon={<Server className="h-3.5 w-3.5" />}
                label={t("userConversations.environment", "Environment")}
                value={result.environment}
              />
            </div>

            <div className="flex items-center gap-3 rounded-lg bg-secondary/30 px-4 py-3">
              <MessageSquare className="h-4 w-4 text-primary shrink-0" />
              <div className="flex-1 min-w-0">
                <p className="text-xs text-muted-foreground">
                  {t("userConversations.conversationId", "Conversation ID")}
                </p>
                <Link
                  to={`/manage/conversationview/${result.conversationId}`}
                  className="block truncate font-mono text-sm text-primary hover:underline"
                >
                  {result.conversationId}
                </Link>
              </div>
              <Button
                variant="destructive"
                size="sm"
                onClick={() =>
                  setDeleteTarget({
                    intent: result.intent,
                    userId: result.userId,
                  })
                }
                data-testid="uc-delete-btn"
              >
                <Trash2 className="h-3.5 w-3.5 me-1.5" />
                {t("common.delete")}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Create Dialog */}
      {showCreate && (
        <CreateUserConvDialog onClose={() => setShowCreate(false)} />
      )}

      {/* Delete Confirm */}
      <AlertDialog
        open={!!deleteTarget}
        onOpenChange={() => setDeleteTarget(null)}
        title={t("userConversations.deleteTitle", "Delete Binding")}
        description={t(
          "userConversations.deleteDesc",
          "Remove the conversation binding for intent \"{{intent}}\" and user \"{{userId}}\"?",
          { intent: deleteTarget?.intent ?? "", userId: deleteTarget?.userId ?? "" },
        )}
        confirmLabel={t("common.delete")}
        variant="destructive"
        onConfirm={() => {
          if (deleteTarget) {
            deleteMutation.mutate(deleteTarget, {
              onSuccess: () => {
                toast.success(
                  t(
                    "userConversations.deleted",
                    "Binding deleted",
                  ),
                );
                setDeleteTarget(null);
              },
              onError: (err) => toast.error((err as Error).message),
            });
          }
        }}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}

// ─── Sub-components ───

function InfoItem({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="rounded-lg border border-border/50 bg-muted/30 px-3 py-2.5">
      <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        {icon}
        {label}
      </div>
      <p className="mt-1 truncate font-mono text-sm text-foreground" title={value}>
        {value}
      </p>
    </div>
  );
}

function CreateUserConvDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const createMutation = useCreateUserConversation();
  const [intent, setIntent] = useState("");
  const [userId, setUserId] = useState("");
  const [agentId, setAgentId] = useState("");
  const [conversationId, setConversationId] = useState("");
  const [environment, setEnvironment] = useState("production");

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [onClose]);

  const isValid =
    intent.trim() &&
    userId.trim() &&
    agentId.trim() &&
    conversationId.trim();

  const handleCreate = useCallback(() => {
    if (!isValid) return;
    createMutation.mutate(
      {
        intent: intent.trim(),
        userId: userId.trim(),
        data: {
          intent: intent.trim(),
          userId: userId.trim(),
          agentId: agentId.trim(),
          conversationId: conversationId.trim(),
          environment,
        },
      },
      {
        onSuccess: () => {
          toast.success(
            t("userConversations.created", "Binding created"),
          );
          onClose();
        },
        onError: (err) => toast.error((err as Error).message),
      },
    );
  }, [isValid, intent, userId, agentId, conversationId, environment, createMutation, t, onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={t("userConversations.createTitle", "Create Binding")}
    >
      <div
        className="w-full max-w-lg rounded-xl border border-border bg-card p-6 shadow-xl space-y-4 mx-4"
        onClick={(e) => e.stopPropagation()}
        data-testid="uc-create-dialog"
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">
            {t("userConversations.createTitle", "Create Binding")}
          </h2>
          <button
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground"
            aria-label={t("common.close")}
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.intent", "Intent")}
            </label>
            <input
              type="text"
              value={intent}
              onChange={(e) => setIntent(e.target.value)}
              placeholder="booking_request"
              className="h-9 w-full rounded-md border border-input bg-background px-3 font-mono text-sm focus:outline-none focus:ring-1 focus:ring-ring"
              autoFocus
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.userId", "User ID")}
            </label>
            <input
              type="text"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              placeholder="user-123"
              className="h-9 w-full rounded-md border border-input bg-background px-3 font-mono text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.agentId", "Agent ID")}
            </label>
            <input
              type="text"
              value={agentId}
              onChange={(e) => setAgentId(e.target.value)}
              placeholder="abc123"
              className="h-9 w-full rounded-md border border-input bg-background px-3 font-mono text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("userConversations.environment", "Environment")}
            </label>
            <select
              value={environment}
              onChange={(e) => setEnvironment(e.target.value)}
              className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            >
              {ENVIRONMENTS.map((env) => (
                <option key={env} value={env}>
                  {env}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            {t("userConversations.conversationId", "Conversation ID")}
          </label>
          <input
            type="text"
            value={conversationId}
            onChange={(e) => setConversationId(e.target.value)}
            placeholder="conv-xyz-789"
            className="h-9 w-full rounded-md border border-input bg-background px-3 font-mono text-sm focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="secondary" onClick={onClose} size="sm">
            {t("common.cancel")}
          </Button>
          <Button
            onClick={handleCreate}
            disabled={!isValid || createMutation.isPending}
            size="sm"
            data-testid="uc-save-btn"
          >
            {createMutation.isPending
              ? t("common.saving", "Saving...")
              : t("common.save")}
          </Button>
        </div>
      </div>
    </div>
  );
}
