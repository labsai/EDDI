import { useState, useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Bot,
  Package,
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
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useBot,
  useDeploymentStatus,
  useDeploymentStatuses,
  useDeployBot,
  useUndeployBot,
  useDeleteBot,
  useDuplicateBot,
  useBotVersions,
} from "@/hooks/use-bots";
import { useExportBot } from "@/hooks/use-backup";
import { usePackageDescriptors, useUpdateBotPackages } from "@/hooks/use-packages";
import { parseResourceUri, type EnvironmentStatus } from "@/lib/api/bots";

/* ─── Status config for environment badges ─── */
const statusConfig = {
  READY: { icon: Rocket, label: "Deployed", color: "text-emerald-500", bg: "bg-emerald-500/10" },
  IN_PROGRESS: { icon: Clock, label: "Deploying...", color: "text-amber-500", bg: "bg-amber-500/10" },
  ERROR: { icon: AlertTriangle, label: "Error", color: "text-destructive", bg: "bg-destructive/10" },
  NOT_FOUND: { icon: Square, label: "Not deployed", color: "text-muted-foreground", bg: "bg-muted" },
};

const envLabels: Record<string, string> = {
  unrestricted: "botDetail.envUnrestricted",
  restricted: "botDetail.envRestricted",
  test: "botDetail.envTest",
};

/* ─── Main page ─── */
export function BotDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [version, setVersion] = useState(1);
  const [showAddPackage, setShowAddPackage] = useState(false);
  const [saveMessage, setSaveMessage] = useState<{ type: "success" | "error"; text: string } | null>(null);

  const { data: bot, isLoading, isError, refetch } = useBot(id!, version);
  const { data: deployment } = useDeploymentStatus(id!, version);
  const { data: envStatuses } = useDeploymentStatuses(id!, version);
  const { data: versions } = useBotVersions(id!);

  const deployMutation = useDeployBot();
  const undeployMutation = useUndeployBot();
  const deleteMutation = useDeleteBot();
  const duplicateMutation = useDuplicateBot();
  const updatePackagesMutation = useUpdateBotPackages();
  const exportMutation = useExportBot();

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
    deployMutation.mutate({ botId: id!, version });
  }

  function handleUndeploy() {
    undeployMutation.mutate({ botId: id!, version });
  }

  async function handleDelete() {
    if (window.confirm(t("bots.confirmDelete"))) {
      await deleteMutation.mutateAsync({ id: id!, version });
      navigate("/manage/bots");
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
      navigate(`/manage/botview/${newId}`);
    } catch {
      // Error handled by mutation state
    }
  }

  function handleRemovePackage(packageUri: string) {
    if (!bot?.packages) return;
    const updated = bot.packages.filter((p) => p !== packageUri);
    updatePackagesMutation.mutate({ botId: id!, version, packages: updated });
  }

  function handleAddPackage(packageUri: string) {
    const current = bot?.packages ?? [];
    if (current.includes(packageUri)) return;
    updatePackagesMutation.mutate({
      botId: id!,
      version,
      packages: [...current, packageUri],
    });
    setShowAddPackage(false);
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

  if (isError || !bot) {
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
                {t("botDetail.title", "Bot Detail")}
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
                ? t("bots.undeploy")
                : t("bots.deploy")}
          </button>

          {/* Chat — only when deployed */}
          {isDeployed && (
            <Link
              to={`/manage/chat?botId=${id}`}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500/10 px-4 py-2 text-sm font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
              data-testid="chat-btn"
            >
              <MessageSquare className="h-4 w-4" />
              {t("bots.chat", "Chat")}
            </Link>
          )}

          {/* Duplicate */}
          <button
            onClick={handleDuplicate}
            disabled={duplicateMutation.isPending}
            className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
            data-testid="duplicate-bot-btn"
          >
            <Copy className="h-4 w-4 inline-block me-1.5" />
            {duplicateMutation.isPending
              ? t("botDetail.duplicating")
              : t("botDetail.duplicate")}
          </button>

          {/* Export */}
          <button
            onClick={() => exportMutation.mutate({ botId: id!, version })}
            disabled={exportMutation.isPending}
            className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground hover:bg-secondary transition-colors disabled:opacity-50"
            data-testid="export-bot-btn"
          >
            <Download className="h-4 w-4 inline-block me-1.5" />
            {exportMutation.isPending
              ? t("bots.exporting", "Exporting...")
              : t("bots.export", "Export")}
          </button>

          {/* Delete */}
          <button
            onClick={handleDelete}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
            data-testid="delete-bot-btn"
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
          onDeploy={(env) => deployMutation.mutate({ environment: env, botId: id!, version })}
          onUndeploy={(env) => undeployMutation.mutate({ environment: env, botId: id!, version })}
          isBusy={isBusy}
        />
      )}

      {/* Packages section */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Package className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("botDetail.packages", "Packages")}
            </h2>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {bot.packages?.length ?? 0}
            </span>
          </div>
          <button
            onClick={() => setShowAddPackage(!showAddPackage)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/20 transition-colors"
            data-testid="add-package-btn"
          >
            <Plus className="h-4 w-4" />
            {t("botDetail.addPackage", "Add Package")}
          </button>
        </div>

        {/* Package list */}
        <div className="divide-y divide-border">
          {(!bot.packages || bot.packages.length === 0) && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <Package className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">{t("botDetail.noPackages", "No packages added yet")}</p>
            </div>
          )}

          {bot.packages?.map((pkgUri) => {
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
                      to={`/manage/packageview/${pkgId}`}
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
                  onClick={() => handleRemovePackage(pkgUri)}
                  disabled={updatePackagesMutation.isPending}
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
      {showAddPackage && (
        <AddPackagePanel
          currentPackages={bot.packages ?? []}
          onAdd={handleAddPackage}
          onClose={() => setShowAddPackage(false)}
        />
      )}

      {/* Raw config (collapsible) */}
      <RawConfigSection bot={bot} />
    </div>
  );
}

/* ─── Sub-components ─── */

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/manage/bots"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {t("botDetail.backToBots", "Back to Bots")}
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
          {t("botDetail.environments", "Environments")}
        </h2>
      </div>
      <div className="grid grid-cols-1 gap-0 divide-y divide-border sm:grid-cols-3 sm:divide-x sm:divide-y-0">
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
                {isUp ? t("bots.undeploy") : t("bots.deploy")}
              </button>
            </div>
          );
        })}
      </div>
    </section>
  );
}

/* ─── Add Package Panel ─── */
function AddPackagePanel({
  currentPackages,
  onAdd,
  onClose,
}: {
  currentPackages: string[];
  onAdd: (uri: string) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const { data: packages, isLoading } = usePackageDescriptors(100, 0, filter);

  const available = (packages ?? []).filter(
    (pkg) => !currentPackages.includes(pkg.resource)
  );

  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <div className="flex items-center justify-between border-b border-border p-5">
        <h3 className="text-lg font-semibold text-foreground">
          {t("botDetail.selectPackage", "Select Package to Add")}
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
function RawConfigSection({ bot }: { bot: { packages?: string[]; channels?: string[] } }) {
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
            {t("botDetail.rawConfig", "Raw Configuration")}
          </h2>
        </div>
        <span className="text-sm text-muted-foreground">
          {expanded ? "▲" : "▼"}
        </span>
      </button>
      {expanded && (
        <div className="border-t border-border p-5">
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm text-foreground">
            {JSON.stringify(bot, null, 2)}
          </pre>
        </div>
      )}
    </section>
  );
}
