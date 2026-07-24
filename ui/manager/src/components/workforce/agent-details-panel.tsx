import { useState, useCallback } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAgent } from "@/lib/api/agents";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import {
  PanelRightClose,
  Pencil,
  Bot,
  Copy,
  Check,
  ShieldCheck,
  Brain,
  Zap,
  FileText,
  Sparkles,
  Share2,
  Layers,
  Activity,
  ArrowUpRight,
  Database,
  Lock,
} from "lucide-react";

interface AgentDetailsPanelProps {
  agentId: string | null;
  agentName?: string | null;
  onClose?: () => void;
  className?: string;
}

export function AgentDetailsPanel({
  agentId,
  agentName,
  onClose,
  className,
}: AgentDetailsPanelProps) {
  const { t } = useTranslation();
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState(false);

  // Fetch full agent configuration from backend
  const { data: agentData, isLoading } = useQuery({
    queryKey: ["agent", agentId],
    queryFn: () => getAgent(agentId!),
    enabled: !!agentId,
    staleTime: 10_000,
  });

  const handleCopyId = useCallback(async () => {
    if (!agentId) return;
    try {
      await navigator.clipboard.writeText(agentId);
      setCopiedId(true);
      toast.success(t("common.copied", "Copied to clipboard"));
      setTimeout(() => setCopiedId(false), 1500);
    } catch {
      /* ignore */
    }
  }, [agentId, t]);

  if (!agentId) {
    return (
      <div className={cn("w-72 shrink-0 border-s border-border bg-card overflow-y-auto flex flex-col max-lg:hidden", className)}>
        <div className="p-3 border-b border-border flex items-center justify-between shrink-0">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("Workforce.chat.agentDetails", "Agent Details")}
          </h3>
          {onClose && (
            <button
              type="button"
              onClick={onClose}
              className="p-0.5 rounded hover:bg-secondary/50 text-muted-foreground hover:text-foreground transition-colors"
            >
              <PanelRightClose className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
        <div className="flex flex-1 items-center justify-center p-4">
          <div className="text-center text-muted-foreground">
            <Bot className="h-8 w-8 mx-auto mb-2 opacity-40" />
            <p className="text-xs">
              {t("Workforce.chat.selectToView", "Select an agent to view details")}
            </p>
          </div>
        </div>
      </div>
    );
  }

  const displayName = agentName || agentData?.identity?.agentDid || agentId;

  return (
    <div className={cn("w-80 shrink-0 border-s border-border bg-card overflow-y-auto flex flex-col max-lg:hidden", className)}>
      {/* Header bar */}
      <div className="p-3 border-b border-border flex items-center justify-between shrink-0 bg-muted/20">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
          <Bot className="h-3.5 w-3.5 text-primary" />
          {t("Workforce.chat.agentDetails", "Agent Details")}
        </h3>
        {onClose && (
          <button
            type="button"
            onClick={onClose}
            className="p-1 rounded-md hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
            title={t("Workforce.chat.hideDetails", "Hide details panel")}
          >
            <PanelRightClose className="h-4 w-4" />
          </button>
        )}
      </div>

      {isLoading ? (
        <div className="p-4 space-y-4">
          <div className="flex flex-col items-center gap-2">
            <Skeleton className="h-16 w-16 rounded-full" />
            <Skeleton className="h-4 w-28" />
            <Skeleton className="h-3 w-40" />
          </div>
          <Skeleton className="h-20 w-full rounded-lg" />
          <Skeleton className="h-12 w-full rounded-lg" />
          <Skeleton className="h-10 w-full rounded-lg" />
        </div>
      ) : agentData ? (
        <div className="p-4 space-y-5">
          {/* Main Agent Header Card */}
          <div className="flex flex-col items-center text-center gap-2">
            <div className="relative">
              <AdvisorAvatar
                name={displayName}
                agentId={agentId}
                size="lg"
              />
              <span className="absolute bottom-0 end-0 h-3.5 w-3.5 rounded-full bg-emerald-500 border-2 border-background shadow-xs" title="Agent Ready" />
            </div>

            <div className="w-full min-w-0">
              <h4 className="text-base font-semibold text-foreground truncate" title={displayName}>
                {displayName}
              </h4>

              {/* Agent ID pill */}
              <button
                type="button"
                onClick={handleCopyId}
                className="mt-1 inline-flex items-center gap-1 max-w-full rounded-full border border-border/60 bg-muted/40 px-2.5 py-0.5 text-[10px] font-mono text-muted-foreground hover:bg-muted hover:text-foreground transition-colors truncate"
                title={t("common.copyId", "Copy Agent ID")}
              >
                <span className="truncate">{agentId}</span>
                {copiedId ? (
                  <Check className="h-3 w-3 text-emerald-500 shrink-0" />
                ) : (
                  <Copy className="h-3 w-3 shrink-0" />
                )}
              </button>

              {agentData.description && (
                <p className="text-xs text-muted-foreground/90 mt-2 text-start bg-muted/20 rounded-lg p-2.5 border border-border/40 leading-relaxed">
                  {agentData.description}
                </p>
              )}
            </div>
          </div>

          {/* Quick Action Buttons */}
          <div className="grid grid-cols-2 gap-2">
            <Button
              variant="outline"
              size="sm"
              className="w-full text-xs h-8 justify-start"
              onClick={() => setEditingAgentId(agentId)}
            >
              <Pencil className="h-3.5 w-3.5 me-1 text-primary" />
              {t("Workforce.chat.editAgent", "Edit Agent")}
            </Button>
            <Link to={`/audit?agentId=${agentId}`}>
              <Button
                variant="outline"
                size="sm"
                className="w-full text-xs h-8 justify-start"
              >
                <FileText className="h-3.5 w-3.5 me-1 text-muted-foreground" />
                {t("Workforce.chat.auditLogs", "Audit Logs")}
              </Button>
            </Link>
          </div>

          {/* Capabilities & Skills */}
          {agentData.capabilities && agentData.capabilities.length > 0 && (
            <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2">
              <div className="flex items-center justify-between">
                <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                  <Sparkles className="h-3.5 w-3.5 text-amber-500" />
                  {t("Workforce.agentEditor.capabilities", "Capabilities")}
                </h5>
                <span className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-1.5 py-0.5">
                  {agentData.capabilities.length}
                </span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {agentData.capabilities.map((cap, idx) => (
                  <Badge
                    key={`${cap.skill}-${idx}`}
                    variant="secondary"
                    className="text-[10px] bg-primary/5 text-primary border-primary/20 hover:bg-primary/10 transition-colors"
                  >
                    {cap.skill}
                  </Badge>
                ))}
              </div>
            </div>
          )}

          {/* Workflows / Pipelines */}
          {agentData.workflows && agentData.workflows.length > 0 && (
            <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2">
              <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                <Layers className="h-3.5 w-3.5 text-blue-500" />
                {t("Workforce.agentEditor.workflows", "Workflows")}
              </h5>
              <div className="space-y-1">
                {agentData.workflows.map((wf, idx) => (
                  <div key={idx} className="flex items-center justify-between text-xs py-1 border-b border-border/30 last:border-0">
                    <span className="font-mono text-[11px] text-foreground/80 truncate max-w-[180px]" title={wf}>
                      {wf}
                    </span>
                    <Link to={`/workflows?filter=${wf}`} className="text-muted-foreground hover:text-primary transition-colors">
                      <ArrowUpRight className="h-3 w-3" />
                    </Link>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Feature Settings Overview */}
          <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2.5">
            <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
              <Activity className="h-3.5 w-3.5 text-emerald-500" />
              {t("Workforce.agentEditor.features", "Features & Security")}
            </h5>

            <div className="space-y-2">
              {/* Agent-to-Agent */}
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground flex items-center gap-1.5">
                  <Share2 className="h-3 w-3 text-purple-400" />
                  {t("Workforce.agentEditor.a2aEnabled", "Agent-to-Agent")}
                </span>
                <Badge
                  variant={agentData.a2aEnabled ? "success" : "secondary"}
                  className="text-[10px]"
                >
                  {agentData.a2aEnabled ? t("common.on", "On") : t("common.off", "Off")}
                </Badge>
              </div>

              {/* Memory Tools */}
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground flex items-center gap-1.5">
                  <Brain className="h-3 w-3 text-indigo-400" />
                  {t("Workforce.agentEditor.memoryTools", "Memory Tools")}
                </span>
                <Badge
                  variant={agentData.enableMemoryTools ? "success" : "secondary"}
                  className="text-[10px]"
                >
                  {agentData.enableMemoryTools ? t("common.on", "On") : t("common.off", "Off")}
                </Badge>
              </div>

              {/* Message Signing */}
              {agentData.security?.signInterAgentMessages !== undefined && (
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground flex items-center gap-1.5">
                    <ShieldCheck className="h-3 w-3 text-emerald-400" />
                    {t("Workforce.agentEditor.signedMessages", "Signed Messages")}
                  </span>
                  <Badge
                    variant={agentData.security.signInterAgentMessages ? "success" : "secondary"}
                    className="text-[10px]"
                  >
                    {agentData.security.signInterAgentMessages ? t("common.on", "On") : t("common.off", "Off")}
                  </Badge>
                </div>
              )}

              {/* Memory Discipline Policy */}
              {agentData.memoryPolicy?.strictWriteDiscipline?.enabled && (
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground flex items-center gap-1.5">
                    <Database className="h-3 w-3 text-amber-400" />
                    {t("Workforce.agentEditor.strictMemory", "Strict Memory")}
                  </span>
                  <Badge variant="outline" className="text-[10px] border-amber-500/30 text-amber-500">
                    Active
                  </Badge>
                </div>
              )}

              {/* HITL Configuration */}
              {agentData.hitlConfig?.timeoutPolicy && (
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground flex items-center gap-1.5">
                    <Lock className="h-3 w-3 text-amber-400" />
                    {t("Workforce.agentEditor.hitlMode", "Approval Policy")}
                  </span>
                  <Badge variant="warning" className="text-[10px]">
                    {agentData.hitlConfig.timeoutPolicy}
                  </Badge>
                </div>
              )}
            </div>
          </div>

          {/* Quick Triggers Link */}
          <Link to={`/triggers?agentId=${agentId}`}>
            <Button
              variant="secondary"
              size="sm"
              className="w-full text-xs justify-between mt-2"
            >
              <span className="flex items-center gap-1.5">
                <Zap className="h-3.5 w-3.5 text-amber-500" />
                {t("Workforce.chat.triggers", "View Triggers")}
              </span>
              <ArrowUpRight className="h-3.5 w-3.5 text-muted-foreground" />
            </Button>
          </Link>
        </div>
      ) : (
        <div className="flex flex-1 items-center justify-center p-4">
          <div className="text-center text-muted-foreground">
            <Bot className="h-8 w-8 mx-auto mb-2 opacity-40" />
            <p className="text-xs">
              {t("Workforce.chat.selectToView", "Select an agent to view details")}
            </p>
          </div>
        </div>
      )}

      {/* Agent Editor Sheet (Slide-over) */}
      <AgentEditorSheet
        agentId={editingAgentId}
        onClose={() => setEditingAgentId(null)}
      />
    </div>
  );
}
