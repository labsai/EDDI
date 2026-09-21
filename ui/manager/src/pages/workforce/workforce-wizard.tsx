import { useState, useCallback, useMemo, useRef, useEffect } from "react";
import { useNavigate, Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { getErrorMessage } from "@/lib/api-client";
import { Button } from "@/components/ui/button";
import { useSetupAgent } from "@/hooks/use-agent-setup";
import { useCreateGroup, useAvailableStyles } from "@/hooks/use-groups";
import { useTemplates } from "@/hooks/use-templates";
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
  memberIssue,
  effectiveLlm,
  starterPrompt,
  INITIAL_LLM_DEFAULTS,
  type LlmDefaults,
} from "@/components/workforce/wizard/member-validation";
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
  systemPrompt = "",
): MemberSlot {
  return {
    id: crypto.randomUUID(),
    displayName,
    role,
    mode: "new",
    agentId: "",
    createdAgentId: "",
    systemPrompt,
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
  /** style/maxRounds from an applied saved template, so the custom build path
   *  reproduces how that template actually runs rather than defaulting. */
  const [savedTemplateConfig, setSavedTemplateConfig] = useState<{
    style: DiscussionStyle;
    maxRounds: number;
  } | null>(null);
  /** Provider/model/key every new advisor inherits unless it sets its own. */
  const [llmDefaults, setLlmDefaults] = useState<LlmDefaults>(INITIAL_LLM_DEFAULTS);
  /** Validation messages stay hidden until the user first tries to continue. */
  const [showTeamErrors, setShowTeamErrors] = useState(false);
  /** Bumped on each failed Next so the effect below moves focus to the first flagged field. */
  const [focusFlaggedRequest, setFocusFlaggedRequest] = useState(0);
  const teamStepRef = useRef<HTMLDivElement>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [creationProgress, setCreationProgress] = useState<
    CreationProgressItem[]
  >([]);

  // Filtered by backend support — see TemplatePicker. Resolved here as well so
  // a `?template=` deep link cannot apply a template the picker is hiding.
  const availableStyles = useAvailableStyles();
  const templates = useMemo(
    () => getGroupTemplates(t).filter((tpl) => availableStyles.includes(tpl.style)),
    [t, availableStyles],
  );

  // ─── Derived ────────────────────────────────────────────────────────────

  const selectedTemplateObj = useMemo(
    () => templates.find((tpl) => tpl.key === selectedTemplate) ?? null,
    [templates, selectedTemplate],
  );

  const resolvedStyle: DiscussionStyle | null =
    selectedTemplateObj?.style ??
    savedTemplateConfig?.style ??
    (selectedTemplate === "custom" ? "CUSTOM" : null);

  const steps = useMemo(
    () => [
      { label: t("Workforce.wizard.stepTemplate", "Template") },
      { label: t("Workforce.wizard.stepTeam", "Team") },
      { label: t("Workforce.wizard.stepReview", "Review") },
    ],
    [t],
  );

  // ─── Navigation guards ─────────────────────────────────────────────────

  const teamComplete = useMemo(
    () =>
      boardName.trim().length > 0 &&
      members.length > 0 &&
      members.every((m) => memberIssue(m, llmDefaults) === null),
    [boardName, members, llmDefaults],
  );

  // Step 1's Next stays clickable even when the team is incomplete: a disabled
  // button gave no clue which field was missing, and the one that actually
  // blocked creation — the system prompt — is hidden inside a collapsed card.
  // Clicking now reveals the messages instead of advancing.
  const canProceed = useMemo(() => {
    if (currentStep === 0) return selectedTemplate !== null;
    if (currentStep === 1) return true;
    return false; // Step 2 uses CreateButton inside ReviewLaunch
  }, [currentStep, selectedTemplate]);

  const handleNext = useCallback(() => {
    if (currentStep === 1 && !teamComplete) {
      setShowTeamErrors(true);
      setFocusFlaggedRequest((n) => n + 1);
      return;
    }
    setShowTeamErrors(false);
    setCurrentStep((s) => s + 1);
  }, [currentStep, teamComplete]);

  const handleBack = useCallback(() => {
    if (currentStep === 0) {
      navigate("/workforce");
      return;
    }
    // Leaving the team step drops its "you tried to continue" state, so a
    // freshly picked template does not arrive on the team step pre-flagged.
    // Leaving the review step drops a previous attempt's progress list, so
    // after fixing the cause the summary shows again instead of the failure —
    // the advisors that attempt did create are kept via their agentId and
    // reused, see handleCreate.
    if (currentStep === 1) setShowTeamErrors(false);
    if (currentStep === 2) setCreationProgress([]);
    setCurrentStep((s) => s - 1);
  }, [currentStep, navigate]);

  // Move focus to the first flagged control after a failed Next. Read from the
  // DOM rather than threaded through props: the flagged field can be any of a
  // dozen inputs across the member cards, and `aria-invalid` (or
  // `data-invalid` on the two composite pickers) already marks exactly it. The
  // card holding it is derived-open in the same render, so it is present here.
  useEffect(() => {
    if (focusFlaggedRequest === 0) return;
    const flagged = teamStepRef.current?.querySelector<HTMLElement>(
      '[aria-invalid="true"], [data-invalid="true"]',
    );
    if (!flagged) return;
    const target = flagged.matches("input, textarea, select, button")
      ? flagged
      : (flagged.querySelector<HTMLElement>("input, textarea, select, button") ?? flagged);
    target.scrollIntoView?.({ block: "center" });
    target.focus();
  }, [focusFlaggedRequest]);

  // ─── Template selection handler ─────────────────────────────────────────

  const handleTemplateSelect = useCallback(
    (key: string) => {
      setSelectedTemplate(key);
      // A manual pick replaces whatever a ?template=<uuid> deep link applied.
      // Without this, arriving via a saved template and then choosing another
      // card — or "Custom" — kept that template's style and maxRounds, so the
      // created group silently ran with settings the user had moved away from.
      // The deep-link effect sets this AFTER its own selection, so the
      // saved-template path is unaffected.
      setSavedTemplateConfig(null);

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
      // A template is a quick start, so it seeds each role's system prompt
      // too — editable in the card, and shown on the review step. Without it,
      // "Advisory Board" meant writing five prompts before anything could be
      // created.
      setMembers(
        tpl.roles.map((r) =>
          emptySlot(r.displayName, r.role ?? "", starterPrompt(r.displayName, r.role ?? "", t)),
        ),
      );
    },
    [templates, t],
  );

  /**
   * Apply a `?template=` deep link once, on mount.
   *
   * Three places link here with a template pre-chosen — the onboarding hero's
   * template cards and its Custom card, and Dashboard > Templates > Use — and the
   * wizard ignored the parameter entirely, so every one of them landed on step 0
   * with nothing selected and Next disabled. The user's choice was silently
   * discarded.
   *
   * Two different id spaces arrive here: built-in templates are keyed by `key`
   * ("advisory-board"), while templates the user saved are keyed by a
   * `crypto.randomUUID()` `id` and live in localStorage. Resolve both, and ignore
   * an unknown value rather than leaving the wizard in a half-selected state.
   */
  const [searchParams] = useSearchParams();
  const savedTemplates = useTemplates();
  const appliedDeepLink = useRef(false);

  useEffect(() => {
    if (appliedDeepLink.current) return;
    const requested = searchParams.get("template");
    if (!requested) return;
    appliedDeepLink.current = true;

    if (requested === "custom" || templates.some((tpl) => tpl.key === requested)) {
      handleTemplateSelect(requested);
      return;
    }

    const saved = savedTemplates.templates.find((tpl) => tpl.id === requested);
    if (!saved) return; // unknown id — leave the picker untouched

    setSelectedTemplate("custom");
    setSavedTemplateConfig({ style: saved.style, maxRounds: saved.maxRounds });
    setBoardName(saved.name);
    setBoardDescription(saved.description);
    setMembers(
      saved.members.length > 0
        ? saved.members.map((m) =>
            emptySlot(m.displayName, m.role, starterPrompt(m.displayName, m.role, t)),
          )
        : [emptySlot(), emptySlot()],
    );
  }, [searchParams, templates, savedTemplates.templates, handleTemplateSelect, t]);

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

      // An existing agent the user picked, or a new advisor a previous attempt
      // already provisioned (its id was written into `createdAgentId` below) —
      // nothing to create. The old "resume" logic remembered only which *rows*
      // had finished, not the ids they were given, so a retry skipped the setup
      // call and then built the group with agentId "" for them.
      const alreadyProvisioned =
        member.mode === "existing" ? member.agentId : member.createdAgentId;
      if (alreadyProvisioned) {
        resolvedMembers[i] = { ...member, agentId: alreadyProvisioned };
        updateProgress(member.id, { status: "done" });
        continue;
      }

      updateProgress(member.id, { status: "creating" });

      try {
        const llm = effectiveLlm(member, llmDefaults);
        const result = await setupAgent.mutateAsync({
          name: member.displayName,
          systemPrompt: member.systemPrompt,
          provider: llm.provider || undefined,
          model: llm.model || undefined,
          apiKey: llm.apiKey || undefined,
          deploy: true,
        });

        resolvedMembers[i] = { ...member, agentId: result.agentId };
        // Persist the id so a retry after a later failure reuses this agent
        // instead of deploying a duplicate. createdAgentId, not agentId:
        // the latter belongs to the "use an existing agent" picker.
        setMembers((prev) =>
          prev.map((m) =>
            m.id === member.id ? { ...m, createdAgentId: result.agentId } : m,
          ),
        );
        updateProgress(member.id, { status: "done" });
      } catch (err) {
        // getErrorMessage keeps the backend's sentence ("System prompt is
        // required") and appends the status, which is what the rest of the
        // app shows for API failures. This used to be `err instanceof Error ?
        // err.message : String(err)` against a plain-object ApiError, which
        // rendered every rejection as "[object Object]" — ApiClient now throws
        // a real Error (ApiClientError), so that shape is safe again elsewhere.
        const message = getErrorMessage(err);
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
          // A saved template carries its own style and round count, and the
          // Templates panel displays them — hardcoding CUSTOM/1 here meant
          // "Use template" recreated only the member list and silently changed
          // how the discussion runs.
          style: savedTemplateConfig?.style ?? ("CUSTOM" as const),
          maxRounds: savedTemplateConfig?.maxRounds ?? 1,
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
      const message = getErrorMessage(err);
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
    llmDefaults,
    // Read in the custom build path to reproduce a saved template's style and
    // round count. Omitting it would let this callback close over a stale value.
    savedTemplateConfig,
  ]);

  // ─── Render ─────────────────────────────────────────────────────────────

  // The outer element is the SCROLLER. `workforce-main` is deliberately
  // `overflow-hidden` — every Workforce page brings its own scroll container
  // (see workforce-analytics / workforce-history) — and this page had none, so
  // once the step content grew past the viewport the rest was simply
  // unreachable: no page scrollbar, no wheel response, and the wizard's own
  // Back/Next controls sat below the cut.
  return (
    <div className="h-full min-h-0 overflow-y-auto">
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
          ref={teamStepRef}
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
              llmDefaults={llmDefaults}
              onLlmDefaultsChange={setLlmDefaults}
              showErrors={showTeamErrors}
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
              llmDefaults={llmDefaults}
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
          <Button variant="outline" onClick={handleBack}>
            {t("Workforce.wizard.back", "Back")}
          </Button>

          {currentStep < 2 && (
            <Button
              variant="primary"
              disabled={!canProceed}
              onClick={handleNext}
            >
              {t("Workforce.wizard.next", "Next")}
            </Button>
          )}
        </div>
      )}
      </div>
    </div>
  );
}

export { WorkforceWizard };
