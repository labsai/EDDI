import { useState, useMemo, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api-client";
import { getAgentDescriptors, parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { PipelineRailroad } from "@/components/studio/pipeline-railroad";
import { ChatPanel } from "@/components/chat/chat-panel";
import {
  ArrowLeft,
  Bot,
  Loader2,
  Layers,
  PanelRightClose,
  PanelRight,
} from "lucide-react";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string };
}

interface AgentConfig {
  workflows: string[];
  channels: unknown[];
  _version: number;
}

interface WorkflowConfig {
  workflowSteps: WorkflowStep[];
}

// ==================== Component ====================

export function AgentStudioPage() {
  const { t } = useTranslation();
  const { agentId } = useParams<{ agentId: string }>();
  const [selectedStageIndex, setSelectedStageIndex] = useState<number | null>(null);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  // Fetch agent descriptor for name
  const { data: descriptors } = useQuery({
    queryKey: ["studio", "descriptors"],
    queryFn: () => getAgentDescriptors(100, 0, ""),
    staleTime: 60_000,
  });

  const agentDescriptor = useMemo(() => {
    if (!descriptors || !agentId) return null;
    return descriptors.find((d: AgentDescriptor) => {
      const { id } = parseResourceUri(d.resource);
      return id === agentId;
    }) ?? null;
  }, [descriptors, agentId]);

  // Fetch agent config
  const { data: agentConfig, isLoading: agentLoading } = useQuery({
    queryKey: ["studio", "agent", agentId],
    queryFn: () => api.get<AgentConfig>(`/agentstore/agents/${agentId}`),
    enabled: !!agentId,
    staleTime: 30_000,
  });

  // Get the first workflow URI and fetch its pipeline
  const workflowUri = agentConfig?.workflows?.[0];
  const workflowId = workflowUri
    ? parseResourceUri(workflowUri).id
    : null;

  const { data: workflowConfig } = useQuery({
    queryKey: ["studio", "workflow", workflowId],
    queryFn: () => api.get<WorkflowConfig>(`/workflowstore/workflows/${workflowId}`),
    enabled: !!workflowId,
    staleTime: 30_000,
  });

  const workflowSteps = workflowConfig?.workflowSteps ?? [];

  const handleSelectStage = useCallback((index: number) => {
    setSelectedStageIndex(index);
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

      {/* Three-panel layout */}
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

        {/* Center: Editor placeholder */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {selectedStep ? (
            <div className="flex-1 flex flex-col items-center justify-center p-6 text-center">
              <Layers className="h-12 w-12 text-muted-foreground/20 mb-3" />
              <h3 className="text-sm font-semibold text-foreground">
                {selectedStep.type.replace("ai.labs.", "")}
              </h3>
              {selectedStep.config?.uri && (
                <p className="mt-1 text-xs text-muted-foreground font-mono">
                  {selectedStep.config.uri}
                </p>
              )}
              <p className="mt-3 text-xs text-muted-foreground max-w-xs">
                {t("studio.editorComingSoon", "In-place editor integration coming soon")}
              </p>
              <Link
                to={`/manage/resources/${getResourceType(selectedStep.type)}/${getResourceId(selectedStep.config?.uri)}`}
                className="mt-4 inline-flex items-center gap-1 rounded-lg border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors"
              >
                {t("studio.openEditor", "Open in Editor")}
              </Link>
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center p-6 text-center">
              <Layers className="h-16 w-16 text-muted-foreground/15 mb-4" />
              <p className="text-sm text-muted-foreground">
                {t("studio.selectStage", "Click a pipeline stage to open its editor")}
              </p>
            </div>
          )}
        </div>

        {/* Right: Chat + Debug */}
        {rightPanelOpen && (
          <div className="w-96 shrink-0 border-s border-border overflow-hidden hidden md:flex md:flex-col">
            <ChatPanel />
          </div>
        )}
      </div>

      {/* Mobile: tabs for Pipeline / Editor / Chat */}
      <div className="flex border-t border-border lg:hidden">
        {/* Mobile bottom tabs could go here — simplified for now */}
      </div>
    </div>
  );
}

// ==================== Helpers ====================

function getResourceType(extensionType: string): string {
  const map: Record<string, string> = {
    "ai.labs.rules": "behavior",
    "ai.labs.apicalls": "httpcalls",
    "ai.labs.llm": "langchain",
    "ai.labs.output": "output",
    "ai.labs.property": "propertysetter",
    "ai.labs.mcpcalls": "mcpcalls",
  };
  return map[extensionType] ?? "behavior";
}

function getResourceId(uri?: string): string {
  if (!uri) return "";
  const parts = uri.split("/");
  const last = parts[parts.length - 1] ?? "";
  return last.split("?")[0] ?? last;
}
