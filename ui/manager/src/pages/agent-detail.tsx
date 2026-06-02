import { useState, useEffect, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Bot,
  Workflow,
  Rocket,
  Square,
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
} from "lucide-react";
import { cn, formatRelativeTime } from "@/lib/utils";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { AlertDialog } from "@/components/ui/alert-dialog";
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
import { ExportAgentDialog } from "@/components/agents/export-agent-dialog";
import { useWorkflowDescriptors, useUpdateAgentWorkflows } from "@/hooks/use-workflows";
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
  ChannelsSection,
} from "@/components/editors/agent-config-sections";

/* ─── Status icons (labels resolved via i18n in component) ─── */
const statusIcons = {
  READY: { icon: Rocket, color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10" },
  NOT_FOUND: { icon: Square, color: "text-muted-foreground", bg: "bg-muted" },
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
  const [saveMessage, setSaveMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showExportDialog, setShowExportDialog] = useState(false);

  // Reset version when navigating to a different agent (React reuses
  // the component, so useState values persist across route param changes).
  useEffect(() => {
    setVersion(undefined);
  }, [id]);

  const { data: versions } = useAgentVersions(id!);

  // Default to latest version once loaded
  const resolvedVersion = version ?? versions?.[0]?.version ?? 1;

  const { data: agent, isLoading, isError, refetch } = useAgent(id!, resolvedVersion);
  const { data: deployment } = useDeploymentStatus(id!, resolvedVersion);
  const { data: envStatuses } = useDeploymentStatuses(id!, resolvedVersion);

  const deployMutation = useDeployAgent();
  const undeployMutation = useUndeployAgent();
  const deleteMutation = useDeleteAgent();
  const duplicateMutation = useDuplicateAgent();
  const updateWorkflowsMutation = useUpdateAgentWorkflows();
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

  // Version staleness detection for workflow references
  const workflowUris = useMemo(
    () => (agent?.workflows ?? []).filter((u) => u.includes("://")),
    [agent?.workflows]
  );
  const { data: latestVersions } = useLatestVersions(workflowUris);

  // Clear save message after 3s
  useEffect(() => {
    if (saveMessage) {
      const timer = setTimeout(() => setSaveMessage(null), 3000);
      return () => clearTimeout(timer);
    }
  }, [saveMessage]);

  function handleDeploy() {
    deployMutation.mutate(
      { agentId: id!, version: resolvedVersion },
      {
        onSuccess: () => toast.success(t("agents.deploySuccess", "Deployment started")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function handleUndeploy() {
    undeployMutation.mutate(
      { agentId: id!, version: resolvedVersion },
      {
        onSuccess: () => toast.success(t("agents.undeploySuccess", "Agent undeployed")),
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
    if (!agent?.workflows) return;
    const updated = agent.workflows.filter((p) => p !== packageUri);
    updateWorkflowsMutation.mutate(
      { agentId: id!, version: resolvedVersion, workflows: updated },
      {
        onSuccess: () => toast.success(t("agentDetail.workflowRemoved", "Workflow removed")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function handleAddWorkflow(packageUri: string) {
    const current = agent?.workflows ?? [];
    if (current.includes(packageUri)) return;
    updateWorkflowsMutation.mutate(
      {
        agentId: id!,
        version: resolvedVersion,
        workflows: [...current, packageUri],
      },
      {
        onSuccess: () => toast.success(t("agentDetail.workflowAdded", "Workflow added")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
    setShowAddWorkflow(false);
  }

  function handleUpdateWorkflowVersion(oldUri: string, newVersion: number) {
    if (!agent?.workflows) return;
    const updated = agent.workflows.map((u) => {
      if (u === oldUri) {
        return u.replace(/([?&]version=)\d+/, `$1${newVersion}`);
      }
      return u;
    });
    updateWorkflowsMutation.mutate(
      { agentId: id!, version: resolvedVersion, workflows: updated },
      {
        onSuccess: () => toast.success(t("agentDetail.workflowUpdated", "Workflow updated to latest version")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  const handleVersionChange = useCallback((v: number) => {
    setVersion(v);
  }, []);

  if (isLoading && !agent) {
    return (
      <div className="flex items-center justify-center py-20">
        <RefreshCw className="h-8 w-8 animate-spin text-primary" />
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
              <p className="font-mono text-sm text-muted-foreground">
                {id}
                <span className="ms-2 inline-flex items-center rounded-md bg-primary/10 px-1.5 py-0.5 text-xs font-semibold text-primary">
                  v{resolvedVersion}
                </span>
              </p>
            </div>
          </div>
          {/* Version picker */}
          {versions && versions.length > 0 && (
            <VersionSelect
              versions={versions}
              current={resolvedVersion}
              onChange={handleVersionChange}
            />
          )}
        </div>

        {/* Actions — grouped into primary (deploy/chat) and secondary (tools) */}
        <div className="flex flex-col gap-2 items-end">
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
              onClick={isDeployed ? handleUndeploy : handleDeploy}
              disabled={isBusy}
              className={cn(
                "rounded-lg px-4 py-2 text-sm font-medium transition-colors",
                isDeployed
                  ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                  : "bg-primary text-primary-foreground hover:bg-primary/90",
                isBusy && "cursor-not-allowed opacity-50"
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
            {!isDeployed && !isBusy && (
              <button
                onClick={async () => {
                  const drawerStore = useChatDrawerStore.getState();
                  const chatStore = useChatStore.getState();
                  drawerStore.open(id!, id!);
                  drawerStore.setStep("deploying");
                  try {
                    await deployAgent("production", id!, resolvedVersion);
                    for (let i = 0; i < 15; i++) {
                      await new Promise(r => setTimeout(r, 2000));
                      const s = await getDeploymentStatus("production", id!, resolvedVersion);
                      if (s.status === "READY") break;
                      if (s.status === "ERROR") throw new Error("Deploy failed");
                    }
                    drawerStore.setStep("starting");
                    chatStore.clearMessages();
                    chatStore.setSelectedAgent(id!, id!);
                    await startConversationMutation.mutateAsync({ agentId: id! });
                    drawerStore.setStep("ready");
                    queryClient.invalidateQueries({ queryKey: ["agents"] });
                    queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
                  } catch (err) {
                    drawerStore.setStep("error", getErrorMessage(err));
                  }
                }}
                disabled={startConversationMutation.isPending}
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
                  drawerStore.open(id!, id!);
                  if (isDeployed) {
                    drawerStore.setStep("starting");
                    chatStore.clearMessages();
                    chatStore.setSelectedAgent(id!, id!);
                    try {
                      await startConversationMutation.mutateAsync({ agentId: id! });
                      drawerStore.setStep("ready");
                    } catch (err) {
                      drawerStore.setStep("error", getErrorMessage(err));
                    }
                  }
                }}
                disabled={startConversationMutation.isPending}
                className={cn(
                  "inline-flex items-center gap-1.5 bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400 disabled:opacity-50 disabled:cursor-not-allowed",
                  isDeployed ? "rounded-s-lg" : "rounded-lg"
                )}
                data-testid="chat-btn"
                aria-label={t("agents.chat", "Chat")}
              >
                <MessageSquare className="h-4 w-4" aria-hidden="true" />
                {t("agents.chat", "Chat")}
              </button>
              {isDeployed && (
                <a
                  href={`/chat/production/${id}`}
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
            <button
              onClick={() => setShowDeleteDialog(true)}
              className="inline-flex items-center gap-1.5 rounded-lg bg-destructive/10 px-3 py-1.5 text-xs font-medium text-destructive hover:bg-destructive/20 transition-colors"
              data-testid="delete-agent-btn"
              aria-label={t("agents.deleteAgent", "Delete agent")}
            >
              <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>

      {/* Non-latest version warning */}
      {isNotLatest && (
        <div className="flex items-center gap-3 rounded-lg border border-amber-400/30 bg-amber-50 px-4 py-3 dark:bg-amber-900/15 dark:border-amber-700/30" data-testid="non-latest-warning">
          <Info className="h-5 w-5 text-amber-600 dark:text-amber-400 shrink-0" />
          <p className="flex-1 text-sm text-amber-800 dark:text-amber-300">
            {t("agentDetail.viewingOldVersion", "You are viewing version {{current}}. Latest is version {{latest}}.", { current: resolvedVersion, latest: latestVersion })}
          </p>
          <button
            onClick={() => handleVersionChange(latestVersion)}
            className="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-700 transition-colors shrink-0"
          >
            {t("agentDetail.switchToLatest", "Switch to latest")}
          </button>
        </div>
      )}

      {/* Save feedback */}
      {saveMessage && (
        <div
          className={cn(
            "rounded-lg px-4 py-2 text-sm font-medium transition-all",
            saveMessage.type === "success"
              ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
              : "bg-destructive/10 text-destructive"
          )}
          data-testid="save-feedback"
          role="status"
          aria-live="polite"
        >
          {saveMessage.text}
        </div>
      )}

      {/* Environment Status Badges */}
      {envStatuses && envStatuses.length > 0 && (
        <EnvironmentBadges
          statuses={envStatuses}
          onDeploy={(env) => deployMutation.mutate(
            { environment: env, agentId: id!, version: resolvedVersion },
            { onError: (err) => toast.error(getErrorMessage(err)) }
          )}
          onUndeploy={(env) => undeployMutation.mutate(
            { environment: env, agentId: id!, version: resolvedVersion },
            { onError: (err) => toast.error(getErrorMessage(err)) }
          )}
          isBusy={isBusy}
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
              {agent.workflows?.length ?? 0}
            </span>
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
          {(!agent.workflows || agent.workflows.length === 0) && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <Workflow className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">{t("agentDetail.noWorkflows", "No workflows added yet")}</p>
            </div>
          )}

          {agent.workflows?.map((wfUri) => {
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
                      disabled={updateWorkflowsMutation.isPending}
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
                    disabled={updateWorkflowsMutation.isPending}
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
          currentWorkflows={agent.workflows ?? []}
          onAdd={handleAddWorkflow}
          onClose={() => setShowAddWorkflow(false)}
        />
      )}

      {/* A2A Protocol (collapsible, hidden by default) */}
      <A2ASection
        agent={agent}
        agentId={id!}
        version={resolvedVersion}
      />

      {/* Security & Identity */}
      <SecurityIdentitySection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* Capabilities */}
      <CapabilitiesSection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* User Memory */}
      <UserMemorySection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* Memory Policy */}
      <MemoryPolicySection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* Session Management */}
      <SessionManagementSection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* Channel Connectors */}
      <ChannelsSection agent={agent} agentId={id!} version={resolvedVersion} />

      {/* Raw config (collapsible) */}
      <RawConfigSection agent={agent} />

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
}: {
  versions: { version: number; lastModifiedOn: number }[];
  current: number;
  onChange: (v: number) => void;
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
      className="rounded-md border border-input bg-background px-2.5 py-1 text-xs font-medium text-foreground shadow-sm transition-colors hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1"
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
  statuses,
  onDeploy,
  onUndeploy,
  isBusy,
}: {
  statuses: EnvironmentStatus[];
  onDeploy: (env: string) => void;
  onUndeploy: (env: string) => void;
  isBusy: boolean;
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
        {statuses.map(({ environment, status }) => {
          const conf = statusIcons[status];
          const Icon = conf.icon;
          const isUp = status === "READY";
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
                  </p>
                </div>
              </div>
              <button
                onClick={() => (isUp ? onUndeploy(environment) : onDeploy(environment))}
                disabled={isBusy}
                className={cn(
                  "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
                  isUp
                    ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                    : "bg-primary/10 text-primary hover:bg-primary/20",
                  isBusy && "cursor-not-allowed opacity-50"
                )}
              >
                {isUp ? t("agents.undeploy") : t("agents.deploy")}
              </button>
            </div>
          );
        })}
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
  version,
}: {
  agent: Agent;
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();
  const [skillInput, setSkillInput] = useState("");
  const [localDesc, setLocalDesc] = useState(agent.description ?? "");
  const [showCard, setShowCard] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

  // Keep localDesc synced when agent data changes (version switch, refetch)
  useEffect(() => setLocalDesc(agent.description ?? ""), [agent.description]);

  const isEnabled = agent.a2aEnabled ?? false;

  function handleToggleA2A() {
    updateAgent.mutate({
      id: agentId,
      version,
      agent: { ...agent, a2aEnabled: !isEnabled },
    });
  }

  function handleDescriptionSave() {
    if (localDesc !== (agent.description ?? "")) {
      updateAgent.mutate({
        id: agentId,
        version,
        agent: { ...agent, description: localDesc },
      });
    }
  }

  function handleAddSkill() {
    const trimmed = skillInput.trim();
    if (!trimmed) return;
    const current = agent.a2aSkills ?? [];
    if (current.includes(trimmed)) return;
    updateAgent.mutate({
      id: agentId,
      version,
      agent: { ...agent, a2aSkills: [...current, trimmed] },
    });
    setSkillInput("");
  }

  function handleRemoveSkill(idx: number) {
    const updated = (agent.a2aSkills ?? []).filter((_, i) => i !== idx);
    updateAgent.mutate({
      id: agentId,
      version,
      agent: { ...agent, a2aSkills: updated },
    });
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
              disabled={updateAgent.isPending}
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
                value={localDesc}
                onChange={(e) => setLocalDesc(e.target.value)}
                onBlur={handleDescriptionSave}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    handleDescriptionSave();
                  }
                }}
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
