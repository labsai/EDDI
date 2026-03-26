import { useState, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
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
} from "lucide-react";
import { cn } from "@/lib/utils";
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
import { useExportAgent } from "@/hooks/use-backup";
import { useWorkflowDescriptors, useUpdateAgentWorkflows } from "@/hooks/use-workflows";
import { parseResourceUri, type EnvironmentStatus } from "@/lib/api/agents";

/* ─── Status config for environment badges ─── */
const statusConfig = {
  READY: { icon: Rocket, label: "Deployed", color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, label: "Deploying...", color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, label: "Error", color: "text-destructive", bg: "bg-destructive/10" },
  NOT_FOUND: { icon: Square, label: "Not deployed", color: "text-muted-foreground", bg: "bg-muted" },
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

  const [version, setVersion] = useState(1);
  const [showAddWorkflow, setShowAddWorkflow] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  const { data: agent, isLoading, isError, refetch } = useAgent(id!, version);
  const { data: deployment } = useDeploymentStatus(id!, version);
  const { data: envStatuses } = useDeploymentStatuses(id!, version);
  const { data: versions } = useAgentVersions(id!);

  const deployMutation = useDeployAgent();
  const undeployMutation = useUndeployAgent();
  const deleteMutation = useDeleteAgent();
  const duplicateMutation = useDuplicateAgent();
  const updateWorkflowsMutation = useUpdateAgentWorkflows();
  const exportMutation = useExportAgent();

  const status = deployment?.status ?? "NOT_FOUND";
  const config = statusConfig[status];
  const StatusIcon = config.icon;
  const isDeployed = status === "READY";
  const isBusy = deployMutation.isPending || undeployMutation.isPending || status === "IN_PROGRESS";

  // Clear save message after 3s
  useEffect(() => {
    if (saveMessage) {
      const timer = setTimeout(() => setSaveMessage(null), 3000);
      return () => clearTimeout(timer);
    }
  }, [saveMessage]);

  function handleDeploy() {
    deployMutation.mutate({ agentId: id!, version });
  }

  function handleUndeploy() {
    undeployMutation.mutate({ agentId: id!, version });
  }

  async function handleDelete() {
    if (window.confirm(t("agents.confirmDelete"))) {
      await deleteMutation.mutateAsync({ id: id!, version });
      navigate("/manage/agents");
    }
  }

  async function handleDuplicate() {
    try {
      const result = await duplicateMutation.mutateAsync({
        id: id!,
        version,
        deepCopy: true,
      });
      const { id: newId } = parseResourceUri(result.location);
      navigate(`/manage/agentview/${newId}`);
    } catch {
      // Error handled by mutation state
    }
  }

  function handleRemoveWorkflow(packageUri: string) {
    if (!agent?.workflows) return;
    const updated = agent.workflows.filter((p) => p !== packageUri);
    updateWorkflowsMutation.mutate({ agentId: id!, version, workflows: updated });
  }

  function handleAddWorkflow(packageUri: string) {
    const current = agent?.workflows ?? [];
    if (current.includes(packageUri)) return;
    updateWorkflowsMutation.mutate({
      agentId: id!,
      version,
      workflows: [...current, packageUri],
    });
    setShowAddWorkflow(false);
  }

  const handleVersionChange = useCallback((v: number) => {
    setVersion(v);
  }, []);

  if (isLoading) {
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
                {t("agentDetail.title", "Agent Detail")}
              </h1>
              <p className="font-mono text-sm text-muted-foreground">ID: {id}</p>
            </div>
          </div>
          {/* Version picker */}
          {versions && versions.length > 0 && (
            <VersionSelect
              versions={versions}
              current={version}
              onChange={handleVersionChange}
            />
          )}
        </div>

        {/* Actions */}
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
            {config.label}
          </div>

          {/* Deploy/Undeploy */}
          <button
            onClick={isDeployed ? handleUndeploy : handleDeploy}
            disabled={isBusy}
            className={cn(
              "rounded-lg px-4 py-2 text-sm font-medium transition-colors",
              isDeployed
                ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                : "bg-primary px-4 py-2 text-primary-foreground hover:bg-primary/90",
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

          {/* Chat — only when deployed */}
          {isDeployed && (
            <Link
              to={`/manage/chat?agentId=${id}`}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
              data-testid="chat-btn"
            >
              <MessageSquare className="h-4 w-4" />
              {t("agents.chat", "Chat")}
            </Link>
          )}

          {/* Duplicate */}
          <button
            onClick={handleDuplicate}
            disabled={duplicateMutation.isPending}
            className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
            data-testid="duplicate-agent-btn"
          >
            <Copy className="h-4 w-4 inline-block me-1.5" />
            {duplicateMutation.isPending
              ? t("agentDetail.duplicating")
              : t("agentDetail.duplicate")}
          </button>

          {/* Export */}
          <button
            onClick={() => exportMutation.mutate({ agentId: id!, version })}
            disabled={exportMutation.isPending}
            className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
            data-testid="export-agent-btn"
          >
            <Download className="h-4 w-4 inline-block me-1.5" />
            {exportMutation.isPending
              ? t("agents.exporting", "Exporting...")
              : t("agents.export", "Export")}
          </button>

          {/* Delete */}
          <button
            onClick={handleDelete}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
            data-testid="delete-agent-btn"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

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
        >
          {saveMessage.text}
        </div>
      )}

      {/* Environment Status Badges */}
      {envStatuses && envStatuses.length > 0 && (
        <EnvironmentBadges
          statuses={envStatuses}
          onDeploy={(env) => deployMutation.mutate({ environment: env, agentId: id!, version })}
          onUndeploy={(env) => undeployMutation.mutate({ environment: env, agentId: id!, version })}
          isBusy={isBusy}
        />
      )}

      {/* A2A Protocol Section */}
      <A2ASection
        agent={agent}
        agentId={id!}
        version={version}
      />

      {/* Workflows section */}
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

        {/* Workflow list */}
        <div className="divide-y divide-border">
          {(!agent.workflows || agent.workflows.length === 0) && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <Workflow className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">{t("agentDetail.noWorkflows", "No workflows added yet")}</p>
            </div>
          )}

          {agent.workflows?.map((pkgUri) => {
            const { id: pkgId, version: pkgVersion } = parseResourceUri(pkgUri);
            return (
              <div
                key={pkgUri}
                className="flex items-center justify-between px-5 py-3 hover:bg-secondary/50 transition-colors"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <Settings className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <Link
                      to={`/manage/workflowview/${pkgId}`}
                      className="text-sm font-medium text-foreground hover:text-primary truncate block transition-colors"
                    >
                      {pkgId}
                      <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                    </Link>
                    <p className="text-xs text-muted-foreground">
                      v{pkgVersion}
                    </p>
                  </div>
                </div>
                <button
                  onClick={() => handleRemoveWorkflow(pkgUri)}
                  disabled={updateWorkflowsMutation.isPending}
                  className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors disabled:opacity-50"
                  title={t("common.delete")}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
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

      {/* Raw config (collapsible) */}
      <RawConfigSection agent={agent} />
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

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString();
}

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
  return (
    <section className="rounded-xl border bg-card shadow-sm" data-testid="env-badges">
      <div className="flex items-center gap-2 border-b border-border px-5 py-3">
        <Server className="h-5 w-5 text-primary" />
        <h2 className="text-sm font-semibold text-foreground">
          {t("agentDetail.environments", "Environments")}
        </h2>
      </div>
      <div className="grid grid-cols-1 gap-0 divide-y divide-border sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {statuses.map(({ environment, status }) => {
          const conf = statusConfig[status];
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
                    {conf.label}
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
    (pkg) => !currentWorkflows.includes(pkg.resource)
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
        {available.map((pkg) => (
          <button
            key={pkg.resource}
            onClick={() => onAdd(pkg.resource)}
            className="flex w-full items-center justify-between px-5 py-3 text-start hover:bg-secondary/50 transition-colors"
          >
            <div>
              <p className="text-sm font-medium text-foreground">
                {pkg.name || parseResourceUri(pkg.resource).id}
              </p>
              <p className="text-xs text-muted-foreground line-clamp-1">
                {pkg.description || "No description"}
              </p>
            </div>
            <Plus className="h-4 w-4 text-primary" />
          </button>
        ))}
      </div>
    </section>
  );
}

/* ─── Raw Config Section ─── */
function RawConfigSection({ agent }: { agent: { workflows?: string[]; channels?: string[] } }) {
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
            {t("agentDetail.rawConfig", "Raw Configuration")}
          </h2>
        </div>
        <span className="text-sm text-muted-foreground">
          {expanded ? "▲" : "▼"}
        </span>
      </button>
      {expanded && (
        <div className="border-t border-border p-5">
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm text-foreground">
            {JSON.stringify(agent, null, 2)}
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
  agent: { workflows?: string[]; channels?: string[]; a2aEnabled?: boolean; description?: string; a2aSkills?: string[] };
  agentId: string;
  version: number;
}) {
  const { t } = useTranslation();
  const updateAgent = useUpdateAgent();
  const [skillInput, setSkillInput] = useState("");
  const [localDesc, setLocalDesc] = useState(agent.description ?? "");
  const [showCard, setShowCard] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

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

  return (
    <section className="rounded-xl border bg-card shadow-sm" data-testid="a2a-section">
      <div className="flex items-center justify-between border-b border-border p-5">
        <div className="flex items-center gap-2">
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
        </div>
        {isEnabled && (
          <button
            onClick={handleToggleA2A}
            disabled={updateAgent.isPending}
            className="rounded-lg bg-destructive/10 px-3 py-1.5 text-xs font-medium text-destructive hover:bg-destructive/20 transition-colors disabled:opacity-50"
          >
            {t("agentDetail.a2aDisable", "Disable A2A")}
          </button>
        )}
      </div>

      <div className="p-5">
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
      </div>
    </section>
  );
}
