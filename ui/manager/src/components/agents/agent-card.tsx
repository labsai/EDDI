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
  Share2,
  MessageSquare,
  Sparkles,
} from "lucide-react";
import { cn, formatRelativeTime } from "@/lib/utils";
import { useDeploymentStatuses, useDeployAgent, useUndeployAgent } from "@/hooks/use-agents";
import { DeploymentEnvironmentBadge } from "./deployment-environments";
import { OwnershipBadge } from "@/components/workspaces/ownership-badge";
import { accessFor, type ResourceAccess } from "@/lib/access";
import { useEnvironmentLabel } from "@/hooks/use-environment-label";
import { deployedEnvironments, isAnyEnvironmentBusy } from "@/lib/deployment-environments";

import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore, useStartConversation } from "@/hooks/use-chat";
import { useOperatorConfig } from "@/hooks/use-operator";
import { getErrorMessage } from "@/lib/api-client";
import type { AgentDescriptor } from "@/lib/api/agents";
import { useState, useCallback, useId, useRef, useEffect } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";

interface AgentCardProps {
  agent: AgentDescriptor & { id: string; version: number };
  onDuplicate: (id: string, version: number) => void;
  onDelete: (id: string, version: number) => void;
  onExport?: (id: string, version: number) => void;
  onShare?: (id: string, name: string) => void;
}

// Status labels use i18n keys — resolved in component body
const statusIcons = {
  READY: { icon: Rocket, color: "text-emerald-500", bg: "bg-emerald-500/10", ring: "ring-emerald-500/20" },
  IN_PROGRESS: { icon: Clock, color: "text-amber-500", bg: "bg-amber-500/10", ring: "ring-amber-500/20" },
  ERROR: { icon: AlertTriangle, color: "text-destructive", bg: "bg-destructive/10", ring: "ring-destructive/20" },
  NOT_FOUND: { icon: Square, color: "text-muted-foreground", bg: "bg-muted", ring: "ring-border" },
};

