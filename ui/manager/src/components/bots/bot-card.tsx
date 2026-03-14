import { useTranslation } from "react-i18next";
import {
  Rocket,
  Square,
  Clock,
  AlertTriangle,
  Copy,
  Trash2,
  MoreVertical,
  ExternalLink,
  Download,
  MessageSquare,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useDeploymentStatus, useDeployBot, useUndeployBot } from "@/hooks/use-bots";
import { useExportBot } from "@/hooks/use-backup";
import type { BotDescriptor } from "@/lib/api/bots";
import { useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";

interface BotCardProps {
  bot: BotDescriptor & { id: string; version: number };
  onDuplicate: (id: string, version: number) => void;
  onDelete: (id: string, version: number) => void;
  onExport?: (id: string, version: number) => void;
}

const statusConfig = {
  READY: {
    icon: Rocket,
    label: "Deployed",
    color: "text-emerald-500",
    bg: "bg-emerald-500/10",
    ring: "ring-emerald-500/20",
  },
  IN_PROGRESS: {
    icon: Clock,
    label: "Deploying...",
    color: "text-amber-500",
    bg: "bg-amber-500/10",
    ring: "ring-amber-500/20",
  },
  ERROR: {
    icon: AlertTriangle,
    label: "Error",
    color: "text-destructive",
    bg: "bg-destructive/10",
    ring: "ring-destructive/20",
  },
  NOT_FOUND: {
    icon: Square,
    label: "Not deployed",
    color: "text-muted-foreground",
    bg: "bg-muted",
    ring: "ring-border",
  },
};

export function BotCard({ bot, onDuplicate, onDelete }: BotCardProps) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const { data: deployment } = useDeploymentStatus(bot.id, bot.version);
  const deployMutation = useDeployBot();
  const undeployMutation = useUndeployBot();
  const exportMutation = useExportBot();

  const status = deployment?.status ?? "NOT_FOUND";
  const config = statusConfig[status];
  const StatusIcon = config.icon;

  const isDeployed = status === "READY";
  const isBusy =
    deployMutation.isPending ||
    undeployMutation.isPending ||
    status === "IN_PROGRESS";

  function handleDeploy() {
    deployMutation.mutate(
      { botId: bot.id, version: bot.version },
      {
        onSuccess: () => toast.success(t("bots.deploySuccess", "Bot deployed successfully")),
        onError: () => toast.error(t("bots.deployError", "Deploy failed")),
      }
    );
  }

  function handleUndeploy() {
    undeployMutation.mutate(
      { botId: bot.id, version: bot.version },
      {
        onSuccess: () => toast.success(t("bots.undeploySuccess", "Bot undeployed")),
        onError: () => toast.error(t("bots.undeployError", "Undeploy failed")),
      }
    );
  }

  const timeAgo = formatTimeAgo(bot.lastModifiedOn);

  return (
    <div
      className={cn(
        "group relative flex flex-col rounded-xl border bg-card p-5 shadow-sm transition-all duration-200",
        "hover:shadow-md hover:border-primary/30",
        `ring-1 ${config.ring}`
      )}
      data-testid={`bot-card-${bot.id}`}
    >
      {/* Status badge + menu */}
      <div className="flex items-start justify-between">
        <div
          className={cn(
            "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ring-1",
            config.bg,
            config.color,
            config.ring
          )}
        >
          <StatusIcon className="h-3.5 w-3.5" />
          {config.label}
        </div>

        {/* Context menu */}
        <div className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-secondary hover:text-foreground group-hover:opacity-100"
            data-testid={`bot-menu-${bot.id}`}
          >
            <MoreVertical className="h-4 w-4" />
          </button>
          {menuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setMenuOpen(false)}
              />
              <div className="absolute end-0 z-50 mt-1 w-44 rounded-lg border bg-popover py-1 shadow-lg">
                <button
                  onClick={() => {
                    onDuplicate(bot.id, bot.version);
                    setMenuOpen(false);
                  }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary"
                >
                  <Copy className="h-4 w-4" />
                  {t("common.duplicate", "Duplicate")}
                </button>
                <button
                  onClick={() => {
                    exportMutation.mutate({ botId: bot.id, version: bot.version });
                    setMenuOpen(false);
                  }}
                  disabled={exportMutation.isPending}
                  className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary disabled:opacity-50"
                >
                  <Download className="h-4 w-4" />
                  {exportMutation.isPending
                    ? t("bots.exporting", "Exporting...")
                    : t("bots.export", "Export")}
                </button>
                <button
                  onClick={() => {
                    onDelete(bot.id, bot.version);
                    setMenuOpen(false);
                  }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-destructive/10"
                >
                  <Trash2 className="h-4 w-4" />
                  {t("common.delete")}
                </button>
              </div>
            </>
          )}
        </div>
      </div>

      {/* Bot info */}
      <div className="mt-4 flex-1">
        <Link
          to={`/manage/botview/${bot.id}`}
          className="text-lg font-semibold text-foreground hover:text-primary transition-colors"
        >
          {bot.name || t("bots.unnamed", "Unnamed Bot")}
          <ExternalLink className="ms-1 inline h-3.5 w-3.5 opacity-0 group-hover:opacity-50" />
        </Link>
        <p className="mt-0.5 font-mono text-xs text-muted-foreground/70 truncate" title={bot.id}>
          {bot.id}
        </p>
        <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">
          {bot.description || "No description"}
        </p>
      </div>

      {/* Footer: meta + actions */}
      <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
        <span className="text-xs text-muted-foreground" title={new Date(bot.lastModifiedOn).toLocaleString()}>
          {timeAgo}
        </span>

        <div className="flex items-center gap-2">
          {/* Chat button — only when deployed */}
          {isDeployed && (
            <Link
              to={`/manage/chat?botId=${bot.id}`}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
              data-testid={`bot-chat-${bot.id}`}
            >
              <MessageSquare className="h-3.5 w-3.5" />
              {t("bots.chat", "Chat")}
            </Link>
          )}

          <button
            onClick={isDeployed ? handleUndeploy : handleDeploy}
            disabled={isBusy}
            className={cn(
              "rounded-lg px-3 py-1.5 text-xs font-medium transition-colors",
              isDeployed
                ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                : "bg-primary/10 text-primary hover:bg-primary/20",
              isBusy && "cursor-not-allowed opacity-50"
            )}
          >
            {isBusy
              ? t("common.loading")
              : isDeployed
                ? t("bots.undeploy", "Undeploy")
                : t("bots.deploy", "Deploy")}
          </button>
        </div>
      </div>
    </div>
  );
}

function formatTimeAgo(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days < 30) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString();
}
