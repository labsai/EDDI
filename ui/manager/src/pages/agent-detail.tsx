import { ConversationReviewSection } from "@/components/editors/conversation-review-section";
import { RequestAccessPanel } from "@/components/workspaces/request-access-panel";
import { isForbidden } from "@/lib/access";
import { useState, useEffect, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { deployedEnvironments, isLiveAtRequestedVersion, preferredChatEnvironment } from "@/lib/deployment-environments";
import type { Environment } from "@/lib/constants";
import { useQueryClient } from "@tanstack/react-query";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Bot,
  Workflow,
  Rocket,
  Clock,
  AlertTriangle,
  Plus,
  Trash2,
  RefreshCw,
  AlertCircle,
  ExternalLink,
  Settings,
  Download,
  Copy,
  Server,
  MessageSquare,
  Handshake,
  Link2,
  X,
  ChevronDown,
  ChevronRight,
  ArrowUpCircle,
  Sparkles,
  Info,
  CircleDashed,
  Save,
  Undo2,
  GitCompareArrows,
} from "lucide-react";
import { cn, formatRelativeTime } from "@/lib/utils";
import { accessForDetail } from "@/lib/access";
import { useSpaces } from "@/hooks/use-spaces";
import { toast } from "sonner";
import { getErrorMessage, isApiError } from "@/lib/api-client";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  useAgent,
  useDeploymentStatus,
  useDeploymentStatuses,
  useDeployAgent,
  useUndeployAgent,
  useDeleteAgent,
  useDuplicateAgent,
  useAgentVersions,
  useUpdateAgent,
} from "@/hooks/use-agents";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import {
  agentJson,
  applyChangedFields,
  changedAgentFields,
  isAgentDraftDirty,
  type AgentEdit,
} from "@/lib/agent-draft";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import { agentKeys } from "@/lib/query-keys";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { ResourceDiffViewer } from "@/components/agents/resource-diff-viewer";
import { ExportAgentDialog } from "@/components/agents/export-agent-dialog";
import { CompatibilityGenerationBadge } from "@/components/agents/compatibility-generation-badge";
import { DeploymentImpactPanel } from "@/components/agents/deployment-impact-panel";
import { useWorkflowDescriptors } from "@/hooks/use-workflows";
import { parseResourceUri, type EnvironmentStatus, type Agent, deployAgent, getDeploymentStatus } from "@/lib/api/agents";
import { useLatestVersions } from "@/hooks/use-latest-versions";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore, useStartConversation } from "@/hooks/use-chat";
import {
  SecurityIdentitySection,
  CapabilitiesSection,
  UserMemorySection,
  MemoryPolicySection,
  SessionManagementSection,
  HitlConfigSection,
  ChannelsSection,
} from "@/components/editors/agent-config-sections";

