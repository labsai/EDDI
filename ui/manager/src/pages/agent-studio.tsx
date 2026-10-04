import { useState, useMemo, useCallback, useEffect, useId, useRef } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import type { CascadeContext } from "@/lib/api/cascade-save";
import { getAgentDescriptors, getAgent, parseResourceUri, type AgentDescriptor } from "@/lib/api/agents";
import { getWorkflow } from "@/lib/api/workflows";
import { PipelineRailroad } from "@/components/studio/pipeline-railroad";
import { StudioEditorPanel, StudioEditorEmpty } from "@/components/studio/studio-editor-panel";
import { ChatPanel } from "@/components/chat/chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { useStudioSaveAndTest } from "@/components/studio/use-studio-save-and-test";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { useWorkflowDescriptors } from "@/hooks/use-workflows";
import { useSpaces } from "@/hooks/use-spaces";
import { accessForDetail } from "@/lib/access";
import { ResizeHandle } from "@/components/ui/resize-handle";
import { ErrorState } from "@/components/shared/error-state";
import {
  ArrowLeft,
  Bot,
  Loader2,
  PanelRightClose,
  PanelRight,
  MessageCircle,
  Workflow,
  SquarePen,
} from "lucide-react";
import { cn } from "@/lib/utils";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string };
}

// ==================== Constants ====================

const PIPELINE_MIN = 180;
const PIPELINE_MAX = 400;
const PIPELINE_DEFAULT = 256;
const CHAT_MIN = 280;
const CHAT_MAX = 600;
const CHAT_DEFAULT = 384;

/** The resource id a step's config URI names, or "" when it names none. */
function resourceIdOf(uri: string | undefined): string {
  if (!uri) return "";
  try {
    return parseResourceUri(uri).id;
  } catch {
    return "";
  }
}

// ==================== Component ====================