export function AgentCard({ agent, onDuplicate, onDelete, onExport, onShare }: AgentCardProps) {
  // What this user may actually do with THIS agent. Absent on a backend that
  // does not enforce workspaces, which reads as unrestricted — see accessFor.
  const access = accessFor(agent.callerLevel);
  const { data: operatorConfig } = useOperatorConfig();
  const isOperatorAgent = Boolean(operatorConfig?.agentId && operatorConfig.agentId === agent.id);
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuTriggerRef = useRef<HTMLButtonElement>(null);
  const { data: envStatuses, isLoading: statusLoading } = useDeploymentStatuses(agent.id, agent.version);
  const envLabel = useEnvironmentLabel();
  const deployMutation = useDeployAgent();
  const undeployMutation = useUndeployAgent();
  const startConversation = useStartConversation();

  const closeMenuAndRestore = useCallback(() => {
    setMenuOpen(false);
    requestAnimationFrame(() => menuTriggerRef.current?.focus());
  }, []);

  // Every environment, not just production. Reading production alone labelled a
  // test-only agent "Not deployed" on the card most people navigate from.
  const liveEnvironments = deployedEnvironments(envStatuses);
  // The deploy/undeploy toggle still acts on PRODUCTION — the card is a list
  // affordance, and per-environment control lives on the agent's own page. Its
  // label names the environment so it cannot be misread next to a badge that
  // says "Test".
  const productionStatus =
    envStatuses?.find((s) => s.environment === "production")?.status ?? "NOT_FOUND";
  const isProductionDeployed = productionStatus === "READY";
  const config = statusIcons[isProductionDeployed ? "READY" : productionStatus];
  const isBusy =
    deployMutation.isPending ||
    undeployMutation.isPending ||
    isAnyEnvironmentBusy(envStatuses);

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
        <DeploymentEnvironmentBadge
          statuses={envStatuses}
          isLoading={statusLoading}
          data-testid={`agent-deployment-${agent.id}`}
        />

        {/* The Platform Operator agent is provisioned and owned by the operator
            screen. Editing or deleting it here leaves that screen pointing at
            nothing, so say who owns it. */}
        <OwnershipBadge
          ownerId={agent.ownerId}
          spaceId={agent.spaceId}
          visibility={agent.visibility}
        />

        {isOperatorAgent && (
          <span
            className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary"
            data-testid={`agent-managed-${agent.id}`}
            title={t("operator.managedAgentHint", "Provisioned and managed by the Platform Operator screen. Editing or deleting it here will break that screen.")}
          >
            <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
            {t("operator.managedAgentBadge", "Operator")}
          </span>
        )}

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
                onShare={
                  // Re-sharing is an owner's decision — EDIT deliberately does
                  // not carry it — so offering it to anyone else only produces a
                  // 403 they cannot act on.
                  onShare && access.canOwn
                    ? () => {
                        onShare(agent.id, agent.name || agent.id);
                        setMenuOpen(false);
                      }
                    : undefined
                }
                access={access}
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
        {/* The name links to the CONFIGURATION view, which is a VIEW act — so a
            USE holder gets the name as plain text rather than a link into a page
            that would 403 on every panel. They can still converse: the chat
            buttons below need USE, which is exactly what they hold. */}
        {access.canView ? (
          <Link
            to={`/manage/agentview/${agent.id}`}
            className="text-lg font-semibold text-foreground hover:text-primary transition-colors"
            data-testid={`agent-open-${agent.id}`}
          >
            {agent.name || t("agents.unnamed", "Unnamed Agent")}
            <ExternalLink className="ms-1 inline h-3.5 w-3.5 opacity-0 group-hover:opacity-50" />
          </Link>
        ) : (
          <span className="text-lg font-semibold text-foreground">
            {agent.name || t("agents.unnamed", "Unnamed Agent")}
          </span>
        )}
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
          {/* One chat entry PER LIVE ENVIRONMENT. There are at most two, so a
              menu would be heavier than the choice deserves — and the previous
              single button silently targeted production, which is why a
              test-only agent could not be chatted with at all. When exactly one
              environment is live the button reads simply "Chat"; with both, each
              names its environment so the click is never a guess. */}
          {liveEnvironments.map((env) => (
            <div className="inline-flex" key={env}>
              <button
                onClick={async () => {
                  const drawerStore = useChatDrawerStore.getState();
                  const chatStore = useChatStore.getState();
                  const agentName = agent.name || t("agents.unnamed", "Unnamed Agent");
                  drawerStore.open(agent.id, agentName, env);
                  drawerStore.setStep("starting");
                  chatStore.clearMessages();
                  chatStore.setSelectedAgent(agent.id, agentName);
                  try {
                    await startConversation.mutateAsync({ agentId: agent.id, environment: env });
                    drawerStore.setStep("ready");
                  } catch (err) {
                    drawerStore.setStep("error", getErrorMessage(err));
                  }
                }}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-s-lg px-3 py-1.5 text-xs font-medium transition-colors",
                  env === "production"
                    ? "bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 dark:text-emerald-400"
                    : "bg-sky-500/10 text-sky-600 hover:bg-sky-500/20 dark:text-sky-400",
                )}
                data-testid={`agent-chat-${env}-${agent.id}`}
                aria-label={t("agents.chatIn", "Chat in {{environment}}", { environment: envLabel(env) })}
              >
                <MessageSquare className="h-3.5 w-3.5" aria-hidden="true" />
                {liveEnvironments.length > 1 ? envLabel(env) : t("agents.chat", "Chat")}
              </button>
              <a
                href={`/chat/${env}/${agent.id}`}
                target="_blank"
                rel="noopener noreferrer"
                className={cn(
                  "inline-flex items-center rounded-e-lg border-s px-1.5 py-1.5 transition-colors",
                  env === "production"
                    ? "border-emerald-500/20 bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 dark:text-emerald-400"
                    : "border-sky-500/20 bg-sky-500/10 text-sky-600 hover:bg-sky-500/20 dark:text-sky-400",
                )}
                title={t("agents.openExternalChatIn", "Open {{environment}} chat in a new tab", { environment: envLabel(env) })}
                aria-label={t("agents.openExternalChatIn", "Open {{environment}} chat in a new tab", { environment: envLabel(env) })}
                data-testid={`agent-external-chat-${env}-${agent.id}`}
              >
                <ExternalLink className="h-3 w-3" aria-hidden="true" />
              </a>
            </div>
          ))}

          {/* Deploying and undeploying are EDIT, and the gates below the menu
              missed this one because it sits outside it. A VIEW holder could
              still undeploy somebody else's agent from production — or rather,
              could still be OFFERED that and get a 403, which is the same
              broken-looking outcome the row gating exists to remove. */}
          {access.canEdit && (
          <button
            onClick={isProductionDeployed ? handleUndeploy : handleDeploy}
            disabled={isBusy}
            data-testid={`agent-deploy-toggle-${agent.id}`}
            className={cn(
              "rounded-lg px-3 py-1.5 text-xs font-medium transition-colors",
              isProductionDeployed
                ? "bg-destructive/10 text-destructive hover:bg-destructive/20"
                : "bg-primary/10 text-primary hover:bg-primary/20",
              isBusy && "cursor-not-allowed opacity-50"
            )}
          >
            {/* Names the environment: this toggle acts on production, and the
                badge beside it may well say "Test". A bare "Deploy" next to a
                Test chip reads as "not deployed", which is the confusion this
                whole card change exists to remove. */}
            {isBusy
              ? t("common.loading", "Loading...")
              : isProductionDeployed
                ? t("agents.undeployFromProduction", "Undeploy from production")
                : t("agents.deployToProduction", "Deploy to production")}
          </button>
          )}
        </div>
      </div>
    </div>
  );
}

