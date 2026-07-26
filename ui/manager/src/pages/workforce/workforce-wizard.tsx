import { useState, useCallback, useMemo, useRef } from "react";
import { useNavigate, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useSetupAgent } from "@/hooks/use-agent-setup";
import { useCreateGroup } from "@/hooks/use-groups";
import {
  getGroupTemplates,
  buildGroupFromTemplate,
  DEFAULT_AGENT_TIMEOUT_SECONDS,
} from "@/lib/group-templates";
import {
  parseGroupResourceUri,
  type DiscussionStyle,
  type GroupMember,
} from "@/lib/api/groups";
import { StepIndicator } from "@/components/workforce/wizard/step-indicator";
import { TemplatePicker } from "@/components/workforce/wizard/template-picker";
import {
  TeamBuilder,
  type MemberSlot,
} from "@/components/workforce/wizard/team-builder";
import {
  ReviewLaunch,
  type CreationProgressItem,
} from "@/components/workforce/wizard/review-launch";

// ─── Constants ──────────────────────────────────────────────────────────────

const Workforce_PROGRESS_ID = "__Workforce__";

// ─── Helper: create empty MemberSlot ────────────────────────────────────────

function emptySlot(
  displayName = "",
  role = "",
): MemberSlot {
  return {
    id: crypto.randomUUID(),
    displayName,
    role,
    mode: "new",
    agentId: "",
    systemPrompt: "",
    provider: "",
    model: "",
    apiKey: "",
  };
}

// ─── WorkforceWizard ────────────────────────────────────────────────────────