export function AgentStudioPage() {
  const { t } = useTranslation();
  const { agentId } = useParams<{ agentId: string }>();
  const [selectedStageIndex, setSelectedStageIndex] = useState<number | null>(null);
  // The studio edits one of the agent's workflows at a time; the first is the default.
  const [selectedWorkflowIndex, setSelectedWorkflowIndex] = useState(0);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const workflowSelectId = useId();

  /*
   * Unsaved edits, per mounted editor panel (the desktop and the mobile layout
   * each mount one). Replacing a panel — by choosing another stage or workflow —
   * discards what was typed, so the page asks first.
   */
  const [dirtyPanels, setDirtyPanels] = useState<ReadonlySet<string>>(new Set());
  const savers = useRef(new Map<string, () => Promise<boolean>>());
  const [pendingNavigation, setPendingNavigation] = useState<(() => void) | null>(null);
  const handleDirtyChange = useCallback((panelId: string, dirty: boolean) => {
    setDirtyPanels((prev) => {
      if (prev.has(panelId) === dirty) return prev;
      const next = new Set(prev);
      if (dirty) next.add(panelId);
      else next.delete(panelId);
      return next;
    });
  }, []);
  const registerSaver = useCallback((panelId: string, save: (() => Promise<boolean>) | null) => {
    if (save) savers.current.set(panelId, save);
    else savers.current.delete(panelId);
  }, []);
  const hasUnsavedEdits = dirtyPanels.size > 0;
  /** Run `action` now, or after the user has decided what to do with their edits. */
  const guardUnsaved = useCallback(
    (action: () => void) => {
      if (hasUnsavedEdits) setPendingNavigation(() => action);
      else action();
    },
    [hasUnsavedEdits],
  );
  const { saveAndTest, isRunning: isSaveAndTesting } = useStudioSaveAndTest();

  // Resizable panel widths. The handle reports a logical delta — positive means
  // "this panel grows" — so RTL needs no special case here.
  const [pipelineWidth, setPipelineWidth] = useState(PIPELINE_DEFAULT);
  const [chatWidth, setChatWidth] = useState(CHAT_DEFAULT);

  const handlePipelineResize = useCallback((delta: number) => {
    setPipelineWidth((w) => Math.min(PIPELINE_MAX, Math.max(PIPELINE_MIN, w + delta)));
  }, []);

  const handleChatResize = useCallback((delta: number) => {
    // The chat handle sits on the panel's leading edge, so a drag towards the
    // panel shrinks it.
    setChatWidth((w) => Math.min(CHAT_MAX, Math.max(CHAT_MIN, w - delta)));
  }, []);
  const [mobileTab, setMobileTab] = useState<"pipeline" | "editor" | "chat">("pipeline");

  // Fetch agent descriptor for name — filter by ID to avoid fetching all agents
  const {
    data: descriptors,
    isError: descriptorsError,
    refetch: refetchDescriptors,
  } = useQuery({
    queryKey: ["studio", "descriptors", agentId],
    queryFn: () => getAgentDescriptors(10, 0, agentId ?? ""),
    enabled: !!agentId,
    staleTime: 60_000,
  });

  const workspacesEnforced = useSpaces().enforcement;
  const access = accessForDetail(descriptors, agentId, workspacesEnforced);

  const agentDescriptor = useMemo(() => {
    if (!descriptors || !agentId) return null;
    return descriptors.find((d: AgentDescriptor) => {
      const { id } = parseResourceUri(d.resource);
      return id === agentId;
    }) ?? null;
  }, [descriptors, agentId]);

  // Get agent version from descriptor
  const descriptorAgentVersion = useMemo(() => {
    if (!agentDescriptor) return 1;
    return parseResourceUri(agentDescriptor.resource).version;
  }, [agentDescriptor]);

  /**
   * The versions the last stage save produced — owned here, not by the editor
   * panel, because every stage shares them.
   *
   * Each stage's panel is its own mount. When the panel kept these, selecting a
   * second stage mounted a panel seeded from this page's stale versions (the
   * descriptor query is cached for a minute and the workflow query was keyed by
   * id alone), so its first save 409'd on the workflow — after the resource had
   * already been written, leaving an orphaned resource version behind.
   *
   * Only ever moves forward: a descriptor that reports a NEWER agent version
   * than this (an edit made elsewhere) wins, and this is dropped.
   */
  const [savedContext, setSavedContext] = useState<CascadeContext | null>(null);
  /**
   * The newest version each stage's resource was saved at from this page, by
   * resource id. Lives here for the same reason: a save that failed after the
   * resource hop wrote a version the pipeline does not show, and a panel
   * remounted on returning to the stage would otherwise address the old one.
   */
  const [savedResourceVersions, setSavedResourceVersions] = useState<Record<string, number>>({});
  const handleResourceVersionSaved = useCallback((resourceId: string, version: number) => {
    setSavedResourceVersions((prev) =>
      (prev[resourceId] ?? 0) >= version ? prev : { ...prev, [resourceId]: version },
    );
  }, []);
  const liveSavedContext =
    savedContext &&
    savedContext.agentId === agentId &&
    savedContext.agentVersion >= descriptorAgentVersion
      ? savedContext
      : null;
  const agentVersion = liveSavedContext?.agentVersion ?? descriptorAgentVersion;

  // Fetch agent config — must pass version for the API to return data
  const {
    data: agentConfig,
    isLoading: agentLoading,
    isError: agentError,
    refetch: refetchAgent,
  } = useQuery({
    queryKey: ["studio", "agent", agentId, agentVersion],
    queryFn: () => getAgent(agentId!, agentVersion),
    enabled: !!agentId && !!agentDescriptor,
    staleTime: 30_000,
    // Keep the pipeline on screen while a saved version loads, rather than
    // replacing the whole studio (and the open editor) with a spinner.
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === agentId ? previous : undefined,
  });

  // The selected workflow's URI (clamped: the agent may have fewer workflows
  // after a switch to another agent or version)
  const agentWorkflows = agentConfig?.workflows ?? [];
  const workflowUri = agentWorkflows[Math.min(selectedWorkflowIndex, Math.max(agentWorkflows.length - 1, 0))];
  const { data: workflowDescriptors } = useWorkflowDescriptors(100, 0, "");
  const workflowLabels = useMemo(() => {
    const names = new Map<string, string>();
    for (const d of workflowDescriptors ?? []) {
      if (d.name) names.set(parseResourceUri(d.resource).id, d.name);
    }
    return names;
  }, [workflowDescriptors]);
  const { workflowId, workflowVersion, agentWorkflowVersion } = useMemo(() => {
    if (!workflowUri) {
      return { workflowId: null, workflowVersion: 1, agentWorkflowVersion: undefined };
    }
    const parsed = parseResourceUri(workflowUri);
    // A save's versions win until the refetched agent catches up with them.
    if (
      liveSavedContext &&
      liveSavedContext.workflowId === parsed.id &&
      liveSavedContext.workflowVersion >= parsed.version
    ) {
      return {
        workflowId: parsed.id,
        workflowVersion: liveSavedContext.workflowVersion,
        agentWorkflowVersion: liveSavedContext.agentWorkflowVersion,
      };
    }
    return { workflowId: parsed.id, workflowVersion: parsed.version, agentWorkflowVersion: undefined };
  }, [workflowUri, liveSavedContext]);

  const {
    data: workflowConfig,
    isError: workflowError,
    refetch: refetchWorkflow,
  } = useQuery({
    // The version is part of the key: keyed by id alone, the pipeline kept
    // showing the pre-save step URIs, so reopening a stage edited its old version.
    queryKey: ["studio", "workflow", workflowId, workflowVersion],
    queryFn: () => getWorkflow(workflowId!, workflowVersion),
    enabled: !!workflowId,
    staleTime: 30_000,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === workflowId ? previous : undefined,
  });

  const workflowSteps = (workflowConfig?.workflowSteps ?? []) as WorkflowStep[];

  const handleSelectStage = useCallback(
    (index: number) => {
      if (index === selectedStageIndex) {
        setMobileTab("editor");
        return;
      }
      guardUnsaved(() => {
        setSelectedStageIndex(index);
        // On mobile, auto-switch to editor tab
        setMobileTab("editor");
      });
    },
    [selectedStageIndex, guardUnsaved],
  );

  const handleSelectWorkflow = useCallback(
    (index: number) => {
      if (index === selectedWorkflowIndex) return;
      guardUnsaved(() => {
        setSelectedWorkflowIndex(index);
        setSelectedStageIndex(null);
      });
    },
    [selectedWorkflowIndex, guardUnsaved],
  );

  // Another agent: start from its first workflow, with nothing selected.
  useEffect(() => {
    setSelectedWorkflowIndex(0);
    setSelectedStageIndex(null);
  }, [agentId]);

  // The chat beside the editor follows the studio's agent. It reads the global
  // chat store, which still held whichever agent was chatted with last — the
  // studio then tested the wrong agent without saying so. Only preselects: a
  // conversation is started by the user (or by Save & Test), never here.
  const agentDisplayName = agentDescriptor?.name ?? agentId ?? "";
  useEffect(() => {
    if (!agentId) return;
    if (useChatStore.getState().selectedAgentId !== agentId) {
      useChatStore.getState().setSelectedAgent(agentId, agentDisplayName);
    }
  }, [agentId, agentDisplayName]);

  const runSaveAndTest = useCallback(
    (save: () => Promise<{ newAgentVersion: number }>) => {
      if (!agentId) return;
      void saveAndTest({ agentId, agentName: agentDisplayName, save });
    },
    [agentId, agentDisplayName, saveAndTest],
  );

  const confirmSaveThenLeave = useCallback(async () => {
    const action = pendingNavigation;
    if (!action) return;
    const results = await Promise.all([...savers.current.values()].map((save) => save()));
    if (results.every(Boolean)) {
      setPendingNavigation(null);
      action();
    }
    // A failed save keeps the prompt closed and the edits in place — the error
    // is already on screen as a toast.
    else setPendingNavigation(null);
  }, [pendingNavigation]);

  if (!agentId) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">{t("studio.notFound", "Agent not found")}</p>
      </div>
    );
  }

  // Checked before the loading branch, and covering all three queries rather
  // than just the agent one. Each is gated on the previous succeeding
  // (`enabled: !!agentDescriptor`, `enabled: !!workflowId`), so a failed
  // prerequisite leaves the next query *disabled* rather than errored — it
  // never reports isLoading or isError. Without every branch here, a failed
  // descriptor or workflow fetch falls through to the normal chrome with an
  // empty pipeline, which is indistinguishable from an agent that has no
  // workflow at all.
  //
  // Each error is additionally gated on its data being absent: TanStack Query
  // keeps the last good result when a background refetch fails (window
  // refocus, default retry), and unmounting the whole studio — including any
  // in-progress editor state — over a blip while usable data exists would be
  // strictly worse than continuing to show it.
  const descriptorsBlocked = descriptorsError && !descriptors;
  const agentBlocked = agentError && !agentConfig;
  const workflowBlocked = workflowError && !workflowConfig;
  if (descriptorsBlocked || agentBlocked || workflowBlocked) {
    const retryFailed = descriptorsBlocked
      ? refetchDescriptors
      : agentBlocked
        ? refetchAgent
        : refetchWorkflow;
    return (
      <div className="flex h-full items-center justify-center p-6">
        <ErrorState
          message={t("common.error", "Something went wrong")}
          onRetry={() => retryFailed()}
          retryLabel={t("common.retry", "Retry")}
        />
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

  const workflowSwitcher =
    agentWorkflows.length > 1 ? (
      <div className="px-3 pb-2">
        <label
          htmlFor={workflowSelectId}
          className="mb-1 block text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60"
        >
          {t("studio.workflow", "Workflow")}
        </label>
        <select
          id={workflowSelectId}
          value={Math.min(selectedWorkflowIndex, agentWorkflows.length - 1)}
          onChange={(e) => handleSelectWorkflow(Number(e.target.value))}
          className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          data-testid="studio-workflow-switcher"
        >
          {agentWorkflows.map((uri, i) => {
            const wfId = parseResourceUri(uri).id;
            return (
              <option key={uri} value={i}>
                {workflowLabels.get(wfId) ?? wfId}
              </option>
            );
          })}
        </select>
      </div>
    ) : null;

  const selectedStep = selectedStageIndex !== null ? workflowSteps[selectedStageIndex] : null;
  const selectedResourceId = resourceIdOf(selectedStep?.config?.uri);

  return (
    // `relative`: same contract as AppLayout — a fixed-height shell that scrolls
    // only in its inner panes. Without a positioned root, an `absolute`
    // descendant (Tailwind's `sr-only`, for one) resolves against the initial
    // containing block, slips past the `overflow-hidden` body row below, and
    // scrolls the whole studio.
    <div className="relative flex h-screen flex-col bg-background" data-testid="agent-studio">
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
        <div
          className="shrink-0 border-e border-border overflow-y-auto bg-card/30 hidden lg:block"
          style={{ width: pipelineWidth }}
        >
          <div className="px-3 pt-3 pb-1">
            <h2 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/60">
              {t("studio.pipeline", "Pipeline")}
            </h2>
          </div>
          {workflowSwitcher}
          <PipelineRailroad
            workflowSteps={workflowSteps}
            selectedIndex={selectedStageIndex}
            onSelectStage={handleSelectStage}
          />
        </div>

        {/* Resize handle: Pipeline / Editor */}
        <ResizeHandle
          direction="horizontal"
          onResize={handlePipelineResize}
          label={t("studio.pipeline", "Pipeline")}
          value={pipelineWidth}
          min={PIPELINE_MIN}
          max={PIPELINE_MAX}
          className="hidden lg:block"
        />

        {/* Center: Editor */}
        <div className="flex-1 flex flex-col overflow-hidden">
          {/* Desktop view */}
          <div className="hidden lg:flex lg:flex-1 lg:flex-col lg:overflow-hidden">
            {selectedStep && workflowId ? (
              <StudioEditorPanel
                key={`${selectedStageIndex}-${selectedResourceId}`}
                workflowStep={selectedStep}
                agentId={agentId}
                agentVersion={agentVersion}
                workflowId={workflowId}
                workflowVersion={workflowVersion}
                agentWorkflowVersion={agentWorkflowVersion}
                onCascadeContextChange={setSavedContext}
                savedResourceVersion={savedResourceVersions[selectedResourceId]}
                onResourceVersionSaved={handleResourceVersionSaved}
                panelId="desktop"
                onDirtyChange={handleDirtyChange}
                registerSaver={registerSaver}
                onSaveAndTest={runSaveAndTest}
                isSaveAndTesting={isSaveAndTesting}
                readOnly={!access.canEdit}
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
                {workflowSwitcher}
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
                    key={`mobile-${selectedStageIndex}-${selectedResourceId}`}
                    workflowStep={selectedStep}
                    agentId={agentId}
                    agentVersion={agentVersion}
                    workflowId={workflowId}
                    workflowVersion={workflowVersion}
                    agentWorkflowVersion={agentWorkflowVersion}
                    onCascadeContextChange={setSavedContext}
                    savedResourceVersion={savedResourceVersions[selectedResourceId]}
                    onResourceVersionSaved={handleResourceVersionSaved}
                    panelId="mobile"
                    onDirtyChange={handleDirtyChange}
                    registerSaver={registerSaver}
                    onSaveAndTest={runSaveAndTest}
                    isSaveAndTesting={isSaveAndTesting}
                    readOnly={!access.canEdit}
                  />
                ) : (
                  <StudioEditorEmpty />
                )}
              </div>
            )}
            {mobileTab === "chat" && (
              <div className="flex-1 overflow-hidden flex flex-col">
                <ChatPanel embedded />
              </div>
            )}
          </div>
        </div>

        {/* Right: Chat + Debug */}
        {rightPanelOpen && (
          <>
            <ResizeHandle
              direction="horizontal"
              onResize={handleChatResize}
              label={t("studio.chat", "Chat")}
              value={chatWidth}
              min={CHAT_MIN}
              max={CHAT_MAX}
              className="hidden md:block"
            />
            <div
              className="shrink-0 border-s border-border overflow-hidden hidden md:flex md:flex-col min-h-0 min-w-0"
              style={{ width: chatWidth }}
            >
              <ChatPanel embedded />
            </div>
          </>
        )}
      </div>

      {/* Leaving a stage with unsaved edits: save, discard or stay */}
      <UnsavedChangesDialog
        open={pendingNavigation !== null}
        onCancel={() => setPendingNavigation(null)}
        onConfirm={() => {
          const action = pendingNavigation;
          setPendingNavigation(null);
          action?.();
        }}
        onSave={() => void confirmSaveThenLeave()}
        title={t("studio.unsavedTitle", "Unsaved changes in this stage")}
        message={t(
          "studio.unsavedMessage",
          "Switching stages replaces the editor. Save your changes first, discard them, or stay on this stage."
        )}
        confirmLabel={t("studio.discardAndSwitch", "Discard & switch")}
      />

      {/* Mobile: bottom tab bar */}
      <div className="flex border-t border-border lg:hidden">
        {([
          { id: "pipeline" as const, icon: <Workflow className="h-4 w-4" />, label: t("studio.pipeline", "Pipeline") },
          { id: "editor" as const, icon: <SquarePen className="h-4 w-4" />, label: t("studio.editor", "Editor") },
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
