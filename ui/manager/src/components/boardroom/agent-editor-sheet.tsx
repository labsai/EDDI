import { useState, useEffect, useCallback, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { X, Plus, RefreshCw, Loader2, Brain } from "lucide-react";
import { cn } from "@/lib/utils";
import { getAgent, getAgentDescriptors, updateAgent } from "@/lib/api/agents";
import type { Agent, Capability, AgentDescriptor } from "@/lib/api/agents";
import { parseResourceUri } from "@/lib/api/agents";
import { useAgentPrompt, useUpdateAgentPrompt } from "@/hooks/use-agent-prompt";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

// ─── Types ───────────────────────────────────────────────────────

interface AgentEditorSheetProps {
  agentId: string | null;
  onClose: () => void;
}

// ─── Confidence helpers ──────────────────────────────────────────

type ConfidenceLevel = "low" | "medium" | "high";

function confidenceBadgeVariant(
  confidence: string | undefined
): "warning" | "secondary" | "success" {
  switch (confidence) {
    case "high":
      return "success";
    case "low":
      return "warning";
    default:
      return "secondary";
  }
}

// ─── Component ───────────────────────────────────────────────────

function AgentEditorSheet({ agentId, onClose }: AgentEditorSheetProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  // ── Data fetching ────────────────────────────────────────────

  const {
    data: agent,
    isLoading: agentLoading,
    isError: agentError,
    refetch: refetchAgent,
  } = useQuery({
    queryKey: ["agent", agentId],
    queryFn: () => getAgent(agentId!),
    enabled: !!agentId,
  });

  const {
    data: descriptors,
    isLoading: descriptorLoading,
    isError: descriptorError,
    refetch: refetchDescriptor,
  } = useQuery({
    queryKey: ["agent-descriptor", agentId],
    queryFn: () => getAgentDescriptors(1, 0, agentId!),
    enabled: !!agentId,
  });

  const descriptor: AgentDescriptor | undefined = descriptors?.[0];
  const version = descriptor
    ? parseResourceUri(descriptor.resource).version
    : 1;
  const agentName = descriptor?.name ?? agentId ?? "";

  const isLoading = agentLoading || descriptorLoading;
  const isError = agentError || descriptorError;

  // ── Local edit state ─────────────────────────────────────────

  const [description, setDescription] = useState("");
  const [capabilities, setCapabilities] = useState<Capability[]>([]);
  const [a2aEnabled, setA2aEnabled] = useState(false);
  const [enableMemoryTools, setEnableMemoryTools] = useState(false);
  const [saving, setSaving] = useState(false);

  // Prompt state
  const [systemPrompt, setSystemPrompt] = useState("");
  const [promptSynced, setPromptSynced] = useState(false);

  // Capability inline-add form
  const [addingCapability, setAddingCapability] = useState(false);
  const [newSkill, setNewSkill] = useState("");
  const [newConfidence, setNewConfidence] = useState<ConfidenceLevel>("medium");

  // Sync from fetched data
  useEffect(() => {
    if (agent) {
      setDescription(agent.description ?? "");
      setCapabilities(agent.capabilities ?? []);
      setA2aEnabled(agent.a2aEnabled ?? false);
      setEnableMemoryTools(agent.enableMemoryTools ?? false);
    }
  }, [agent]);

  // Prompt fetching
  const {
    data: promptData,
    isLoading: promptLoading,
  } = useAgentPrompt(agentId, version);
  const updatePromptMutation = useUpdateAgentPrompt();

  // Sync prompt from fetched data
  useEffect(() => {
    if (promptData && !promptSynced) {
      setSystemPrompt(promptData.systemMessage);
      setPromptSynced(true);
    }
  }, [promptData, promptSynced]);

  // Reset prompt sync when agent changes
  useEffect(() => {
    setPromptSynced(false);
  }, [agentId]);

  // ── Dirty tracking ──────────────────────────────────────────

  const isDirty = (() => {
    if (!agent) return false;
    if (description !== (agent.description ?? "")) return true;
    if (a2aEnabled !== (agent.a2aEnabled ?? false)) return true;
    if (enableMemoryTools !== (agent.enableMemoryTools ?? false)) return true;
    if (JSON.stringify(capabilities) !== JSON.stringify(agent.capabilities ?? []))
      return true;
    if (promptSynced && promptData && systemPrompt !== promptData.systemMessage) return true;
    return false;
  })();

  // ── Auto-expanding textarea ─────────────────────────────────

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = textareaRef.current;
    if (el) {
      el.style.height = "auto";
      el.style.height = `${el.scrollHeight}px`;
    }
  }, [description]);

  // ── Escape key ──────────────────────────────────────────────

  const handleEscape = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (isDirty) {
          if (!window.confirm(t("boardroom.agentEditor.discardChanges", "You have unsaved changes. Discard?"))) {
            return;
          }
        }
        onClose();
      }
    },
    [onClose, isDirty, t],
  );

  useEffect(() => {
    if (!agentId) return;
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [agentId, handleEscape]);

  // ── Capability add / remove ─────────────────────────────────

  const handleAddCapability = useCallback(() => {
    if (!newSkill.trim()) return;
    setCapabilities((prev) => [
      ...prev,
      { skill: newSkill.trim(), confidence: newConfidence },
    ]);
    setNewSkill("");
    setNewConfidence("medium");
    setAddingCapability(false);
  }, [newSkill, newConfidence]);

  const handleRemoveCapability = useCallback((index: number) => {
    setCapabilities((prev) => prev.filter((_, i) => i !== index));
  }, []);

  // ── Save ────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!agentId || !agent) return;
    setSaving(true);
    try {
      let agentVersion = version;

      // 1. Save prompt first (cascade bumps agent version)
      if (promptData && systemPrompt !== promptData.systemMessage) {
        const result = await updatePromptMutation.mutateAsync({
          agentId,
          promptData,
          newSystemMessage: systemPrompt,
        });
        // Use the new agent version from the cascade for the next save
        if (result.newAgentVersion) {
          agentVersion = result.newAgentVersion;
        }
      }

      // 2. Save agent-level changes with the (possibly bumped) version
      const freshAgent = agentVersion !== version
        ? await getAgent(agentId, agentVersion)
        : agent;
      const updated: Agent = {
        ...freshAgent,
        description,
        capabilities,
        a2aEnabled,
        enableMemoryTools,
      };
      await updateAgent(agentId, agentVersion, updated);

      toast.success(
        t("boardroom.agentEditor.saveSuccess", "Agent updated successfully")
      );
    } catch {
      toast.error(
        t("boardroom.agentEditor.saveError", "Failed to save changes")
      );
    } finally {
      setSaving(false);
      // Always re-sync to pick up version bumps, even on partial failures
      await queryClient.invalidateQueries({ queryKey: ["agent", agentId] });
      await queryClient.invalidateQueries({
        queryKey: ["agent-descriptor", agentId],
      });
      await queryClient.invalidateQueries({ queryKey: ["agents"] });
      setPromptSynced(false);
    }
  }, [
    agentId,
    agent,
    description,
    capabilities,
    a2aEnabled,
    enableMemoryTools,
    version,
    queryClient,
    t,
    systemPrompt,
    promptData,
    updatePromptMutation,
  ]);

  // ── Render nothing when closed ──────────────────────────────

  if (!agentId) return null;

  return (
    <>
      {/* Backdrop overlay */}
      <div
        className="fixed inset-0 bg-black/50 z-40 transition-opacity duration-300"
        onClick={() => {
          if (isDirty) {
            if (!window.confirm(t("boardroom.agentEditor.discardChanges", "You have unsaved changes. Discard?"))) {
              return;
            }
          }
          onClose();
        }}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          "fixed top-0 end-0 bottom-0 w-[480px] max-w-full",
          "bg-background border-s border-border z-50",
          "flex flex-col",
          "transition-transform duration-300",
          "shadow-2xl",
        )}
        role="dialog"
        aria-modal={true}
        aria-label={t("boardroom.agentEditor.title", "Edit Agent")}
      >
        {/* ── Header ──────────────────────────────────────── */}
        <div className="flex items-center gap-3 ps-6 pe-4 py-4 border-b border-border shrink-0">
          {isLoading ? (
            <div className="flex items-center gap-3 flex-1">
              <Skeleton className="h-12 w-12 rounded-full" />
              <Skeleton className="h-5 w-32" />
            </div>
          ) : (
            <div className="flex items-center gap-3 flex-1 min-w-0">
              <AdvisorAvatar
                name={agentName}
                agentId={agentId}
                size="lg"
              />
              <h2 className="text-lg font-semibold text-foreground truncate">
                {agentName}
              </h2>
            </div>
          )}
          <Button
            variant="ghost"
            size="icon"
            onClick={onClose}
            className="shrink-0"
            aria-label={t("boardroom.agentEditor.close", "Close")}
          >
            <X />
          </Button>
        </div>

        {/* ── Body ────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto">
          {isLoading && (
            <div className="p-6 space-y-6">
              <div className="space-y-2">
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-20 w-full" />
              </div>
              <div className="space-y-2">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-3/4" />
              </div>
              <div className="space-y-2">
                <Skeleton className="h-4 w-20" />
                <Skeleton className="h-8 w-full" />
              </div>
            </div>
          )}

          {isError && (
            <div className="p-8 text-center space-y-4">
              <p className="text-sm text-muted-foreground">
                {t(
                  "boardroom.agentEditor.loadError",
                  "Failed to load agent data."
                )}
              </p>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  void refetchAgent();
                  void refetchDescriptor();
                }}
              >
                <RefreshCw className="h-4 w-4" />
                {t("boardroom.agentEditor.retry", "Retry")}
              </Button>
            </div>
          )}

          {!isLoading && !isError && agent && (
            <div className="p-6 space-y-6">
              {/* ── 1. Description ─────────────────────────── */}
              <section>
                <label
                  htmlFor="agent-description"
                  className="block text-sm font-medium text-foreground mb-1.5"
                >
                  {t("boardroom.agentEditor.description", "Description")}
                </label>
                <textarea
                  ref={textareaRef}
                  id="agent-description"
                  className={cn(
                    "w-full rounded-lg border border-border bg-card px-3 py-2",
                    "text-sm text-foreground placeholder:text-muted-foreground",
                    "resize-none overflow-hidden",
                    "focus:outline-none focus:ring-2 focus:ring-ring",
                    "transition-colors"
                  )}
                  rows={3}
                  placeholder={t(
                    "boardroom.agentEditor.descriptionPlaceholder",
                    "Describe this agent's expertise..."
                  )}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </section>

              {/* ── 1b. System Prompt ──────────────────────── */}
              <section>
                <label
                  htmlFor="agent-system-prompt"
                  className="flex items-center gap-1.5 text-sm font-medium text-foreground mb-1.5"
                >
                  <Brain className="h-3.5 w-3.5 text-primary" aria-hidden="true" />
                  {t("boardroom.agentEditor.systemPrompt", "System Prompt")}
                </label>
                {promptLoading ? (
                  <div className="space-y-2">
                    <Skeleton className="h-4 w-3/4" />
                    <Skeleton className="h-24 w-full" />
                  </div>
                ) : promptData ? (
                  <textarea
                    id="agent-system-prompt"
                    className={cn(
                      "w-full rounded-lg border border-border bg-card px-3 py-2",
                      "text-sm text-foreground placeholder:text-muted-foreground",
                      "resize-y min-h-[120px]",
                      "focus:outline-none focus:ring-2 focus:ring-ring",
                      "transition-colors text-sm leading-relaxed"
                    )}
                    rows={6}
                    placeholder={t(
                      "boardroom.agentEditor.systemPromptPlaceholder",
                      "Instructions that define this agent's behavior, personality, and expertise..."
                    )}
                    value={systemPrompt}
                    onChange={(e) => setSystemPrompt(e.target.value)}
                  />
                ) : (
                  <p className="text-xs text-muted-foreground italic">
                    {t(
                      "boardroom.agentEditor.noPrompt",
                      "This agent has no configurable system prompt."
                    )}
                  </p>
                )}
              </section>

              {/* ── 2. Capabilities ────────────────────────── */}
              <section>
                <h3 className="text-sm font-medium text-foreground mb-2">
                  {t("boardroom.agentEditor.capabilities", "Capabilities")}
                </h3>

                {capabilities.length === 0 && !addingCapability && (
                  <p className="text-sm text-muted-foreground mb-2">
                    {t(
                      "boardroom.agentEditor.noCapabilities",
                      "No capabilities defined."
                    )}
                  </p>
                )}

                <div className="flex flex-wrap gap-2 mb-3">
                  {capabilities.map((cap, idx) => (
                    <div
                      key={`${cap.skill}-${idx}`}
                      className={cn(
                        "flex items-center gap-1.5 rounded-full border border-border",
                        "bg-card ps-3 pe-1.5 py-1 text-sm"
                      )}
                    >
                      <span className="text-foreground">{cap.skill}</span>
                      {cap.confidence && (
                        <Badge
                          variant={confidenceBadgeVariant(cap.confidence)}
                          className="text-[10px] px-1.5 py-0"
                        >
                          {cap.confidence}
                        </Badge>
                      )}
                      <button
                        type="button"
                        className={cn(
                          "h-5 w-5 rounded-full flex items-center justify-center",
                          "text-muted-foreground hover:text-foreground hover:bg-muted",
                          "transition-colors",
                          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 bg-card"
                        )}
                        onClick={() => handleRemoveCapability(idx)}
                        aria-label={t(
                          "boardroom.agentEditor.removeCapability",
                          "Remove capability"
                        )}
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </div>
                  ))}
                </div>

                {addingCapability ? (
                  <div className="flex items-end gap-2 p-3 rounded-lg border border-border bg-card">
                    <div className="flex-1 min-w-0">
                      <label
                        htmlFor="new-skill"
                        className="block text-xs font-medium text-muted-foreground mb-1"
                      >
                        {t("boardroom.agentEditor.skill", "Skill")}
                      </label>
                      <input
                        id="new-skill"
                        type="text"
                        className={cn(
                          "w-full rounded-md border border-border bg-background px-2.5 py-1.5",
                          "text-sm text-foreground placeholder:text-muted-foreground",
                          "focus:outline-none focus:ring-2 focus:ring-ring"
                        )}
                        placeholder={t(
                          "boardroom.agentEditor.skillPlaceholder",
                          "e.g. summarization"
                        )}
                        value={newSkill}
                        onChange={(e) => setNewSkill(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") handleAddCapability();
                          if (e.key === "Escape") setAddingCapability(false);
                        }}
                        autoFocus
                      />
                    </div>
                    <div>
                      <label
                        htmlFor="new-confidence"
                        className="block text-xs font-medium text-muted-foreground mb-1"
                      >
                        {t("boardroom.agentEditor.confidence", "Confidence")}
                      </label>
                      <select
                        id="new-confidence"
                        className={cn(
                          "rounded-md border border-border bg-background px-2.5 py-1.5",
                          "text-sm text-foreground",
                          "focus:outline-none focus:ring-2 focus:ring-ring"
                        )}
                        value={newConfidence}
                        onChange={(e) =>
                          setNewConfidence(e.target.value as ConfidenceLevel)
                        }
                      >
                        <option value="low">{t("boardroom.agentEditor.confidenceLow", "Low")}</option>
                        <option value="medium">{t("boardroom.agentEditor.confidenceMedium", "Medium")}</option>
                        <option value="high">{t("boardroom.agentEditor.confidenceHigh", "High")}</option>
                      </select>
                    </div>
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={handleAddCapability}
                      disabled={!newSkill.trim()}
                    >
                      {t("boardroom.agentEditor.add", "Add")}
                    </Button>
                  </div>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setAddingCapability(true)}
                  >
                    <Plus className="h-4 w-4" />
                    {t(
                      "boardroom.agentEditor.addCapability",
                      "Add Capability"
                    )}
                  </Button>
                )}
              </section>

              {/* ── 3. Settings Toggles ────────────────────── */}
              <section>
                <h3 className="text-sm font-medium text-foreground mb-3">
                  {t("boardroom.agentEditor.settings", "Settings")}
                </h3>
                <div className="space-y-2">
                  {/* a2aEnabled toggle */}
                  <button
                    type="button"
                    className={cn(
                      "w-full flex items-center justify-between",
                      "rounded-lg border border-border bg-card px-4 py-3",
                      "hover:bg-muted/50 transition-colors text-start",
                      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                    )}
                    onClick={() => setA2aEnabled((v) => !v)}
                    role="switch"
                    aria-checked={a2aEnabled}
                    aria-label={t("boardroom.agentEditor.a2aEnabled", "Agent-to-Agent Communication")}
                  >
                    <span className="text-sm text-foreground">
                      {t(
                        "boardroom.agentEditor.a2aEnabled",
                        "Agent-to-Agent Communication"
                      )}
                    </span>
                    <div
                      className={cn(
                        "relative h-5 w-9 rounded-full transition-colors",
                        a2aEnabled ? "bg-primary" : "bg-muted"
                      )}
                    >
                      <div
                        className={cn(
                          "absolute top-0.5 h-4 w-4 rounded-full bg-background shadow",
                          "transition-all duration-200",
                          a2aEnabled ? "inset-inline-start-[18px]" : "inset-inline-start-0.5"
                        )}
                      />
                    </div>
                  </button>

                  {/* enableMemoryTools toggle */}
                  <button
                    type="button"
                    className={cn(
                      "w-full flex items-center justify-between",
                      "rounded-lg border border-border bg-card px-4 py-3",
                      "hover:bg-muted/50 transition-colors text-start",
                      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                    )}
                    onClick={() => setEnableMemoryTools((v) => !v)}
                    role="switch"
                    aria-checked={enableMemoryTools}
                    aria-label={t("boardroom.agentEditor.memoryTools", "Memory Tools")}
                  >
                    <span className="text-sm text-foreground">
                      {t(
                        "boardroom.agentEditor.memoryTools",
                        "Memory Tools"
                      )}
                    </span>
                    <div
                      className={cn(
                        "relative h-5 w-9 rounded-full transition-colors",
                        enableMemoryTools ? "bg-primary" : "bg-muted"
                      )}
                    >
                      <div
                        className={cn(
                          "absolute top-0.5 h-4 w-4 rounded-full bg-background shadow",
                          "transition-all duration-200",
                          enableMemoryTools
                            ? "inset-inline-start-[18px]"
                            : "inset-inline-start-0.5"
                        )}
                      />
                    </div>
                  </button>
                </div>
              </section>
            </div>
          )}
        </div>

        {/* ── Footer (sticky) ─────────────────────────────── */}
        {!isLoading && !isError && agent && (
          <div className="shrink-0 border-t border-border ps-6 pe-6 py-4 flex items-center justify-end gap-3">
            <Button variant="ghost" onClick={onClose}>
              {t("boardroom.agentEditor.cancel", "Cancel")}
            </Button>
            <Button
              variant="primary"
              onClick={() => void handleSave()}
              disabled={!isDirty || saving}
            >
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              {t("boardroom.agentEditor.save", "Save Changes")}
            </Button>
          </div>
        )}
      </div>
    </>
  );
}

export { AgentEditorSheet };
export type { AgentEditorSheetProps };