function WorkforceWizard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const setupAgent = useSetupAgent();
  const createGroup = useCreateGroup();
  const creatingRef = useRef(false);

  // ─── State ──────────────────────────────────────────────────────────────

  const [currentStep, setCurrentStep] = useState(0);
  const [selectedTemplate, setSelectedTemplate] = useState<string | null>(null);
  const [boardName, setBoardName] = useState("");
  const [boardDescription, setBoardDescription] = useState("");
  const [members, setMembers] = useState<MemberSlot[]>([]);
  const [isCreating, setIsCreating] = useState(false);
  const [creationProgress, setCreationProgress] = useState<
    CreationProgressItem[]
  >([]);

  const templates = useMemo(() => getGroupTemplates(t), [t]);

  // ─── Derived ────────────────────────────────────────────────────────────

  const selectedTemplateObj = useMemo(
    () => templates.find((tpl) => tpl.key === selectedTemplate) ?? null,
    [templates, selectedTemplate],
  );

  const resolvedStyle: DiscussionStyle | null =
    selectedTemplateObj?.style ?? (selectedTemplate === "custom" ? "CUSTOM" : null);

  const steps = useMemo(
    () => [
      { label: t("Workforce.wizard.stepTemplate", "Template") },
      { label: t("Workforce.wizard.stepTeam", "Team") },
      { label: t("Workforce.wizard.stepReview", "Review") },
    ],
    [t],
  );

  // ─── Navigation guards ─────────────────────────────────────────────────

  const canProceed = useMemo(() => {
    if (currentStep === 0) return selectedTemplate !== null;
    if (currentStep === 1)
      return (
        boardName.trim().length > 0 &&
        members.length > 0 &&
        members.every((m) => m.displayName.trim().length > 0)
      );
    return false; // Step 2 uses CreateButton inside ReviewLaunch
  }, [currentStep, selectedTemplate, boardName, members]);

  // ─── Template selection handler ─────────────────────────────────────────

  const handleTemplateSelect = useCallback(
    (key: string) => {
      setSelectedTemplate(key);

      if (key === "custom") {
        setBoardName("");
        setBoardDescription("");
        setMembers([emptySlot(), emptySlot()]);
        return;
      }

      const tpl = templates.find((tmpl) => tmpl.key === key);
      if (!tpl) return;

      setBoardName(tpl.name);
      setBoardDescription(tpl.description);
      setMembers(
        tpl.roles.map((r) =>
          emptySlot(r.displayName, r.role ?? ""),
        ),
      );
    },
    [templates],
  );

  // ─── Creation flow ──────────────────────────────────────────────────────

  const updateProgress = useCallback(
    (id: string, patch: Partial<CreationProgressItem>) => {
      setCreationProgress((prev) =>
        prev.map((p) => (p.id === id ? { ...p, ...patch } : p)),
      );
    },
    [],
  );

  const handleCreate = useCallback(async () => {
    if (creatingRef.current) return;
    creatingRef.current = true;
    setIsCreating(true);

    // Snapshot completed IDs before overwriting progress state
    const completedIds = new Set(
      creationProgress
        .filter((p) => p.status === "done")
        .map((_, i) => i),
    );

    // Initialize progress entries
    const progressItems: CreationProgressItem[] = [
      ...members.map((m) => ({
        id: m.id,
        name: m.displayName || t("Workforce.wizard.unnamed", "Unnamed"),
        status: "pending" as const,
      })),
      {
        id: Workforce_PROGRESS_ID,
        name: t("Workforce.wizard.settingUpBoard", "Assembling task force…"),
        status: "pending" as const,
      },
    ];
    setCreationProgress(progressItems);

    // Mutable copy of members to record returned agentIds
    const resolvedMembers = members.map((m) => ({ ...m }));

    // Create new agents sequentially
    for (let i = 0; i < resolvedMembers.length; i++) {
      const member = resolvedMembers[i]!;

      // Skip members already completed in a previous attempt
      if (completedIds.has(i)) continue;

      if (member.mode === "existing") {
        // Already has an agentId — mark as done immediately
        updateProgress(member.id, { status: "done" });
        continue;
      }

      updateProgress(member.id, { status: "creating" });

      try {
        const result = await setupAgent.mutateAsync({
          name: member.displayName,
          systemPrompt: member.systemPrompt,
          provider: member.provider || undefined,
          model: member.model || undefined,
          apiKey: member.apiKey || undefined,
          deploy: true,
        });

        resolvedMembers[i] = { ...member, agentId: result.agentId };
        updateProgress(member.id, { status: "done" });
      } catch (err) {
        const message =
          err instanceof Error ? err.message : String(err);
        updateProgress(member.id, { status: "error", error: message });
        toast.error(
          t("Workforce.wizard.agentError", "Failed to create {{name}}: {{error}}", {
            name: member.displayName,
            error: message,
          }),
        );
        setIsCreating(false);
        creatingRef.current = false;
        return; // Stop on error
      }
    }

    // Build group configuration
    updateProgress(Workforce_PROGRESS_ID, { status: "creating" });

    const groupMembers: GroupMember[] = resolvedMembers.map((m, i) => ({
      agentId: m.agentId,
      displayName: m.displayName,
      speakingOrder: i + 1,
      role: m.role || null,
      memberType: "AGENT" as const,
    }));

    try {
      let config;
      if (selectedTemplateObj) {
        config = buildGroupFromTemplate(selectedTemplateObj, {
          name: boardName,
          members: groupMembers,
        });
        config.description = boardDescription;
      } else {
        // Custom configuration
        config = {
          name: boardName,
          description: boardDescription,
          members: groupMembers,
          moderatorAgentId: null,
          style: "CUSTOM" as const,
          maxRounds: 1,
          phases: null,
          protocol: {
            agentTimeoutSeconds: DEFAULT_AGENT_TIMEOUT_SECONDS,
            onAgentFailure: "SKIP" as const,
            maxRetries: 2,
            onMemberUnavailable: "SKIP" as const,
          },
        };
      }

      const result = await createGroup.mutateAsync(config);
      updateProgress(Workforce_PROGRESS_ID, { status: "done" });

      // Parse the returned location to get the group ID
      const { id: newGroupId } = parseGroupResourceUri(
        result.location,
      );

      toast.success(
        t("Workforce.wizard.success", "Task Force assembled successfully!"),
      );
      setIsCreating(false);
      creatingRef.current = false;

      navigate(`/workforce/${newGroupId}?version=1`);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      updateProgress(Workforce_PROGRESS_ID, {
        status: "error",
        error: message,
      });
      toast.error(
        t("Workforce.wizard.groupError", "Failed to assemble task force: {{error}}", {
          error: message,
        }),
      );
      setIsCreating(false);
      creatingRef.current = false;
    }
  }, [
    members,
    t,
    updateProgress,
    setupAgent,
    selectedTemplateObj,
    boardName,
    boardDescription,
    createGroup,
    navigate,
    creationProgress,
  ]);

  // ─── Render ─────────────────────────────────────────────────────────────

  return (
    <div className="ms-auto me-auto max-w-3xl p-5 sm:p-8">
      {/* Close / cancel wizard — back to dashboard */}
      <div className="flex items-center justify-end mb-4">
        <Link
          to="/workforce"
          className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("Workforce.wizard.cancel", "Cancel")}
        >
          <X className="h-5 w-5" />
        </Link>
      </div>

      {/* Step indicator */}
      <StepIndicator steps={steps} currentStep={currentStep} />

      {/* Step content with transition */}
      <div className="mt-8 relative">
        {/* Step 0: Template picker */}
        <div
          className={cn(
            "transition-all duration-300",
            currentStep === 0
              ? "translate-y-0 opacity-100"
              : "pointer-events-none absolute -translate-y-4 opacity-0",
          )}
        >
          {currentStep === 0 && (
            <TemplatePicker
              selected={selectedTemplate}
              onSelect={handleTemplateSelect}
            />
          )}
        </div>

        {/* Step 1: Team builder */}
        <div
          className={cn(
            "transition-all duration-300",
            currentStep === 1
              ? "translate-y-0 opacity-100"
              : "pointer-events-none absolute -translate-y-4 opacity-0",
          )}
        >
          {currentStep === 1 && (
            <TeamBuilder
              boardName={boardName}
              onBoardNameChange={setBoardName}
              boardDescription={boardDescription}
              onBoardDescriptionChange={setBoardDescription}
              members={members}
              onMembersChange={setMembers}
            />
          )}
        </div>

        {/* Step 2: Review & launch */}
        <div
          className={cn(
            "transition-all duration-300",
            currentStep === 2
              ? "translate-y-0 opacity-100"
              : "pointer-events-none absolute -translate-y-4 opacity-0",
          )}
        >
          {currentStep === 2 && (
            <ReviewLaunch
              boardName={boardName}
              boardDescription={boardDescription}
              style={resolvedStyle}
              members={members}
              isCreating={isCreating}
              creationProgress={creationProgress}
              onCreateClick={handleCreate}
            />
          )}
        </div>
      </div>

      {/* Navigation buttons */}
      {!isCreating && (
        <div className="mt-8 flex justify-between">
          <Button
            variant="outline"
            onClick={() =>
              currentStep === 0
                ? navigate("/workforce")
                : setCurrentStep((s) => s - 1)
            }
          >
            {t("Workforce.wizard.back", "Back")}
          </Button>

          {currentStep < 2 && (
            <Button
              variant="primary"
              disabled={!canProceed}
              onClick={() => setCurrentStep((s) => s + 1)}
            >
              {t("Workforce.wizard.next", "Next")}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

export { WorkforceWizard };
