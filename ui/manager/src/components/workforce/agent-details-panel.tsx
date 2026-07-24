import { useState, useCallback } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAgent, parseResourceUri } from "@/lib/api/agents";
import { getResource, RESOURCE_TYPES } from "@/lib/api/resources";
import { getWorkflow } from "@/lib/api/workflows";
import { useDeployedAgents } from "@/hooks/use-chat";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import type { LlmConfig } from "@/components/editors/llm/types";
import {
  PanelRightClose,
  Pencil,
  Bot,
  Copy,
  Check,
  ShieldCheck,
  Brain,
  FileText,
  Sparkles,
  Share2,
  Layers,
  Activity,
  ArrowUpRight,
  Database,
  Lock,
  Cpu,
  Radio,
  ChevronDown,
  ChevronUp,
  Terminal,
  Wrench,
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
  const [copiedPersona, setCopiedPersona] = useState(false);
  const [personaExpanded, setPersonaExpanded] = useState(false);

  // Read deployed agents to resolve display name, description & real resource ID
  const { data: deployedAgents } = useDeployedAgents();

  const matchedDescriptor = deployedAgents?.find(
    (a) =>
      parseResourceUri(a.resource).id === agentId ||
      a.name === agentId ||
      a.resource === agentId
  );

  const parsedAgent = matchedDescriptor ? parseResourceUri(matchedDescriptor.resource) : null;
  const realAgentId = parsedAgent?.id || agentId;
  const realAgentVersion = parsedAgent?.version;

  // Fetch full agent configuration from backend
  const { data: agentData, isLoading } = useQuery({
    queryKey: ["agent", realAgentId, realAgentVersion],
    queryFn: () => getAgent(realAgentId!, realAgentVersion),
    enabled: !!realAgentId,
    staleTime: 10_000,
    retry: 1,
  });

  // ── Resolve: Agent → Workflow → LLM step → LLM resource ──

  // 1) Parse the first workflow URI from the agent
  const firstWorkflowUri = agentData?.workflows?.[0];
  const parsedWorkflow = firstWorkflowUri ? parseResourceUri(firstWorkflowUri) : null;

  // 2) Fetch the workflow to get its pipeline steps
  const { data: workflowData, isLoading: isWorkflowLoading } = useQuery({
    queryKey: ["workflow", parsedWorkflow?.id, parsedWorkflow?.version],
    queryFn: () => getWorkflow(parsedWorkflow!.id, parsedWorkflow!.version),
    enabled: !!parsedWorkflow?.id,
    staleTime: 30_000,
    retry: false,
  });

  // 3) Find the LLM step in the pipeline and extract the LLM resource URI
  const llmStep = workflowData?.workflowSteps?.find(
    (step) => step.type.includes("ai.labs.llm") || step.type.includes("langchain")
  );
  const llmStepConfigUri = llmStep?.config?.uri as string | undefined;
  const parsedLlmUri = llmStepConfigUri ? parseResourceUri(llmStepConfigUri) : null;

  // 4) Fetch the actual LLM resource configuration
  const llmTypeConfig = RESOURCE_TYPES.find((r) => r.slug === "llm");
  const { data: llmConfig, isLoading: isLlmLoading } = useQuery<LlmConfig>({
    queryKey: ["resource", "llm", parsedLlmUri?.id, parsedLlmUri?.version],
    queryFn: () => getResource<LlmConfig>(llmTypeConfig!, parsedLlmUri!.id, parsedLlmUri!.version),
    enabled: !!llmTypeConfig && !!parsedLlmUri?.id,
    staleTime: 30_000,
    retry: false,
  });

  // The cascade is still resolving if any step in the chain is loading
  const llmIsResolving = !!firstWorkflowUri && (isWorkflowLoading || (!llmStep && !workflowData) || isLlmLoading);

  // Extract actual model configuration parameters from the LLM task
  const firstTask = llmConfig?.tasks?.[0];
  const extractedModel =
    firstTask?.parameters?.model ||
    firstTask?.parameters?.modelName ||
    firstTask?.parameters?.["model.name"] ||
    firstTask?.parameters?.["llm.model"] ||
    (firstTask?.type ? firstTask.type.split(".").pop() : undefined);

  const displayModel = extractedModel || (llmConfig ? "Custom LLM" : null);
  const temperature = firstTask?.parameters?.temperature ?? null;
  const maxCtx = firstTask?.maxContextTokens;
  const contextLimit =
    maxCtx != null && maxCtx > 0
      ? `${(maxCtx / 1024).toFixed(0)}k tokens`
      : maxCtx === -1
        ? null // -1 means unlimited, omit
        : null;

  // Build comprehensive list of active tool names
  const activeTools: string[] = [];
  if (firstTask?.tools?.length) {
    activeTools.push(...firstTask.tools);
  }
  if (firstTask?.builtInToolsWhitelist?.length) {
    activeTools.push(...firstTask.builtInToolsWhitelist);
  }
  if (firstTask?.enableHttpCallTools) activeTools.push("HTTP Calls");
  if (firstTask?.enableMcpCallTools) activeTools.push("MCP Tools");
  if (agentData?.enableMemoryTools) activeTools.push("Memory");
  if (agentData?.capabilities?.length) {
    for (const cap of agentData.capabilities) {
      if (!activeTools.includes(cap.skill)) activeTools.push(cap.skill);
    }
  }

  const handleCopyId = useCallback(async () => {
    if (!realAgentId) return;
    try {
      await navigator.clipboard.writeText(realAgentId);
      setCopiedId(true);
      toast.success(t("common.copied", "Copied to clipboard"));
      setTimeout(() => setCopiedId(false), 1500);
    } catch {
      /* ignore */
    }
  }, [realAgentId, t]);

  const handleCopyPersona = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedPersona(true);
      toast.success(t("common.copied", "Copied to clipboard"));
      setTimeout(() => setCopiedPersona(false), 1500);
    } catch {
      /* ignore */
    }
  }, [t]);

  if (!agentId) {
    return (
      <div className={cn("w-80 shrink-0 border-s border-border bg-card overflow-y-auto flex flex-col max-lg:hidden", className)}>
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
            >
              <PanelRightClose className="h-4 w-4" />
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

  const displayName = agentName || matchedDescriptor?.name || agentId;
  const description = agentData?.description || matchedDescriptor?.description;

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

      {isLoading && !matchedDescriptor ? (
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
      ) : (
        <div className="p-4 space-y-5">
          {/* Main Agent Header Card */}
          <div className="flex flex-col items-center text-center gap-2">
            <div className="relative">
              <AdvisorAvatar
                name={displayName}
                agentId={realAgentId || agentId}
                size="lg"
              />
              <span className="absolute bottom-0 end-0 h-3.5 w-3.5 rounded-full bg-emerald-500 border-2 border-background shadow-xs" title="Agent Deployed & Ready" />
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
                <span className="truncate">{realAgentId || agentId}</span>
                {copiedId ? (
                  <Check className="h-3 w-3 text-emerald-500 shrink-0" />
                ) : (
                  <Copy className="h-3 w-3 shrink-0" />
                )}
              </button>

              {description && (
                <p className="text-xs text-muted-foreground/90 mt-2 text-start bg-muted/20 rounded-lg p-2.5 border border-border/40 leading-relaxed">
                  {description}
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
              onClick={() => setEditingAgentId(realAgentId || agentId)}
            >
              <Pencil className="h-3.5 w-3.5 me-1 text-primary" />
              {t("Workforce.chat.editAgent", "Edit Agent")}
            </Button>
            <Link to={`/audit?agentId=${realAgentId || agentId}`}>
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

          {/* Model & LLM Configuration Card */}
          <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2.5">
            <div className="flex items-center justify-between">
              <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                <Cpu className="h-3.5 w-3.5 text-purple-400" />
                {t("Workforce.agentEditor.llmConfig", "Model & LLM Engine")}
              </h5>
              {displayModel && (
                <Badge variant="outline" className="text-[10px] border-purple-500/30 text-purple-400 bg-purple-500/5 font-mono">
                  {displayModel}
                </Badge>
              )}
            </div>
            <div className="space-y-2 text-xs">
              {llmIsResolving ? (
                /* Still loading LLM config */
                <div className="space-y-1.5">
                  <Skeleton className="h-3 w-full" />
                  <Skeleton className="h-3 w-3/4" />
                </div>
              ) : !llmConfig ? (
                <span className="text-[11px] text-muted-foreground italic">
                  {t("Workforce.agentEditor.noLlm", "No LLM configured")}
                </span>
              ) : (
                <>
                  {temperature != null && (
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">{t("Workforce.agentEditor.temperature", "Temperature")}</span>
                      <span className="font-mono text-[11px] font-medium text-foreground">{temperature}</span>
                    </div>
                  )}
                  {contextLimit != null && (
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">{t("Workforce.agentEditor.contextWindow", "Context Limit")}</span>
                      <span className="font-mono text-[11px] font-medium text-foreground">{contextLimit}</span>
                    </div>
                  )}
                  {firstTask?.type && (
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">{t("Workforce.agentEditor.provider", "Provider")}</span>
                      <span className="font-mono text-[11px] font-medium text-foreground capitalize">{firstTask.type.replace("ai.labs.", "")}</span>
                    </div>
                  )}
                </>
              )}

              {/* Specific Active Tools List */}
              <div className="space-y-1 pt-1 border-t border-border/40">
                <span className="text-muted-foreground flex items-center gap-1 text-[11px]">
                  <Wrench className="h-3 w-3 text-sky-400" />
                  {t("Workforce.agentEditor.activeTools", "Active Tools")}
                </span>
                <div className="flex flex-wrap gap-1 pt-0.5">
                  {activeTools.length > 0 ? (
                    activeTools.map((tool, idx) => (
                      <Badge
                        key={`${tool}-${idx}`}
                        variant="secondary"
                        className="text-[10px] bg-sky-500/10 text-sky-400 border-sky-500/20"
                      >
                        {tool}
                      </Badge>
                    ))
                  ) : (
                    <span className="text-[11px] text-muted-foreground italic">
                      {t("Workforce.agentEditor.noTools", "None configured")}
                    </span>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* System Prompt / Persona Preview Card */}
          {description && (
            <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2">
              <div className="flex items-center justify-between">
                <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
                  <Terminal className="h-3.5 w-3.5 text-emerald-400" />
                  {t("Workforce.agentEditor.personaPrompt", "Persona Prompt")}
                </h5>
                <button
                  type="button"
                  onClick={() => handleCopyPersona(description)}
                  className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
                  title={t("common.copy", "Copy Persona")}
                >
                  {copiedPersona ? (
                    <Check className="h-3 w-3 text-emerald-500" />
                  ) : (
                    <Copy className="h-3 w-3" />
                  )}
                </button>
              </div>
              <div
                className={cn(
                  "text-[11px] font-mono text-muted-foreground bg-muted/30 rounded-md p-2 border border-border/30 transition-all overflow-hidden",
                  !personaExpanded && "line-clamp-3"
                )}
              >
                {description}
              </div>
              <button
                type="button"
                onClick={() => setPersonaExpanded((v) => !v)}
                className="flex items-center gap-1 text-[10px] text-primary hover:underline font-medium pt-0.5"
              >
                {personaExpanded ? (
                  <>
                    <ChevronUp className="h-3 w-3" /> {t("common.showLess", "Show Less")}
                  </>
                ) : (
                  <>
                    <ChevronDown className="h-3 w-3" /> {t("common.showMore", "Expand Persona")}
                  </>
                )}
              </button>
            </div>
          )}

          {/* Capabilities & Skills */}
          {agentData?.capabilities && agentData.capabilities.length > 0 && (
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

          {/* Channel Connectors & Deployment Targets */}
          <div className="rounded-lg border border-border/60 bg-card p-3 space-y-2">
            <h5 className="text-xs font-semibold text-foreground flex items-center gap-1.5">
              <Radio className="h-3.5 w-3.5 text-emerald-400" />
              {t("Workforce.agentEditor.channels", "Channel Connectors")}
            </h5>
            <div className="flex flex-wrap gap-1.5">
              <Badge variant="secondary" className="text-[10px] border border-border/50">
                Web Widget
              </Badge>
              <Badge variant="secondary" className="text-[10px] border border-border/50">
                REST API
              </Badge>
              {agentData?.a2aEnabled && (
                <Badge variant="secondary" className="text-[10px] border border-purple-500/30 text-purple-400 bg-purple-500/5">
                  A2A Protocol
                </Badge>
              )}
            </div>
          </div>

          {/* Workflows / Pipelines */}
          {agentData?.workflows && agentData.workflows.length > 0 && (
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
                  variant={agentData?.a2aEnabled ? "success" : "secondary"}
                  className="text-[10px]"
                >
                  {agentData?.a2aEnabled ? t("common.on", "On") : t("common.off", "Off")}
                </Badge>
              </div>

              {/* Memory Tools */}
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground flex items-center gap-1.5">
                  <Brain className="h-3 w-3 text-indigo-400" />
                  {t("Workforce.agentEditor.memoryTools", "Memory Tools")}
                </span>
                <Badge
                  variant={agentData?.enableMemoryTools ? "success" : "secondary"}
                  className="text-[10px]"
                >
                  {agentData?.enableMemoryTools ? t("common.on", "On") : t("common.off", "Off")}
                </Badge>
              </div>

              {/* Message Signing */}
              {agentData?.security?.signInterAgentMessages !== undefined && (
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
              {agentData?.memoryPolicy?.strictWriteDiscipline?.enabled && (
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
              {agentData?.hitlConfig?.timeoutPolicy && (
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