/* ─── Status icons (labels resolved via i18n in component) ─── */
const statusIcons = {
  READY: { icon: Rocket, color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10" },
  NOT_FOUND: { icon: CircleDashed, color: "text-muted-foreground", bg: "bg-muted" },
};

const envLabels: Record<string, string> = {
  production: "agentDetail.envProduction",
  test: "agentDetail.envTest",
};

/* ─── Main page ─── */
export function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [version, setVersion] = useState<number | undefined>(undefined);
  const [showAddWorkflow, setShowAddWorkflow] = useState(false);
  /**
   * The agent document as edited on this page, or `null` while nothing has been
   * touched. Every section edits it; nothing reaches the backend until Save,
   * which writes it as ONE new version.
   */
  const [draft, setDraft] = useState<Agent | null>(null);
  const [showDiscardDialog, setShowDiscardDialog] = useState(false);
  const [showReviewDialog, setShowReviewDialog] = useState(false);
  /**
   * Set when a save hit a 409: the document the draft was built on. Once the
   * page has moved onto the newer version, the draft's own edits are re-applied
   * on top of it (see the effect below), so another client's change is kept.
   */
  const [rebaseFrom, setRebaseFrom] = useState<Agent | null>(null);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showExportDialog, setShowExportDialog] = useState(false);
  // Undeploy confirmation — tracks which environment is being undeployed plus
  // the two destructive query options (default OFF).
  const [undeployTarget, setUndeployTarget] = useState<{ environment: string } | null>(null);
  const [undeployEndConversations, setUndeployEndConversations] = useState(false);
  const [undeployPreviousVersions, setUndeployPreviousVersions] = useState(false);

  // Reset version when navigating to a different agent (React reuses
  // the component, so useState values persist across route param changes).
  useEffect(() => {
    setVersion(undefined);
    setDraft(null);
  }, [id]);

  const { data: versions } = useAgentVersions(id!);
  // Delete (and sharing) need OWN. An EDIT grantee was offered Delete here and
  // met a 403 — the agents list already hid it for them via the same level.
  // Only consulted when no descriptor for this id came back — see accessForDetail.
  // `enforcement`, not `enabled`: a failed /workspaces must not read as "off".
  const workspacesEnforced = useSpaces().enforcement;
  const access = accessForDetail(versions, id, workspacesEnforced);

  // Default to latest version once loaded
  const resolvedVersion = version ?? versions?.[0]?.version ?? 1;

  const { data: agent, isLoading, isError, error: loadError, refetch } = useAgent(id!, resolvedVersion);
  const { data: deployment } = useDeploymentStatus(id!, resolvedVersion);
  const { data: envStatuses } = useDeploymentStatuses(id!, resolvedVersion);
  // Chat where the agent is ACTUALLY live. Production wins when it is live (the
  // environment a reader assumes), otherwise the first live one — which is what
  // makes a test-only agent reachable at all.
  const liveEnvironments = deployedEnvironments(envStatuses);
  const chatEnvironment: Environment = preferredChatEnvironment(liveEnvironments);
  /**
   * Whether a chat is reachable AT ALL, and therefore whether the chat controls
   * appear. Derived from every environment, not from production: `isDeployed`
   * below is production-only (it drives the production deploy/undeploy toggle),
   * and reusing it here hid the Chat button from a test-only agent — the exact
   * bug this page's sibling card had.
   */
  const isChatReachable = liveEnvironments.length > 0;

  const deployMutation = useDeployAgent();
  const undeployMutation = useUndeployAgent();
  const deleteMutation = useDeleteAgent();
  const duplicateMutation = useDuplicateAgent();
  const updateAgentMutation = useUpdateAgent();
  const startConversationMutation = useStartConversation();

  const status = deployment?.status ?? "NOT_FOUND";
  const config = statusIcons[status];
  const StatusIcon = config.icon;
  const statusLabels: Record<string, string> = {
    READY: t("status.deployed", "Deployed"),
    IN_PROGRESS: t("status.deploying", "Deploying..."),
    ERROR: t("status.error", "Error"),
    NOT_FOUND: t("status.notDeployed", "Not deployed"),
  };
  const statusLabel = statusLabels[status] ?? status;
  const isDeployed = status === "READY";
  const isBusy = deployMutation.isPending || undeployMutation.isPending || status === "IN_PROGRESS";

  // Derive agent display name from the version descriptor (the full agent
  // config returned by useAgent does NOT include name/description).
  const agentDisplayName = versions?.[0]?.name || id!;

  // Version staleness detection for workflow references
  const workflowUris = useMemo(
    () => (agent?.workflows ?? []).filter((u) => u.includes("://")),
    [agent?.workflows]
  );
  const { data: latestVersions } = useLatestVersions(workflowUris);

  // ── Draft ──
  const editable = draft ?? agent;
  const isDirty = isAgentDraftDirty(draft, agent);
  const changedFields = useMemo(() => changedAgentFields(draft, agent), [draft, agent]);
  const isSaving = updateAgentMutation.isPending;
  /** Whether any of these top-level fields holds an unsaved edit. */
  const touched = (...fields: string[]) => fields.some((f) => changedFields.has(f));

  const editAgent = useCallback<AgentEdit>(
    (next) => {
      if (!agent || !editable) return;
      // Applied onto the latest draft, not taken wholesale: an edit built from
      // a slightly older render must not undo the one before it.
      setDraft((prev) => applyChangedFields(prev ?? agent, editable, next));
    },
    [agent, editable],
  );

  /** Writes the draft as one new version. Resolves whether it landed. */
  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!draft || !isDirty) return true;
    const sent = draft;
    try {
      const result = await updateAgentMutation.mutateAsync({ id: id!, version: resolvedVersion, agent: sent });
      const created = parseVersionFromLocation(result?.location);
      // Edits made while the request was in flight stay in the draft.
      setDraft((current) => (current === sent ? null : current));
      // Follow the version the save created. The mutation has already seeded
      // its cache with what was sent, so the page does not flash the old one.
      if (created !== null) setVersion(created);
      toast.success(
        created !== null
          ? t("agentDetail.savedAsVersion", "Saved as version {{version}}", { version: created })
          : t("agentDetail.saved", "Agent saved"),
      );
      return true;
    } catch (err) {
      if (isApiError(err) && err.status === 409) {
        // Someone saved a newer version meanwhile. Keep the edits, follow the
        // latest version again (a save pins the one it created), and refetch;
        // the rebase effect then moves the edits onto that version.
        if (agent) setRebaseFrom(agent);
        setVersion(undefined);
        void queryClient.invalidateQueries({ queryKey: agentKeys.all });
        toast.error(
          t(
            "agentDetail.saveConflict",
            "This agent was changed elsewhere since you opened it. Your edits are kept: review them and save again.",
          ),
        );
      } else {
        toast.error(getErrorMessage(err));
      }
      return false;
    }
  }, [draft, isDirty, agent, updateAgentMutation, id, resolvedVersion, queryClient, t]);

  useEffect(() => {
    if (!rebaseFrom || !agent || agent === rebaseFrom) return;
    // Only the top-level blocks the user changed are carried over; everything
    // else comes from the newer version.
    setDraft((current) => (current ? applyChangedFields(agent, rebaseFrom, current) : current));
    setRebaseFrom(null);
  }, [agent, rebaseFrom]);

  function confirmDiscard() {
    setDraft(null);
    setShowDiscardDialog(false);
    setShowReviewDialog(false);
  }

  // Tab close / reload, and (through the app-wide navigation guard) leaving by
  // any link, the back button or the command palette, with Save & leave.
  useUnsavedChangesGuard(isDirty, { onSave: saveDraft });

  // Ctrl/Cmd+S saves while there is something to save.
  useEffect(() => {
    if (!isDirty) return;
    function onKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (!isSaving) void saveDraft();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isDirty, isSaving, saveDraft]);

  function handleDeploy() {
    deployMutation.mutate(
      { agentId: id!, version: resolvedVersion },
      {
        onSuccess: () => toast.success(t("agents.deploySuccess", "Deployment started")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function openUndeployDialog(environment: string) {
    setUndeployTarget({ environment });
  }

  function closeUndeployDialog() {
    setUndeployTarget(null);
    setUndeployEndConversations(false);
    setUndeployPreviousVersions(false);
  }

  function confirmUndeploy() {
    if (!undeployTarget) return;
    undeployMutation.mutate(
      {
        environment: undeployTarget.environment,
        agentId: id!,
        version: resolvedVersion,
        endAllActiveConversations: undeployEndConversations,
        undeployAllPreviousVersions: undeployPreviousVersions,
      },
      {
        onSuccess: () => {
          toast.success(t("agents.undeploySuccess", "Agent undeployed"));
          closeUndeployDialog();
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function handleDelete() {
    deleteMutation.mutate(
      { id: id!, version: resolvedVersion },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          setShowDeleteDialog(false);
          navigate("/manage/agents");
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  async function handleDuplicate() {
    try {
      const result = await duplicateMutation.mutateAsync({
        id: id!,
        version: resolvedVersion,
        deepCopy: true,
      });
      toast.success(t("agentDetail.duplicateSuccess"));
      const { id: newId } = parseResourceUri(result.location);
      navigate(`/manage/agentview/${newId}`);
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  }

  function handleRemoveWorkflow(packageUri: string) {
    if (!editable?.workflows) return;
    editAgent({ ...editable, workflows: editable.workflows.filter((p) => p !== packageUri) });
  }

  function handleAddWorkflow(packageUri: string) {
    if (!editable) return;
    const current = editable.workflows ?? [];
    if (current.includes(packageUri)) return;
    editAgent({ ...editable, workflows: [...current, packageUri] });
    setShowAddWorkflow(false);
  }

  function handleUpdateWorkflowVersion(oldUri: string, newVersion: number) {
    if (!editable?.workflows) return;
    const updated = editable.workflows.map((u) =>
      u === oldUri ? u.replace(/([?&]version=)\d+/, `$1${newVersion}`) : u,
    );
    editAgent({ ...editable, workflows: updated });
  }

  const handleVersionChange = useCallback(
    (v: number) => {
      // Choosing the latest version means "follow the latest", not "pin this
      // number": pinned, the page stayed on it after the next inline save
      // created a newer one, and every section went on editing the old one.
      setVersion(v === versions?.[0]?.version ? undefined : v);
    },
    [versions],
  );

  if (isLoading && !agent) {
    return (
      <div className="flex items-center justify-center py-20" data-testid="agent-detail-loading">
        <RefreshCw className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (isForbidden(loadError)) {
    return (
      <div className="space-y-4">
        <BackLink />
        <RequestAccessPanel resourceId={id!} isAgent />
      </div>
    );
  }

  if (isError || !agent) {
    return (
      <div className="space-y-4">
        <BackLink />
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">{t("common.error")}</p>
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

  const latestVersion = versions?.[0]?.version ?? resolvedVersion;
  const isNotLatest = resolvedVersion < latestVersion;
  /** What the sections show and edit: the draft, or the stored document. */
  const doc = editable ?? agent;
  const modifiedSectionCount = [
    touched("workflows"),
    touched("a2aEnabled", "description", "a2aSkills"),
    touched("security", "identity"),
    touched("capabilities"),
    touched("enableMemoryTools", "userMemoryConfig"),
    touched("memoryPolicy"),
    touched("sessionManagement"),
    touched("conversationReview"),
    touched("hitlConfig"),
    touched("channels"),
  ].filter(Boolean).length;
  // Deploying acts on the SAVED version; with edits pending that is rarely
  // what was meant, so it waits until they are saved or discarded.
  const deployBlockedTitle = isDirty
    ? t("agentDetail.saveBeforeDeploy", "Save or discard your changes before deploying")
    : undefined;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-2">
          <BackLink />
          <div className="flex items-center gap-3">
            <Bot className="h-8 w-8 text-primary" />
            <div>
              <h1 className="text-3xl font-bold text-foreground">
                {versions?.find(v => v.version === resolvedVersion)?.name || t("agentDetail.title", "Agent Detail")}
              </h1>
              <p className="font-mono text-sm text-muted-foreground" data-testid="agent-id">
                {id}
              </p>
            </div>
          </div>
          {/* Version row — which version, and which compatible line it is on */}
          {versions && versions.length > 0 && (
            <div className="flex flex-wrap items-center gap-2" data-testid="agent-version-row">
              <VersionSelect
                versions={versions}
                current={resolvedVersion}
                onChange={handleVersionChange}
                disabled={isDirty || isSaving}
              />
              <CompatibilityGenerationBadge generation={agent.compatibilityGeneration} />
            </div>
          )}
        </div>

        {/* Actions — edit state, then primary (deploy/chat), then secondary (tools) */}
        <div className="flex flex-col gap-2 items-end">
          {/* Edits — same controls as the workflow and resource editors */}
          <div className="flex flex-wrap items-center justify-end gap-2" data-testid="agent-save-controls">
            {isDirty && (
              <button
                type="button"
                onClick={() => setShowReviewDialog(true)}
                className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 hover:bg-amber-200 transition-colors dark:bg-amber-900/30 dark:text-amber-400 dark:hover:bg-amber-900/50"
                title={t("agentDetail.reviewChanges", "Review changes")}
                data-testid="dirty-indicator"
              >
                <AlertCircle className="h-3 w-3" aria-hidden="true" />
                {t("editor.dirty", "Unsaved changes")}
              </button>
            )}
            <button
              type="button"
              onClick={() => setShowDiscardDialog(true)}
              disabled={!isDirty || isSaving}
              className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
              data-testid="discard-btn"
            >
              <Undo2 className="h-4 w-4" aria-hidden="true" />
              {t("editor.discard", "Discard")}
            </button>
            <button
              type="button"
              onClick={() => void saveDraft()}
              disabled={!isDirty || isSaving}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98] disabled:opacity-50"
              data-testid="save-btn"
            >
              <Save className="h-4 w-4" aria-hidden="true" />
              {isSaving ? t("editor.saving", "Saving...") : t("editor.save", "Save")}
            </button>
          </div>

          {/* Primary actions: status + deploy + chat */}
          <div className="flex flex-wrap items-center gap-2">
            {/* Status badge */}
            <div
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium",
                config.bg,
                config.color
              )}
              data-testid="deployment-status"
            >
              <StatusIcon className="h-4 w-4" />
              {statusLabel}
            </div>

            {/* Deploy/Undeploy */}
            <button
              onClick={isDeployed ? () => openUndeployDialog("production") : handleDeploy}
              disabled={isBusy || isDirty}
              title={deployBlockedTitle}
              className={cn(
                "rounded-lg px-4 py-2 text-sm font-medium transition-colors",
                isDeployed
                  ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                  : "bg-primary text-primary-foreground hover:bg-primary/90",
                (isBusy || isDirty) && "cursor-not-allowed opacity-50"
              )}
              data-testid="deploy-btn"
            >
              {isBusy
                ? t("common.loading")
                : isDeployed
                  ? t("agents.undeploy")
                  : t("agents.deploy")}
            </button>

            {/* Deploy & Chat */}
            {!isChatReachable && !isBusy && (
              <button
                onClick={async () => {
                  const drawerStore = useChatDrawerStore.getState();
                  const chatStore = useChatStore.getState();
                  // Named, not defaulted: this branch deploys to production, and
                  // the drawer's "New conversation" must start there too.
                  drawerStore.open(id!, agentDisplayName, "production");
                  drawerStore.setStep("deploying");
                  try {
                    await deployAgent("production", id!, resolvedVersion);
                    for (let i = 0; i < 15; i++) {
                      await new Promise(r => setTimeout(r, 2000));
                      const s = await getDeploymentStatus("production", id!, resolvedVersion);
                      if (s.status === "READY") break;
                      if (s.status === "ERROR") throw new Error("Deploy failed");
                    }
                    // Invalidate immediately after deployment is confirmed so
                    // caches are fresh even if the conversation start fails.
                    queryClient.invalidateQueries({ queryKey: ["agents"] });
                    queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
                    drawerStore.setStep("starting");
                    chatStore.clearMessages();
                    chatStore.setSelectedAgent(id!, agentDisplayName);
                    // Same environment this branch just deployed to. It only
                    // runs when nothing is live, so the two cannot disagree —
                    // but naming it keeps them from drifting apart later.
                    await startConversationMutation.mutateAsync({ agentId: id!, environment: "production" });
                    drawerStore.setStep("ready");
                  } catch (err) {
                    drawerStore.setStep("error", getErrorMessage(err));
                  }
                }}
                disabled={startConversationMutation.isPending || isDirty}
                title={deployBlockedTitle}
                className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400 disabled:opacity-50 disabled:cursor-not-allowed"
                data-testid="deploy-chat-btn"
              >
                <Rocket className="h-4 w-4" aria-hidden="true" />
                {t("agents.deployAndChat", "Deploy & Chat")}
              </button>
            )}

            {/* Chat — split button: inline drawer + external chat UI */}
            <div className="inline-flex">
              <button
                onClick={async () => {
                  const drawerStore = useChatDrawerStore.getState();
                  const chatStore = useChatStore.getState();
                  // The environment the conversation is started in, so the
                  // drawer's "New conversation" lands there as well. Left to
                  // the default it restarted a test-only agent in production,
                  // where it is not deployed.
                  drawerStore.open(id!, agentDisplayName, chatEnvironment);
                  if (isChatReachable) {
                    drawerStore.setStep("starting");
                    chatStore.clearMessages();
                    chatStore.setSelectedAgent(id!, agentDisplayName);
                    try {
                      await startConversationMutation.mutateAsync({ agentId: id!, environment: chatEnvironment });
                      drawerStore.setStep("ready");
                    } catch (err) {
                      drawerStore.setStep("error", getErrorMessage(err));
                    }
                  }
                }}
                disabled={startConversationMutation.isPending}
                className={cn(
                  "inline-flex items-center gap-1.5 bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400 disabled:opacity-50 disabled:cursor-not-allowed",
                  isChatReachable ? "rounded-s-lg" : "rounded-lg"
                )}
                data-testid="chat-btn"
                aria-label={t("agents.chat", "Chat")}
              >
                <MessageSquare className="h-4 w-4" aria-hidden="true" />
                {t("agents.chat", "Chat")}
              </button>
              {isChatReachable && (
                <a
                  href={`/chat/${chatEnvironment}/${id}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center rounded-e-lg border-s border-emerald-500/20 bg-emerald-500/10 px-2.5 py-2 text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
                  title={t("agents.openExternalChat", "Open in new tab")}
                  aria-label={t("agents.openExternalChat", "Open in new tab")}
                  data-testid="external-chat-btn"
                >
                  <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                </a>
              )}
            </div>
          </div>

          {/* Secondary actions: studio, duplicate, export, delete */}
          <div className="flex flex-wrap items-center gap-1.5">
            <Link
              to={`/manage/studio/${id}`}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary/20 transition-colors"
              data-testid="open-studio-btn"
            >
              <Sparkles className="h-3.5 w-3.5" />
              {t("agentDetail.openStudio", "Open in Studio")}
            </Link>
            <button
              onClick={handleDuplicate}
              disabled={duplicateMutation.isPending}
              className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
              data-testid="duplicate-agent-btn"
            >
              <Copy className="h-3.5 w-3.5" />
              {duplicateMutation.isPending ? t("agentDetail.duplicating") : t("agentDetail.duplicate")}
            </button>
            <button
              onClick={() => setShowExportDialog(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors"
              data-testid="export-agent-btn"
            >
              <Download className="h-3.5 w-3.5" />
              {t("agents.export", "Export")}
            </button>
            {access.canOwn && (
              <button
                onClick={() => setShowDeleteDialog(true)}
                className="inline-flex items-center gap-1.5 rounded-lg bg-destructive/10 px-3 py-1.5 text-xs font-medium text-destructive hover:bg-destructive/20 transition-colors"
                data-testid="delete-agent-btn"
                aria-label={t("agents.deleteAgent", "Delete agent")}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Non-latest version warning */}
      {isNotLatest && (
        <div className="flex items-center gap-3 rounded-lg border border-warning/30 bg-warning/5 px-4 py-3" data-testid="non-latest-warning">
          <Info className="h-5 w-5 text-warning shrink-0" />
          <p className="flex-1 text-sm text-foreground">
            {t("agentDetail.viewingOldVersion", "You are viewing version {{current}}. Latest is version {{latest}}.", { current: resolvedVersion, latest: latestVersion })}
          </p>
          <Button
            variant="warning"
            size="sm"
            onClick={() => handleVersionChange(latestVersion)}
            className="shrink-0"
          >
            {t("agentDetail.switchToLatest", "Switch to latest")}
          </Button>
        </div>
      )}

      {/* Environment Status Badges */}
      {envStatuses && envStatuses.length > 0 && (
        <EnvironmentBadges
          agentId={id!}
          version={resolvedVersion}
          statuses={envStatuses}
          onDeploy={(env) => deployMutation.mutate(
            { environment: env, agentId: id!, version: resolvedVersion },
            { onError: (err) => toast.error(getErrorMessage(err)) }
          )}
          onUndeploy={(env) => openUndeployDialog(env)}
          isBusy={isBusy}
          blockedTitle={deployBlockedTitle}
        />
      )}

      {/* ══════ Config sections — ordered by importance ══════ */}

      {/* Workflows — promoted to top */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Workflow className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("agentDetail.packages", "Workflows")}
            </h2>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {doc.workflows?.length ?? 0}
            </span>
            {touched("workflows") && <ModifiedPill />}
          </div>
          <button
            onClick={() => setShowAddWorkflow(!showAddWorkflow)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/20 transition-colors"
            data-testid="add-workflow-btn"
          >
            <Plus className="h-4 w-4" />
            {t("agentDetail.addWorkflow", "Add Workflow")}
          </button>
        </div>

        {/* Workflow list — clickable cards */}
        <div className="divide-y divide-border">
          {(!doc.workflows || doc.workflows.length === 0) && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <Workflow className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">{t("agentDetail.noWorkflows", "No workflows added yet")}</p>
            </div>
          )}

          {doc.workflows?.map((wfUri) => {
            const { id: wfId, version: wfVersion } = parseResourceUri(wfUri);
            const latestVer = latestVersions?.[wfId];
            const isStale = latestVer !== undefined && latestVer > wfVersion;
            const workflowLink = `/manage/workflowview/${wfId}?agentId=${id}&agentVer=${resolvedVersion}`;
            return (
              <div
                key={wfUri}
                className={cn(
                  "group flex items-center gap-3 px-5 py-4 transition-colors",
                  isStale
                    ? "bg-amber-50/50 dark:bg-amber-900/10 hover:bg-amber-50 dark:hover:bg-amber-900/20"
                    : "hover:bg-secondary/50"
                )}
              >
                <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary/10 shrink-0">
                  <Workflow className="h-4 w-4 text-primary" />
                </div>
                <Link
                  to={workflowLink}
                  className="flex-1 min-w-0"
                >
                  <p className="text-sm font-semibold text-foreground group-hover:text-primary transition-colors truncate">
                    {wfId}
                  </p>
                  <div className="flex items-center gap-2 mt-0.5">
                    <span className="inline-flex items-center rounded-md bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                      v{wfVersion}
                    </span>
                    {isStale && (
                      <span className="text-[10px] text-amber-600 dark:text-amber-400 font-medium">
                        {t("agentDetail.outdated", "outdated")}
                      </span>
                    )}
                  </div>
                </Link>
                <div className="flex items-center gap-1.5 shrink-0">
                  {isStale && (
                    <button
                      onClick={(e) => { e.preventDefault(); handleUpdateWorkflowVersion(wfUri, latestVer!); }}
                      disabled={isSaving}
                      className="inline-flex items-center gap-1 rounded-md bg-amber-100 px-2 py-1 text-[10px] font-semibold text-amber-800 hover:bg-amber-200 dark:bg-amber-900/30 dark:text-amber-400 dark:hover:bg-amber-900/50 transition-colors disabled:opacity-50"
                      title={t("agentDetail.updateToLatest", "Update to latest version")}
                      data-testid={`update-workflow-${wfId}`}
                    >
                      <ArrowUpCircle className="h-3 w-3" />
                      v{latestVer}
                    </button>
                  )}
                  <Link
                    to={workflowLink}
                    className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary hover:bg-primary/20 transition-colors"
                  >
                    {t("agentDetail.openWorkflow", "Open")}
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                  <button
                    onClick={() => handleRemoveWorkflow(wfUri)}
                    disabled={isSaving}
                    className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors disabled:opacity-50"
                    title={t("common.delete")}
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {/* Add package panel */}
      {showAddWorkflow && (
        <AddWorkflowPanel
          currentWorkflows={doc.workflows ?? []}
          onAdd={handleAddWorkflow}
          onClose={() => setShowAddWorkflow(false)}
        />
      )}

      {/* A2A Protocol (collapsible, hidden by default) */}
      <A2ASection
        agent={doc}
        agentId={id!}
        onChange={editAgent}
        disabled={isSaving}
        modified={touched("a2aEnabled", "description", "a2aSkills")}
      />

      {/* Security & Identity */}
      <SecurityIdentitySection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("security", "identity")} />

      {/* Capabilities */}
      <CapabilitiesSection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("capabilities")} />

      {/* User Memory */}
      <UserMemorySection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("enableMemoryTools", "userMemoryConfig")} />

      {/* Memory Policy */}
      <MemoryPolicySection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("memoryPolicy")} />

      {/* Session Management */}
      <SessionManagementSection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("sessionManagement")} />

      {/* Usage, and opt-in review of conversations by the agent's maintainers */}
      <ConversationReviewSection
        agent={doc}
        agentId={id!}
        onChange={editAgent}
        disabled={isSaving}
        modified={touched("conversationReview")}
      />

      {/* Human-in-the-Loop */}
      <HitlConfigSection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("hitlConfig")} />

      {/* Channel Connectors */}
      <ChannelsSection agent={doc} onChange={editAgent} disabled={isSaving} modified={touched("channels")} />

      {/* Raw config (collapsible) */}
      <RawConfigSection agent={doc} />

      {/* Floating save bar — the header's controls scroll away on a page this
          long, and the edit is usually made far below them. */}
      {isDirty && (
        <div
          className="sticky bottom-4 z-20 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-500/30 bg-card/95 px-4 py-3 shadow-lg backdrop-blur"
          role="region"
          aria-label={t("editor.dirty", "Unsaved changes")}
          data-testid="unsaved-bar"
        >
          <p className="flex items-center gap-2 text-sm text-foreground">
            <span className="h-2 w-2 shrink-0 rounded-full bg-amber-500" aria-hidden="true" />
            {t("agentDetail.unsavedSections", "Sections with unsaved changes: {{count}}", {
              count: modifiedSectionCount,
            })}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setShowReviewDialog(true)}
              className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
              data-testid="review-changes-btn"
            >
              <GitCompareArrows className="h-4 w-4" aria-hidden="true" />
              {t("agentDetail.reviewChanges", "Review changes")}
            </button>
            <button
              type="button"
              onClick={() => setShowDiscardDialog(true)}
              disabled={isSaving}
              className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-1.5 text-sm font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
              data-testid="unsaved-bar-discard"
            >
              <Undo2 className="h-4 w-4" aria-hidden="true" />
              {t("editor.discard", "Discard")}
            </button>
            <button
              type="button"
              onClick={() => void saveDraft()}
              disabled={isSaving}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              data-testid="unsaved-bar-save"
            >
              <Save className="h-4 w-4" aria-hidden="true" />
              {isSaving ? t("editor.saving", "Saving...") : t("editor.save", "Save")}
            </button>
          </div>
        </div>
      )}

      {/* Review changes — the draft against the saved version */}
      <AccessibleDialog
        open={showReviewDialog}
        onClose={() => setShowReviewDialog(false)}
        title={t("agentDetail.reviewChangesTitle", "Unsaved changes to version {{version}}", { version: resolvedVersion })}
        maxWidth="max-w-4xl"
        testId="review-changes-dialog"
      >
        <div className="space-y-4 p-5">
          <ResourceDiffViewer
            targetContent={agentJson(agent)}
            sourceContent={agentJson(draft)}
            labels={{
              target: t("agentDetail.savedVersion", "Saved v{{version}}", { version: resolvedVersion }),
              source: t("agentDetail.yourChanges", "Your changes"),
            }}
          />
          <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
            <button
              type="button"
              onClick={() => setShowReviewDialog(false)}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              {t("editor.keepEditing", "Keep editing")}
            </button>
            <button
              type="button"
              onClick={() => setShowDiscardDialog(true)}
              disabled={isSaving}
              className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors disabled:opacity-50"
            >
              {t("editor.discardChanges", "Discard changes")}
            </button>
            <button
              type="button"
              onClick={async () => {
                if (await saveDraft()) setShowReviewDialog(false);
              }}
              disabled={isSaving || !isDirty}
              className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              data-testid="review-save-btn"
            >
              <Save className="h-4 w-4" aria-hidden="true" />
              {isSaving ? t("editor.saving", "Saving...") : t("editor.save", "Save")}
            </button>
          </div>
        </div>
      </AccessibleDialog>

      {/* Discard confirmation */}
      <UnsavedChangesDialog
        open={showDiscardDialog}
        title={t("editor.discardTitle", "Discard Changes?")}
        message={t("agentDetail.discardMessage", "Every unsaved change on this page goes back to what version {{version}} holds.", {
          version: resolvedVersion,
        })}
        confirmLabel={t("editor.discard", "Discard")}
        cancelLabel={t("editor.keepEditing", "Keep editing")}
        onConfirm={confirmDiscard}
        onCancel={() => setShowDiscardDialog(false)}
      />

      {/* Undeploy confirmation dialog */}
      <AlertDialog
        open={undeployTarget !== null}
        onOpenChange={(open) => {
          if (!open) closeUndeployDialog();
        }}
        title={t("agents.confirmUndeploy", "Undeploy agent?")}
        description={t(
          "agents.confirmUndeployDescription",
          "Version {{version}} will be undeployed from {{environment}}.",
          {
            version: resolvedVersion,
            environment: undeployTarget
              ? t(envLabels[undeployTarget.environment] ?? undeployTarget.environment, undeployTarget.environment)
              : "",
          }
        )}
        confirmLabel={t("agents.undeploy")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmUndeploy}
        variant="destructive"
        isPending={undeployMutation.isPending}
      >
        <div className="space-y-3">
          <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-border/60 bg-muted/30 p-3 text-sm">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 accent-destructive"
              checked={undeployEndConversations}
              onChange={(e) => setUndeployEndConversations(e.target.checked)}
              data-testid="undeploy-end-conversations-checkbox"
            />
            <span className="text-muted-foreground">
              <span className="block font-medium text-foreground">
                {t("agents.undeployEndConversationsLabel", "End all active conversations")}
              </span>
              <span className="mt-0.5 block text-xs text-destructive">
                {t(
                  "agents.undeployEndConversationsHint",
                  "Immediately terminates every in-progress conversation on this deployment. This cannot be undone."
                )}
              </span>
              <span className="mt-0.5 block text-xs" data-testid="undeploy-compatible-hint">
                {t(
                  "agents.undeployCompatibleHint",
                  "Conversations that a compatible deployed version can continue are not ended: they move to it on their next turn.",
                )}
              </span>
            </span>
          </label>
          <label className="flex cursor-pointer items-start gap-2 rounded-lg border border-border/60 bg-muted/30 p-3 text-sm">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 accent-destructive"
              checked={undeployPreviousVersions}
              onChange={(e) => setUndeployPreviousVersions(e.target.checked)}
              data-testid="undeploy-previous-versions-checkbox"
            />
            <span className="text-muted-foreground">
              <span className="block font-medium text-foreground">
                {t("agents.undeployPreviousVersionsLabel", "Undeploy this and all previous versions")}
              </span>
              <span className="mt-0.5 block text-xs">
                {t(
                  "agents.undeployPreviousVersionsHint",
                  "Also removes every earlier version of this agent from the environment."
                )}
              </span>
            </span>
          </label>
        </div>
      </AlertDialog>

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={showDeleteDialog}
        onOpenChange={setShowDeleteDialog}
        title={t("agents.confirmDelete")}
        description={t("agents.confirmDeleteDescription", "This action cannot be undone. The agent and all its data will be permanently removed.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      />

      {/* Export dialog */}
      <ExportAgentDialog
        open={showExportDialog}
        onClose={() => setShowExportDialog(false)}
        agentId={id!}
        agentVersion={resolvedVersion}
      />
    </div>
  );
}

/* ─── Sub-components ─── */

/** The "Modified" marker the config sections show — see `EditorSection`. */
function ModifiedPill() {
  const { t } = useTranslation();
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400"
      data-testid="section-modified"
    >
      <span className="h-1.5 w-1.5 rounded-full bg-amber-500" aria-hidden="true" />
      {t("editor.modified", "Modified")}
    </span>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/manage/agents"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {t("agentDetail.backToAgents", "Back to Agents")}
    </Link>
  );
}

/* ─── Version selector ─── */
function VersionSelect({
  versions,
  current,
  onChange,
  disabled = false,
}: {
  versions: { version: number; lastModifiedOn: number }[];
  current: number;
  onChange: (v: number) => void;
  /** While there are unsaved edits — switching would silently drop them. */
  disabled?: boolean;
}) {
  if (versions.length <= 1) {
    return (
      <span
        className="inline-flex items-center gap-1.5 rounded-md bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground"
        data-testid="version-badge"
      >
        v{current}
      </span>
    );
  }

  return (
    <select
      value={current}
      onChange={(e) => onChange(Number(e.target.value))}
      disabled={disabled}
      className="rounded-md border border-input bg-background px-2.5 py-1 text-xs font-medium text-foreground shadow-sm transition-colors hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
      data-testid="version-picker"
    >
      {versions.map((v) => (
        <option key={v.version} value={v.version}>
          v{v.version}
          {v.lastModifiedOn
            ? ` — ${formatRelativeTime(v.lastModifiedOn)}`
            : ""}
        </option>
      ))}
    </select>
  );
}

// formatRelativeTime imported from @/lib/utils

/* ─── Environment Status Badges ─── */
function EnvironmentBadges({
  agentId,
  version,
  statuses,
  onDeploy,
  onUndeploy,
  isBusy,
  blockedTitle,
}: {
  agentId: string;
  version: number;
  statuses: EnvironmentStatus[];
  onDeploy: (env: string) => void;
  onUndeploy: (env: string) => void;
  isBusy: boolean;
  /** Set while deploying is held back (unsaved edits); the reason, as a tooltip. */
  blockedTitle?: string;
}) {
  const { t } = useTranslation();
  const envStatusLabels: Record<string, string> = {
    READY: t("status.deployed", "Deployed"),
    IN_PROGRESS: t("status.deploying", "Deploying..."),
    ERROR: t("status.error", "Error"),
    NOT_FOUND: t("status.notDeployed", "Not deployed"),
  };

  return (
    <section className="overflow-hidden rounded-xl border bg-card shadow-sm" data-testid="env-badges">
      <div className="flex items-center gap-2 border-b border-border px-5 py-3">
        <Server className="h-5 w-5 text-primary" />
        <h2 className="text-sm font-semibold text-foreground">
          {t("agentDetail.environments", "Environments")}
        </h2>
      </div>
      <div className="grid grid-cols-1 gap-0 divide-y divide-border sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {statuses.map((entry) => {
          const { environment, status, deployedVersion } = entry;
          const conf = statusIcons[status];
          const Icon = conf.icon;
          // The button acts on the version this page shows. An environment
          // live at an OLDER version is shown as deployed (with its version),
          // but its action is Deploy: undeploying the page's version would hit
          // a version that is not running — the backend still answers 202 and
          // disables every schedule of the agent, while the old version keeps
          // serving.
          const isUp = isLiveAtRequestedVersion(entry);
          return (
            <div key={environment} className="flex items-center justify-between gap-3 px-5 py-3">
              <div className="flex items-center gap-2">
                <div className={cn("rounded-full p-1.5", conf.bg)}>
                  <Icon className={cn("h-3.5 w-3.5", conf.color)} />
                </div>
                <div>
                  <p className="text-sm font-medium text-foreground">
                    {t(envLabels[environment] ?? environment)}
                  </p>
                  <p className={cn("text-xs", conf.color)}>
                    {envStatusLabels[status] ?? status}
                    {deployedVersion !== undefined && (
                      <span
                        className="ms-1 tabular-nums opacity-75"
                        data-testid={`env-badge-version-${environment}`}
                        title={t("agents.liveInVersion", "Live in {{environment}} at version {{version}}", {
                          environment: t(envLabels[environment] ?? environment),
                          version: deployedVersion,
                        })}
                      >
                        v{deployedVersion}
                      </span>
                    )}
                  </p>
                </div>
              </div>
              <button
                onClick={() => (isUp ? onUndeploy(environment) : onDeploy(environment))}
                disabled={isBusy || !!blockedTitle}
                title={blockedTitle}
                data-testid={`env-toggle-${environment}`}
                className={cn(
                  "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
                  isUp
                    ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                    : "bg-primary/10 text-primary hover:bg-primary/20",
                  (isBusy || blockedTitle) && "cursor-not-allowed opacity-50"
                )}
              >
                {isUp ? t("agents.undeploy") : t("agents.deploy")}
              </button>
            </div>
          );
        })}
      </div>
      {/* What deploying this version does to conversations on the agent's
          other deployed versions. Each panel hides itself when there are none. */}
      <div className="divide-y divide-border border-t border-border empty:hidden">
        {statuses.map(({ environment }) => (
          <DeploymentImpactPanel
            key={environment}
            agentId={agentId}
            version={version}
            environment={environment}
            environmentLabel={t(envLabels[environment] ?? environment)}
          />
        ))}
      </div>
    </section>
  );
}

/* ─── Add Workflow Panel ─── */
function AddWorkflowPanel({
  currentWorkflows,
  onAdd,
  onClose,
}: {
  currentWorkflows: string[];
  onAdd: (uri: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const { data: packages, isLoading } = useWorkflowDescriptors(100, 0, filter);

  const available = (packages ?? []).filter(
    (wf) => !currentWorkflows.includes(wf.resource)
  );

  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <div className="flex items-center justify-between border-b border-border p-5">
        <h3 className="text-lg font-semibold text-foreground">
          {t("agentDetail.selectWorkflow", "Select Workflow to Add")}
        </h3>
        <button
          onClick={onClose}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          {t("common.cancel")}
        </button>
      </div>
      <div className="p-5">
        <input
          type="text"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder={t("common.search")}
          className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      </div>
      <div className="max-h-64 divide-y divide-border overflow-y-auto">
        {isLoading && (
          <div className="flex items-center justify-center py-8">
            <RefreshCw className="h-5 w-5 animate-spin text-primary" />
          </div>
        )}
        {!isLoading && available.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">
            {t("common.noResults")}
          </p>
        )}
        {available.map((wf) => (
          <button
            key={wf.resource}
            onClick={() => onAdd(wf.resource)}
            className="flex w-full items-center justify-between px-5 py-3 text-start hover:bg-secondary/50 transition-colors"
          >
            <div>
              <p className="text-sm font-medium text-foreground">
                {wf.name || parseResourceUri(wf.resource).id}
              </p>
              <p className="text-xs text-muted-foreground line-clamp-1">
                {wf.description || t("agents.noDescription", "No description")}
              </p>
            </div>
            <Plus className="h-4 w-4 text-primary" />
          </button>
        ))}
      </div>
    </section>
  );
}

/* ─── JSON syntax highlighting helper ─── */
function syntaxHighlightJson(json: string): React.ReactNode {
  // Tokenize JSON string into colored spans
  const regex = /("(?:[^"\\]|\\.)*")\s*:|"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\btrue\b|\bfalse\b|\bnull\b/g;
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let matchIndex = 0;

  while ((match = regex.exec(json)) !== null) {
    // Push text before match
    if (match.index > lastIndex) {
      parts.push(json.slice(lastIndex, match.index));
    }

    const text = match[0];
    matchIndex++;

    if (text.endsWith(":")) {
      // JSON key
      const key = text.slice(0, -1);
      parts.push(
        <span key={`k${matchIndex}`} className="text-primary">{key}</span>,
        ":"
      );
    } else if (text.startsWith('"')) {
      // String value
      parts.push(
        <span key={`s${matchIndex}`} className="text-emerald-600 dark:text-emerald-400">{text}</span>
      );
    } else if (text === "true" || text === "false") {
      parts.push(
        <span key={`b${matchIndex}`} className="text-purple-600 dark:text-purple-400">{text}</span>
      );
    } else if (text === "null") {
      parts.push(
        <span key={`n${matchIndex}`} className="text-muted-foreground italic">{text}</span>
      );
    } else {
      // Number
      parts.push(
        <span key={`d${matchIndex}`} className="text-sky-600 dark:text-sky-400">{text}</span>
      );
    }

    lastIndex = match.index + text.length;
  }

  // Push remaining text
  if (lastIndex < json.length) {
    parts.push(json.slice(lastIndex));
  }

  return parts;
}

/* ─── Raw Config Section ─── */
function RawConfigSection({ agent }: { agent: Agent }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between p-5 text-start"
        aria-expanded={expanded}
        aria-controls="raw-config-content"
      >
        <div className="flex items-center gap-2">
          <Settings className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
          <h2 className="text-lg font-semibold text-foreground">
            {t("agentDetail.rawConfig", "Raw Configuration")}
          </h2>
        </div>
        {expanded ? (
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 text-muted-foreground" />
        )}
      </button>
      {expanded && (
        <div className="border-t border-border p-5" id="raw-config-content">
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm font-mono leading-relaxed">
            {syntaxHighlightJson(JSON.stringify(agent, null, 2))}
          </pre>
        </div>
      )}
    </section>
  );
}

/* ─── A2A Protocol Section ─── */
function A2ASection({
  agent,
  agentId,
  onChange,
  disabled,
  modified,
}: {
  /** The page's draft of the agent document. */
  agent: Agent;
  agentId: string;
  /** Records an edit in the draft — nothing is saved until the page's Save. */
  onChange: AgentEdit;
  disabled: boolean;
  modified: boolean;
}) {
  const { t } = useTranslation();
  const [skillInput, setSkillInput] = useState("");
  const [showCard, setShowCard] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

  const isEnabled = agent.a2aEnabled ?? false;

  function handleToggleA2A() {
    onChange({ ...agent, a2aEnabled: !isEnabled });
  }

  function handleAddSkill() {
    const trimmed = skillInput.trim();
    if (!trimmed) return;
    const current = agent.a2aSkills ?? [];
    if (current.includes(trimmed)) return;
    onChange({ ...agent, a2aSkills: [...current, trimmed] });
    setSkillInput("");
  }

  function handleRemoveSkill(idx: number) {
    const updated = (agent.a2aSkills ?? []).filter((_, i) => i !== idx);
    onChange({ ...agent, a2aSkills: updated });
  }

  function copyToClipboard(text: string, label: string) {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(label);
      setTimeout(() => setCopied(null), 2000);
    });
  }

  const baseUrl = window.location.origin;
  const cardUrl = `${baseUrl}/a2a/agents/${agentId}/agent.json`;
  const rpcUrl = `${baseUrl}/a2a/agents/${agentId}`;

  // Build a preview of the Agent Card (mirrors AgentCardService.java)
  const agentCard = {
    name: `EDDI Agent ${agentId}`,
    description: agent.description || "EDDI conversational AI agent",
    url: rpcUrl,
    provider: "EDDI",
    version: "6.0.0",
    capabilities: { streaming: false, pushNotifications: false, stateTransitionHistory: true },
    skills: (agent.a2aSkills && agent.a2aSkills.length > 0)
      ? agent.a2aSkills.map((s) => ({
          id: s.toLowerCase().replace(/ /g, "-"),
          name: s,
          description: `Skill: ${s}`,
        }))
      : [{ id: "chat", name: "Conversational AI", description: "General conversational AI agent powered by EDDI" }],
  };

  const [isOpen, setIsOpen] = useState(false);

  return (
    <section className="rounded-xl border bg-card shadow-sm" data-testid="a2a-section">
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className={cn(
          "flex w-full items-center gap-2 p-5 text-start",
          isOpen && "border-b border-border"
        )}
      >
        {isOpen ? (
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 text-muted-foreground" />
        )}
        <Handshake className="h-5 w-5 text-primary" />
        <h2 className="text-lg font-semibold text-foreground">
          {t("agentDetail.a2aSection", "Agent-to-Agent (A2A)")}
        </h2>
        {modified && <ModifiedPill />}
        {isEnabled && (
          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-600 dark:text-emerald-400">
            <Link2 className="h-3 w-3" />
            {t("agentDetail.a2aEnabled", "Enabled")}
          </span>
        )}
      </button>

      {isOpen && <div className="p-5">
        {!isEnabled ? (
          /* ── Disabled state: CTA ── */
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <Handshake className="h-10 w-10 text-muted-foreground/50" />
            <p className="mt-3 text-sm text-muted-foreground max-w-md">
              {t(
                "agentDetail.a2aEnabledDesc",
                "Make this agent discoverable by other agents via the A2A protocol. Other EDDI instances can find and call this agent as a tool."
              )}
            </p>
            <button
              onClick={handleToggleA2A}
              disabled={disabled}
              className="mt-4 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:opacity-50"
              data-testid="enable-a2a-btn"
            >
              <Link2 className="h-4 w-4" />
              {t("agentDetail.a2aEnable", "Enable A2A")}
            </button>
          </div>
        ) : (
          /* ── Enabled state: full editor ── */
          <div className="space-y-5">
            {/* Description */}
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">
                {t("agentDetail.a2aDescription", "Agent Description")}
              </label>
              <input
                type="text"
                value={agent.description ?? ""}
                onChange={(e) => onChange({ ...agent, description: e.target.value })}
                placeholder={t("agentDetail.a2aDescPlaceholder", "What does this agent do?")}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
                data-testid="a2a-description"
              />
              <p className="mt-1 text-xs text-muted-foreground">
                {t("agentDetail.a2aDescHint", "Shown in the Agent Card — helps other agents understand what this agent does")}
              </p>
            </div>

            {/* Skills */}
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">
                {t("agentDetail.a2aSkills", "A2A Skills")}
              </label>
              <div className="flex flex-wrap gap-1.5 mb-2">
                {(agent.a2aSkills ?? []).map((skill, i) => (
                  <span
                    key={i}
                    className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
                  >
                    {skill}
                    <button
                      type="button"
                      onClick={() => handleRemoveSkill(i)}
                      className="rounded p-0.5 hover:bg-primary/20 transition-colors"
                      aria-label={`Remove ${skill}`}
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </span>
                ))}
                {(agent.a2aSkills ?? []).length === 0 && (
                  <span className="text-xs text-muted-foreground italic">
                    {t("agentDetail.a2aNoSkills", "No skills — a default 'chat' skill will be used")}
                  </span>
                )}
              </div>
              <div className="flex gap-1.5">
                <input
                  type="text"
                  value={skillInput}
                  onChange={(e) => setSkillInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      handleAddSkill();
                    }
                  }}
                  placeholder={t("agentDetail.a2aSkillPlaceholder", "e.g. translation, code-review")}
                  className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  data-testid="a2a-skill-input"
                />
                <button
                  type="button"
                  onClick={handleAddSkill}
                  className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary"
                >
                  <Plus className="h-3 w-3" />
                </button>
              </div>
            </div>

            {/* Endpoints */}
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">
                {t("agentDetail.a2aEndpoints", "Endpoints")}
              </label>
              <div className="space-y-1.5">
                {[
                  { method: "GET", url: cardUrl, label: "card" },
                  { method: "POST", url: rpcUrl, label: "rpc" },
                ].map(({ method, url, label }) => (
                  <div key={label} className="flex items-center gap-2 rounded-lg bg-secondary/50 px-3 py-2">
                    <span className={cn(
                      "rounded px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider",
                      method === "GET" ? "bg-sky-500/10 text-sky-600 dark:text-sky-400" : "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                    )}>
                      {method}
                    </span>
                    <code className="flex-1 truncate font-mono text-xs text-foreground" dir="ltr">
                      {url}
                    </code>
                    <button
                      type="button"
                      onClick={() => copyToClipboard(url, label)}
                      className="rounded p-1 text-muted-foreground hover:text-foreground transition-colors"
                      aria-label="Copy URL"
                      data-testid={`copy-url-${label}`}
                    >
                      {copied === label ? (
                        <span className="text-[10px] font-medium text-emerald-500">✓</span>
                      ) : (
                        <Copy className="h-3.5 w-3.5" />
                      )}
                    </button>
                  </div>
                ))}
              </div>
            </div>

            {/* Agent Card Preview (collapsible) */}
            <div>
              <button
                type="button"
                onClick={() => setShowCard(!showCard)}
                className="flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
                data-testid="a2a-card-toggle"
              >
                {showCard ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                {t("agentDetail.agentCardPreview", "Agent Card Preview")}
              </button>
              {showCard && (
                <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-secondary p-3 text-xs text-foreground font-mono">
                  {JSON.stringify(agentCard, null, 2)}
                </pre>
              )}
            </div>
          </div>
        )}
      </div>}
    </section>
  );
}
