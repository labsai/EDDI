import { useState, useMemo, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAgentDescriptors, getAgent, parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { getWorkflow } from "@/lib/api/workflows";
import { PipelineRailroad } from "@/components/studio/pipeline-railroad";
import { StudioEditorPanel, StudioEditorEmpty } from "@/components/studio/studio-editor-panel";
import { ChatPanel } from "@/components/chat/chat-panel";
import {
  ArrowLeft,
  Bot,
  Loader2,
  PanelRightClose,
  PanelRight,
  GitBranch,
  Layers,
  MessageCircle,
} from "lucide-react";
import { cn } from "@/lib/utils";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string };
}

// ==================== Component ====================

export function AgentStudioPage() {
  const { t } = useTranslation();
  const { agentId } = useParams<{ agentId: string }>();
  const [selectedStageIndex, setSelectedStageIndex] = useState<number | null>(null);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [mobileTab, setMobileTab] = useState<"pipeline" | "editor" | "chat">("pipeline");

  // Fetch agent descriptor for name — filter by ID to avoid fetching all agents
  const { data: descriptors } = useQuery({
    queryKey: ["studio", "descriptors", agentId],
    queryFn: () => getAgentDescriptors(10, 0, agentId ?? ""),
    enabled: !!agentId,
    staleTime: 60_000,
  });

  const agentDescriptor = useMemo(() => {
    if (!descriptors || !agentId) return null;
    return descriptors.find((d: AgentDescriptor) => {
      const { id } = parseResourceUri(d.resource);
      return id === agentId;
    }) ?? null;
  }, [descriptors, agentId]);

  // Get agent version from descriptor
  const agentVersion = useMemo(() => {
    if (!agentDescriptor) return 1;
    return parseResourceUri(agentDescriptor.resource).version;
  }, [agentDescriptor]);

  // Fetch agent config
  const { data: agentConfig, isLoading: agentLoading } = useQuery({
    queryKey: ["studio", "agent", agentId],
    queryFn: () => getAgent(agentId!),
    enabled: !!agentId,
    staleTime: 30_000,
  });

  // Get the first workflow URI and fetch its pipeline
  const workflowUri = agentConfig?.workflows?.[0];
  const { workflowId, workflowVersion } = useMemo(() => {
    if (!workflowUri) return { workflowId: null, workflowVersion: 1 };
    const parsed = parseResourceUri(workflowUri);
    return { workflowId: parsed.id, workflowVersion: parsed.version };
  }, [workflowUri]);

  const { data: workflowConfig } = useQuery({
    queryKey: ["studio", "workflow", workflowId],
    queryFn: () => getWorkflow(workflowId!, workflowVersion),
    enabled: !!workflowId,
    staleTime: 30_000,
  });

  const workflowSteps = (workflowConfig?.workflowSteps ?? []) as WorkflowStep[];

  const handleSelectStage = useCallback((index: number) => {
    setSelectedStageIndex(index);
    // On mobile, auto-switch to editor tab
    setMobileTab("editor");
  }, []);

  if (!agentId) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">{t("studio.notFound", "Agent not found")}</p>
      </div>
    );
  }

  if (agentLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="ms-2 text-sm text-muted-foreground">
          {t("studio.loading", "Loading agent...")}
        </p>
      </div>
    );
  }

  const selectedStep = selectedStageIndex !== null ? workflowSteps[selectedStageIndex] : null;

  return (
    <div className="flex h-full flex-col" data-testid="agent-studio">
      {/* Header */}
      <div className="flex items-center gap-3 border-b border-border px-4 py-2.5 shrink-0">
        <Link
          to={`/manage/agentview/${agentId}`}
          className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          data-testid="studio-back"
          aria-label={t("studio.backToAgent", "Back to agent detail")}
        >
          <ArrowLeft className="h-4 w-4" />
        </Link>
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 shrink-0">
            <Bot className="h-4 w-4 text-primary" />
          </div>
          <div className="min-w-0">
            <h1 className="text-sm font-semibold text-foreground truncate">
              {agentDescriptor?.name ?? agentId}
            </h1>
            <p className="text-[10px] text-muted-foreground">
              {t("studio.title", "Agent Studio")}
            </p>
          </div>
        </div>

        <button
          onClick={() => setRightPanelOpen(!rightPanelOpen)}
          className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          title={rightPanelOpen ? t("studio.hideChat", "Hide chat") : t("studio.showChat", "Show chat")}
          data-testid="toggle-right-panel"
        >
          {rightPanelOpen ? (
            <PanelRightClose className="h-4 w-4" />
          ) : (
            <PanelRight className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Desktop: Three-panel layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: Pipeline Railroad */}
        <div className="w-64 shrink-0 border-e border-border overflow-y-auto bg-card/30 hidden lg:block">
          <div className="px-3 pt-3 pb-1">
            <h2 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
              {t("studio.pipeline", "Pipeline")}
            </h2>
          </div>
          <PipelineRailroad
            workflowSteps={workflowSteps}
            selectedIndex={selectedStageIndex}
            onSelectStage={handleSelectStage}
          />
        </div>

        {/* Center: Editor */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {/* Desktop view */}
          <div className="hidden lg:flex lg:flex-1 lg:flex-col lg:overflow-hidden">
            {selectedStep && workflowId ? (
              <StudioEditorPanel
                key={`${selectedStageIndex}-${selectedStep.config?.uri}`}
                workflowStep={selectedStep}
                agentId={agentId}
                agentVersion={agentVersion}
                workflowId={workflowId}
                workflowVersion={workflowVersion}
              />
            ) : (
              <StudioEditorEmpty />
            )}
          </div>

          {/* Mobile/tablet view — show active tab content */}
          <div className="flex flex-1 flex-col overflow-hidden lg:hidden">
            {mobileTab === "pipeline" && (
              <div className="flex-1 overflow-y-auto">
                <div className="px-3 pt-3 pb-1">
                  <h2 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
                    {t("studio.pipeline", "Pipeline")}
                  </h2>
                </div>
                <PipelineRailroad
                  workflowSteps={workflowSteps}
                  selectedIndex={selectedStageIndex}
                  onSelectStage={handleSelectStage}
                />
              </div>
            )}
            {mobileTab === "editor" && (
              <div className="flex-1 overflow-hidden flex flex-col">
                {selectedStep && workflowId ? (
                  <StudioEditorPanel
                    key={`mobile-${selectedStageIndex}-${selectedStep.config?.uri}`}
                    workflowStep={selectedStep}
                    agentId={agentId}
                    agentVersion={agentVersion}
                    workflowId={workflowId}
                    workflowVersion={workflowVersion}
                  />
                ) : (
                  <StudioEditorEmpty />
                )}
              </div>
            )}
            {mobileTab === "chat" && (
              <div className="flex-1 overflow-hidden flex flex-col">
                <ChatPanel />
              </div>
            )}
          </div>
        </div>

        {/* Right: Chat + Debug */}
        {rightPanelOpen && (
          <div className="w-96 shrink-0 border-s border-border overflow-hidden hidden md:flex md:flex-col">
            <ChatPanel />
          </div>
        )}
      </div>

      {/* Mobile: bottom tab bar */}
      <div className="flex border-t border-border lg:hidden">
        {([
          { id: "pipeline" as const, icon: <GitBranch className="h-4 w-4" />, label: t("studio.pipeline", "Pipeline") },
          { id: "editor" as const, icon: <Layers className="h-4 w-4" />, label: t("studio.editor", "Editor") },
          { id: "chat" as const, icon: <MessageCircle className="h-4 w-4" />, label: t("studio.chat", "Chat") },
        ]).map((tab) => (
          <button
            key={tab.id}
            onClick={() => setMobileTab(tab.id)}
            className={cn(
              "flex flex-1 flex-col items-center gap-0.5 py-2 text-[10px] font-medium transition-colors",
              mobileTab === tab.id
                ? "text-primary border-t-2 border-primary -mt-[2px]"
                : "text-muted-foreground hover:text-foreground",
            )}
            data-testid={`mobile-tab-${tab.id}`}
          >
            {tab.icon}
            {tab.label}
          </button>
        ))}
      </div>
    </div>
  );
}