/* ─── AgentCardMenu — context menu with full ARIA keyboard nav ─── */

function AgentCardMenu({
  onDuplicate,
  onExport,
  onShare,
  onDelete,
  onClose,
  access,
}: {
  onDuplicate: () => void;
  onExport: () => void;
  /** Omitted on a backend without workspaces, which hides the entry entirely. */
  onShare?: () => void;
  onDelete: () => void;
  onClose: () => void;
  /**
   * What the caller may do with this agent.
   *
   * Entries are **omitted** rather than disabled. A disabled control still
   * teaches that the action exists and invites a hunt for how to enable it; an
   * absent one says the resource is not yours to do that with, which is the
   * actual situation. Keyboard navigation reads the DOM, so a shorter menu
   * stays correct without any change here.
   */
  access: ResourceAccess;
}) {
  const { t } = useTranslation();
  const menuRef = useRef<HTMLDivElement>(null);
  const emptyNoticeId = useId();
  const hasNoActions = !access.canView && !access.canOwn;

  // Auto-focus first item on mount, falling back to the menu itself.
  //
  // The fallback is not cosmetic: a USE-only agent has no actionable entries, so
  // there is no first item, focus stayed on the trigger, and the menu's own
  // onKeyDown never saw Escape — leaving a keyboard user behind a full-screen
  // backdrop with no way out. Caught by an E2E run, where the next click landed
  // on the backdrop instead of the button it aimed at.
  useEffect(() => {
    requestAnimationFrame(() => {
      const firstItem = menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]');
      if (firstItem) firstItem.focus();
      else menuRef.current?.focus();
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
      aria-describedby={hasNoActions ? emptyNoticeId : undefined}
      // Focusable so Escape still reaches this handler when the menu has no
      // actionable entries to take focus.
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      {/* Duplicating and exporting both READ the configuration — the whole
          config graph, in fact — so both need VIEW. USE deliberately does not
          carry it: being allowed to talk to an agent is not being allowed to
          read its prompts, tools and vault references. */}
      {access.canView && (
        <button
          onClick={onDuplicate}
          className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary focus:bg-secondary"
          role="menuitem"
          tabIndex={-1}
        >
          <Copy className="h-4 w-4" aria-hidden="true" />
          {t("common.duplicate", "Duplicate")}
        </button>
      )}
      {access.canView && (
        <button
          onClick={onExport}
          className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary focus:bg-secondary disabled:opacity-50"
          role="menuitem"
          tabIndex={-1}
        >
          <Download className="h-4 w-4" aria-hidden="true" />
          {t("agents.export", "Export")}
        </button>
      )}
      {onShare && (
        <button
          onClick={onShare}
          className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary focus:bg-secondary"
          role="menuitem"
          tabIndex={-1}
        >
          <Share2 className="h-4 w-4" aria-hidden="true" />
          {t("workspaces.share.title", "Share")}
        </button>
      )}
      {/* Deleting is an owner's decision. EDIT covers changing and deploying;
          it deliberately stops short of destroying the thing. */}
      {access.canOwn && (
        <button
          onClick={onDelete}
          className="flex w-full items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-destructive/10 focus:bg-destructive/10"
          role="menuitem"
          tabIndex={-1}
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
          {t("common.delete")}
        </button>
      )}

      {/* A menu with nothing in it would open empty, so say why it is empty.
          Deliberately NOT role="menuitem": it is not actionable, and marking it
          as one puts arrow-key navigation onto static text and makes it answer
          to "menuitem named share" — which is how this line first showed up, as
          a Share entry that was not there. */}
      {!access.canView && !access.canOwn && (
        // Described by, not contained as a menuitem: `role="menu"` requires
        // menuitem children, and most screen readers announce only the
        // container's name on focus — so a bare <p> in here is both invalid and
        // silent. `aria-describedby` on the container reads it out instead,
        // while keeping the line non-actionable.
        <p id={emptyNoticeId} className="px-3 py-2 text-xs text-muted-foreground">
          {t("workspaces.useOnlyMenu", "Shared with you for chatting only.")}
        </p>
      )}
    </div>
  );
}
