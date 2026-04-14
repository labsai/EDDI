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
import { cn, formatRelativeTime } from "@/lib/utils";
import { useDeploymentStatus, useDeployAgent, useUndeployAgent } from "@/hooks/use-agents";

import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore, useStartConversation } from "@/hooks/use-chat";
import { getErrorMessage } from "@/lib/api-client";
import type { AgentDescriptor } from "@/lib/api/agents";
import { useState, useCallback, useRef, useEffect } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";

interface AgentCardProps {
  agent: AgentDescriptor & { id: string; version: number };
  onDuplicate: (id: string, version: number) => void;
  onDelete: (id: string, version: number) => void;
  onExport?: (id: string, version: number) => void;
}

// Status labels use i18n keys — resolved in component body
const statusIcons = {
  READY: { icon: Rocket, color: "text-emerald-500", bg: "bg-emerald-500/10", ring: "ring-emerald-500/20" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10", ring: "ring-amber-500/20" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10", ring: "ring-destructive/20" },
  NOT_FOUND: { icon: Square, color: "text-muted-foreground", bg: "bg-muted", ring: "ring-border" },
};

export function AgentCard({ agent, onDuplicate, onDelete, onExport }: AgentCardProps) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuTriggerRef = useRef<HTMLButtonElement>(null);
  const { data: deployment } = useDeploymentStatus(agent.id, agent.version);
  const deployMutation = useDeployAgent();
  const undeployMutation = useUndeployAgent();
  const startConversation = useStartConversation();

  const closeMenuAndRestore = useCallback(() => {
    setMenuOpen(false);
    requestAnimationFrame(() => menuTriggerRef.current?.focus());
  }, []);

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
  const isBusy =
    deployMutation.isPending ||
    undeployMutation.isPending ||
    status === "IN_PROGRESS";

  function handleDeploy() {
    deployMutation.mutate(
      { agentId: agent.id, version: agent.version },
      {
        onSuccess: () => toast.success(t("agents.deploySuccess", "Agent deployed successfully")),
        onError: () => toast.error(t("agents.deployError", "Deploy failed")),
      }
    );
  }

  function handleUndeploy() {
    undeployMutation.mutate(
      { agentId: agent.id, version: agent.version },
      {
        onSuccess: () => toast.success(t("agents.undeploySuccess", "Agent undeployed")),
        onError: () => toast.error(t("agents.undeployError", "Undeploy failed")),
      }
    );
  }

  const timeAgo = formatRelativeTime(agent.lastModifiedOn);

  return (
    <div
      className={cn(
        "group relative flex flex-col rounded-xl border bg-card p-5 shadow-sm transition-all duration-200",
        "hover:shadow-md hover:border-primary/30",
        `ring-1 ${config.ring}`
      )}
      data-testid={`agent-card-${agent.id}`}
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
          {statusLabel}
        </div>

        {/* Context menu */}
        <div className="relative">
          <button
            ref={menuTriggerRef}
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-secondary hover:text-foreground group-hover:opacity-100 focus:opacity-100 group-focus-within:opacity-100"
            data-testid={`agent-menu-${agent.id}`}
            aria-label={t("common.moreActions", "More actions")}
            aria-haspopup="true"
            aria-expanded={menuOpen}
          >
            <MoreVertical className="h-4 w-4" aria-hidden="true" />
          </button>
          {menuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={closeMenuAndRestore}
                aria-hidden="true"
              />
              <AgentCardMenu
                onDuplicate={() => {
                  onDuplicate(agent.id, agent.version);
                  setMenuOpen(false);
                }}
                onExport={() => {
                  onExport?.(agent.id, agent.version);
                  setMenuOpen(false);
                }}
                onDelete={() => {
                  onDelete(agent.id, agent.version);
                  setMenuOpen(false);
                }}
                onClose={closeMenuAndRestore}
              />
            </>
          )}
        </div>
      </div>

      {/* Agent info */}
      <div className="mt-4 flex-1">
        <Link
          to={`/manage/agentview/${agent.id}`}
          className="text-lg font-semibold text-foreground hover:text-primary transition-colors"
        >
          {agent.name || t("agents.unnamed", "Unnamed Agent")}
          <ExternalLink className="ms-1 inline h-3.5 w-3.5 opacity-0 group-hover:opacity-50" />
        </Link>
        <p className="mt-0.5 font-mono text-xs text-muted-foreground/70 truncate" title={agent.id}>
          {agent.id}
        </p>
        <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">
          {agent.description || t("agents.noDescription", "No description")}
        </p>
      </div>

      {/* Footer: meta + actions */}
      <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
        <span className="text-xs text-muted-foreground" title={new Date(agent.lastModifiedOn).toLocaleString()}>
          {timeAgo}
        </span>

        <div className="flex items-center gap-2">
          {/* Chat button group — inline + external, only when deployed */}
          {isDeployed && (
            <div className="inline-flex">
              <button
                onClick={async () => {
                  const drawerStore = useChatDrawerStore.getState();
                  const chatStore = useChatStore.getState();
                  const agentName = agent.name || t("agents.unnamed", "Unnamed Agent");
                  drawerStore.open(agent.id, agentName);
                  drawerStore.setStep("starting");
                  chatStore.clearMessages();
                  chatStore.setSelectedAgent(agent.id, agentName);
                  try {
                    await startConversation.mutateAsync({ agentId: agent.id });
                    drawerStore.setStep("ready");
                  } catch (err) {
                    drawerStore.setStep("error", getErrorMessage(err));
                  }
                }}
                className="inline-flex items-center gap-1.5 rounded-s-lg bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
                data-testid={`agent-chat-${agent.id}`}
                aria-label={t("agents.chat", "Chat")}
              >
                <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
                {t("agents.chat", "Chat")}
              </button>
              <a
                href={`/chat/production/${agent.id}`}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center rounded-e-lg border-s border-emerald-500/20 bg-emerald-500/10 px-1.5 py-1.5 text-emerald-600 hover:bg-emerald-500/20 transition-colors dark:text-emerald-400"
                title={t("agents.openExternalChat", "Open in new tab")}
                aria-label={t("agents.openExternalChat", "Open in new tab")}
                data-testid={`agent-external-chat-${agent.id}`}
              >
                <ExternalLink className="h-3 w-3" aria-hidden="true" />
              </a>
            </div>
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
                ? t("agents.undeploy", "Undeploy")
                : t("agents.deploy", "Deploy")}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ─── AgentCardMenu — context menu with full ARIA keyboard nav ─── */

function AgentCardMenu({
  onDuplicate,
  onExport,
  onDelete,
  onClose,
}: {
  onDuplicate: () => void;
  onExport: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const menuRef = useRef<HTMLDivElement>(null);

  // Auto-focus first item on mount
  useEffect(() => {
    requestAnimationFrame(() => {
      const firstItem = menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]');
      firstItem?.focus();
    });
  }, []);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      const items = menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]');
      if (!items || items.length === 0) return;
      const itemArray = Array.from(items);
      const currentIndex = itemArray.indexOf(document.activeElement as HTMLElement);

      let nextIndex: number | null = null;
      switch (e.key) {
        case "ArrowDown":
          nextIndex = (currentIndex + 1) % itemArray.length;
          break;
        case "ArrowUp":
          nextIndex = (currentIndex - 1 + itemArray.length) % itemArray.length;
          break;
        case "Home":
          nextIndex = 0;
          break;
        case "End":
          nextIndex = itemArray.length - 1;
          break;
        default:
          return;
      }
      e.preventDefault();
      itemArray[nextIndex]?.focus();
    },
    [onClose],
  );

  return (
    <div
      ref={menuRef}
      className="absolute inset-e-0 z-50 mt-1 w-44 rounded-lg border bg-popover py-1 shadow-lg"
      role="menu"
      aria-label={t("common.moreActions", "More actions")}
      onKeyDown={handleKeyDown}
    >
      <button
        onClick={onDuplicate}
        className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary focus:bg-secondary"
        role="menuitem"
        tabIndex={-1}
      >
        <Copy className="h-4 w-4" aria-hidden="true" />
        {t("common.duplicate", "Duplicate")}
      </button>
      <button
        onClick={onExport}
        className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary focus:bg-secondary disabled:opacity-50"
        role="menuitem"
        tabIndex={-1}
      >
        <Download className="h-4 w-4" aria-hidden="true" />
        {t("agents.export", "Export")}
      </button>
      <button
        onClick={onDelete}
        className="flex w-full items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-destructive/10 focus:bg-destructive/10"
        role="menuitem"
        tabIndex={-1}
      >
        <Trash2 className="h-4 w-4" aria-hidden="true" />
        {t("common.delete")}
      </button>
    </div>
  );
}
