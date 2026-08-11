import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Users,
  Brain,
  Rocket,
  RefreshCw,
  Plus,
  Trash2,
  Sparkles,
  CheckCircle2,
  ExternalLink,
  ChevronDown,
  Wand2,
  UserPlus,
  Link2,
  Star,
  AlertTriangle,
  Pencil,
  HandMetal,
  UserCheck,
  Clock,
} from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { useCreateGroup, useAvailableStyles } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { styleLabel, styleDisplay } from "@/lib/discussion-styles";
import { uncoveredRolePhases } from "@/lib/group-config";
import {
  type DiscussionStyle,
  type GroupMember,
  type AgentGroupConfiguration,
  type OnHumanTimeout,
} from "@/lib/api/groups";
import type { GroupHitlConfig } from "@/lib/api/hitl";
import {
  getStylePhases,
  applyApprovalPhases,
  DEFAULT_GROUP_HITL_CONFIG,
  isValidIsoDuration,
  requiresApprovalTimeout,
} from "@/lib/hitl-config";
import { granularityLabel } from "@/lib/hitl-labels";
import { parseResourceUri } from "@/lib/api/agents";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import {
  getGroupTemplates,
  DEFAULT_AGENT_TIMEOUT_SECONDS,
  type GroupTemplate,
} from "@/lib/group-templates";
import {
  setupAgent,
  LLM_PROVIDERS,
  type SetupAgentRequest,
} from "@/lib/api/agent-setup";

/* ================================================================
   Types & Constants
   ================================================================ */

interface MemberSlot extends GroupMember {
  /** Whether this slot should create a new agent vs use existing */
  mode: "existing" | "new";
  /** New agent creation fields */
  systemPrompt: string;
  provider: string;
  model: string;
  apiKey: string;
  /** Whether this agent has been created successfully */
  created: boolean;
  /** Set while creating */
  creating: boolean;
}

interface WizardState {
  name: string;
  description: string;
  style: DiscussionStyle;
  maxRounds: number;
  members: MemberSlot[];
  moderator: MemberSlot | null;
  /** Human-in-the-loop approval settings. */
  hitlEnabled: boolean;
  hitl: GroupHitlConfig;
  /** Names of the phases that require human approval (the pause trigger). */
  approvalPhases: string[];
  /**
   * HUMAN member turn policy (I6) — only meaningful (and only sent) when at
   * least one member is `memberType: "HUMAN"`. Empty `humanTurnTimeout` = wait
   * indefinitely.
   */
  humanTurnTimeout: string;
  humanOnTimeout: OnHumanTimeout;
}

/** Config-step validity gate for the HITL settings. */
function isHitlConfigValid(state: WizardState): boolean {
  if (!state.hitlEnabled) return true;
  if (state.approvalPhases.length === 0) return false;
  if (requiresApprovalTimeout(state.hitl.timeoutPolicy)) {
    return !!state.hitl.approvalTimeout && isValidIsoDuration(state.hitl.approvalTimeout);
  }
  return true;
}

function createEmptySlot(index: number, displayName?: string, role?: string | null): MemberSlot {
  return {
    agentId: "",
    displayName: displayName || "",
    speakingOrder: index + 1,
    role: role || null,
    memberType: "AGENT",
    mode: "new",
    systemPrompt: "",
    provider: "anthropic",
    model: "",
    apiKey: "",
    created: false,
    creating: false,
  };
}

function createModeratorSlot(): MemberSlot {
  return {
    agentId: "",
    displayName: "Moderator",
    speakingOrder: 0,
    role: "MODERATOR",
    memberType: "AGENT",
    mode: "new",
    systemPrompt: "You are a skilled moderator. Synthesize the group's discussion into a clear, balanced summary that captures key insights, areas of agreement, and remaining disagreements.",
    provider: "anthropic",
    model: "",
    apiKey: "",
    created: false,
    creating: false,
  };
}

/**
 * Whether a slot still needs an LLM agent provisioned before the group can be
 * created.
 *
 * Only an AGENT member ever does. A GROUP member points at an existing nested
 * group and a HUMAN member at a principal id; neither has a "create new" flow in
 * the card UI, so `mode` is meaningless for them and merely whatever the last
 * transition happened to leave behind. Keying purely on `mode === "new"` meant a
 * GROUP member with no group picked got a real deployed agent created for it,
 * whose id was then submitted as the nested group's config id.
 *
 * The `memberType` guard is the one that makes this safe: it holds no matter how
 * `mode` was set, so no future transition can reintroduce the same bug.
 */
function needsAgentCreation(slot: MemberSlot): boolean {
  return slot.memberType === "AGENT" && slot.mode === "new" && !slot.created && !slot.agentId;
}

const INITIAL_STATE: WizardState = {
  name: "",
  description: "",
  style: "ROUND_TABLE",
  maxRounds: 2,
  members: [],
  moderator: null,
  hitlEnabled: false,
  hitl: DEFAULT_GROUP_HITL_CONFIG,
  approvalPhases: [],
  humanTurnTimeout: "",
  humanOnTimeout: "SKIP_TURN",
};

const STEPS = [
  { id: "template", icon: Sparkles },
  { id: "config", icon: Brain },
  { id: "members", icon: Users },
  { id: "review", icon: Rocket },
] as const;

/** Style-specific accent colors */
const STYLE_COLORS: Record<DiscussionStyle, { bg: string; border: string; text: string; accent: string }> = {
  ROUND_TABLE: { bg: "bg-amber-500/10", border: "border-amber-500/30", text: "text-amber-600 dark:text-amber-400", accent: "bg-amber-500" },
  PEER_REVIEW: { bg: "bg-teal-500/10", border: "border-teal-500/30", text: "text-teal-600 dark:text-teal-400", accent: "bg-teal-500" },
  DEVIL_ADVOCATE: { bg: "bg-rose-500/10", border: "border-rose-500/30", text: "text-rose-600 dark:text-rose-400", accent: "bg-rose-500" },
  DELPHI: { bg: "bg-violet-500/10", border: "border-violet-500/30", text: "text-violet-600 dark:text-violet-400", accent: "bg-violet-500" },
  DEBATE: { bg: "bg-indigo-500/10", border: "border-indigo-500/30", text: "text-indigo-600 dark:text-indigo-400", accent: "bg-indigo-500" },
  TASK_FORCE: { bg: "bg-orange-500/10", border: "border-orange-500/30", text: "text-orange-600 dark:text-orange-400", accent: "bg-orange-500" },
  NEGOTIATION: { bg: "bg-emerald-500/10", border: "border-emerald-500/30", text: "text-emerald-600 dark:text-emerald-400", accent: "bg-emerald-500" },
  CUSTOM: { bg: "bg-secondary/20", border: "border-border", text: "text-foreground", accent: "bg-muted-foreground" },
};

/* ================================================================
   Main Wizard Component
   ================================================================ */

export function GroupWizardPage() {
  const { t } = useTranslation();
  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>(INITIAL_STATE);
  const [resultId, setResultId] = useState<string | null>(null);
  const [resultVersion, setResultVersion] = useState<number>(1);
  const [creationProgress, setCreationProgress] = useState<string | null>(null);
  const [isBatchCreating, setIsBatchCreating] = useState(false);

  const createMutation = useCreateGroup();

  const isCreating = createMutation.isPending || isBatchCreating;
  const step = STEPS[currentStep];

  const update = useCallback(
    (patch: Partial<WizardState>) => setState((s) => ({ ...s, ...patch })),
    [],
  );

  function selectTemplate(tmpl: GroupTemplate) {
    update({
      name: tmpl.name,
      description: tmpl.description,
      style: tmpl.style,
      maxRounds: tmpl.maxRounds,
      members: tmpl.roles.map((r, i) => createEmptySlot(i, r.displayName, r.role)),
      moderator: tmpl.moderatorSuggested ? createModeratorSlot() : null,
      approvalPhases: [],
    });
    setCurrentStep(1);
  }

  function canProceed(): boolean {
    if (!step) return false;
    switch (step.id) {
      case "template":
        return true;
      case "config":
        return state.name.trim().length > 0 && isHitlConfigValid(state);
      case "members": {
        if (state.members.length < 2) return false;
        // Every member must have a displayName, plus an identity appropriate to
        // its type. HUMAN needs a principal id and GROUP a nested group id, both
        // typed or picked directly — only an AGENT may go without one, because
        // only an AGENT can still be created on the way out (`needsAgentCreation`).
        const membersValid = state.members.every((m) => {
          if (!m.displayName.trim()) return false;
          if (m.memberType === "HUMAN" || m.memberType === "GROUP") return !!m.agentId.trim();
          return !!m.agentId || m.mode === "new";
        });
        if (!membersValid) return false;
        // An explicitly-typed turn timeout must be a duration the backend can
        // parse — an unparseable one is silently treated as "wait indefinitely"
        // there, which is not what typing "abc" or "3h" (missing the P/T grammar) meant.
        if (state.humanTurnTimeout.trim() && !isValidIsoDuration(state.humanTurnTimeout)) {
          return false;
        }
        return true;
      }
      case "review":
        return true;
      default:
        return false;
    }
  }

  function handleNext() {
    if (currentStep < STEPS.length - 1) setCurrentStep(currentStep + 1);
  }

  function handleBack() {
    if (currentStep > 0) setCurrentStep(currentStep - 1);
  }

  async function handleCreate() {
    setIsBatchCreating(true);
    const updatedMembers = [...state.members];
    let updatedModerator = state.moderator ? { ...state.moderator } : null;

    // --- Phase 1: Auto-create all uncreated "new" agents ---
    const uncreatedMembers = updatedMembers
      .map((m, i) => ({ slot: m, index: i }))
      .filter(({ slot }) => needsAgentCreation(slot));

    for (const { slot, index } of uncreatedMembers) {
      setCreationProgress(t("groupWizard.creatingSlot", { name: slot.displayName }));
      try {
        const result = await setupAgent({
          name: `${state.name} — ${slot.displayName}`.trim(),
          systemPrompt: slot.systemPrompt || `You are ${slot.displayName}${slot.role ? `, a ${slot.role} expert` : ""}. Provide clear, actionable insights.`,
          provider: slot.provider || "anthropic",
          model: slot.model || (LLM_PROVIDERS.find(p => p.id === (slot.provider || "anthropic"))?.defaultModel ?? "claude-sonnet-4-6"),
          apiKey: slot.apiKey || undefined,
          deploy: true,
        });
        updatedMembers[index] = { ...updatedMembers[index]!, agentId: result.agentId, created: true };
      } catch (err) {
        toast.error(t("groupWizard.agentCreateFailed", {
          error: err instanceof Error ? err.message : String(err),
        }));
        setIsBatchCreating(false);
        setCreationProgress(null);
        setState((s) => ({ ...s, members: updatedMembers }));
        return; // Stop on first failure
      }
    }

    // Auto-create moderator if needed
    if (updatedModerator && needsAgentCreation(updatedModerator)) {
      setCreationProgress(t("groupWizard.creatingModerator"));
      try {
        const result = await setupAgent({
          name: `${state.name} — Moderator`.trim(),
          systemPrompt: updatedModerator.systemPrompt || "You are a skilled moderator. Synthesize the discussion into a clear, balanced summary.",
          provider: updatedModerator.provider || "anthropic",
          model: updatedModerator.model || (LLM_PROVIDERS.find(p => p.id === (updatedModerator!.provider || "anthropic"))?.defaultModel ?? "claude-sonnet-4-6"),
          apiKey: updatedModerator.apiKey || undefined,
          deploy: true,
        });
        updatedModerator = { ...updatedModerator, agentId: result.agentId, created: true };
      } catch (err) {
        toast.error(t("groupWizard.agentCreateFailed", {
          error: err instanceof Error ? err.message : String(err),
        }));
        setIsBatchCreating(false);
        setCreationProgress(null);
        return;
      }
    }

    // Update state with created agents
    setState((s) => ({ ...s, members: updatedMembers, moderator: updatedModerator }));

    // --- Phase 2: Create the group ---
    setCreationProgress(t("groupWizard.creatingGroup"));
    const config: AgentGroupConfiguration = {
      name: state.name,
      description: state.description,
      members: updatedMembers
        .filter((m) => m.agentId || m.displayName)
        .map((m) => ({
          agentId: m.agentId,
          displayName: m.displayName,
          speakingOrder: m.speakingOrder,
          role: m.role,
          memberType: m.memberType,
        })),
      moderatorAgentId: updatedModerator?.agentId || null,
      style: state.style,
      maxRounds: state.maxRounds,
      // Materialize phases with per-phase approval flags only when HITL is on;
      // otherwise leave null so the backend expands the preset at runtime.
      phases:
        state.hitlEnabled && state.approvalPhases.length > 0
          ? applyApprovalPhases(getStylePhases(state.style, state.maxRounds), state.approvalPhases)
          : null,
      protocol: {
        agentTimeoutSeconds: DEFAULT_AGENT_TIMEOUT_SECONDS,
        onAgentFailure: "SKIP",
        maxRetries: 2,
        onMemberUnavailable: "SKIP",
      },
      hitlConfig: state.hitlEnabled
        ? {
            approvalTimeout: state.hitl.approvalTimeout?.trim() || null,
            timeoutPolicy: state.hitl.timeoutPolicy,
            // TASK granularity only applies to TASK_FORCE (the only style with an
            // EXECUTE phase); force PHASE elsewhere so it can't be inert-configured.
            granularity: state.style === "TASK_FORCE" ? state.hitl.granularity : "PHASE",
            onTaskRejection: state.hitl.onTaskRejection,
          }
        : undefined,
      // Only sent when a HUMAN member actually exists — an absent
      // humanMemberConfig on a group with no human members is the backend's own
      // "wait indefinitely" default, so there is nothing to configure otherwise.
      humanMemberConfig: updatedMembers.some((m) => m.memberType === "HUMAN")
        ? {
            turnTimeout: state.humanTurnTimeout.trim() || null,
            onTimeout: state.humanOnTimeout,
          }
        : undefined,
    };

    createMutation.mutate(config, {
      onSuccess: (data) => {
        toast.success(t("groupWizard.success"));
        // Parse the location URI to extract the actual group ID + version
        let id = "new";
        let version = 1;
        try {
          const location = (data as { location?: string })?.location;
          if (location) {
            const parsed = parseResourceUri(location);
            id = parsed.id;
            version = parsed.version;
          }
        } catch {
          // fallback — use raw data if parse fails
          id = typeof data === "string" ? data : (data as { id?: string })?.id ?? "new";
        }
        setResultId(id);
        setResultVersion(version);
        setIsBatchCreating(false);
        setCreationProgress(null);
      },
      onError: () => {
        toast.error(t("common.error"));
        setIsBatchCreating(false);
        setCreationProgress(null);
      },
    });
  }

  // ---- Success state ----
  if (resultId) {
    return (
      <div className="mx-auto max-w-2xl space-y-8">
        <div className="rounded-2xl border border-emerald-500/30 bg-card p-8 text-center shadow-lg">
          <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/10">
            <CheckCircle2 className="h-8 w-8 text-emerald-500" />
          </div>
          <h2 className="text-2xl font-bold text-foreground">
            {t("groupWizard.created")}
          </h2>
          <p className="mt-2 text-muted-foreground">
            {state.name} — {state.members.length} {t("groups.members")} · {styleLabel(state.style, t)}
          </p>

          <div className="mt-6 flex items-center justify-center gap-3">
            <Link
              to={`/manage/groups/${resultId}?version=${resultVersion}`}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98]"
            >
              {t("groupWizard.viewGroup")}
              <ExternalLink className="h-4 w-4" />
            </Link>
            <button
              onClick={() => {
                setState(INITIAL_STATE);
                setCurrentStep(0);
                setResultId(null);
                setResultVersion(1);
              }}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              {t("groupWizard.createAnother")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  const styleColors = STYLE_COLORS[state.style] || STYLE_COLORS.ROUND_TABLE;

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      {/* Header */}
      <div className="space-y-2">
        <Link
          to="/manage/groups"
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("groupWizard.backToGroups")}
        </Link>
        <h1 className="flex items-center gap-3 text-3xl font-bold text-foreground">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <Wand2 className="h-5 w-5 text-primary" />
          </div>
          {t("groupWizard.title")}
        </h1>
        <p className="text-muted-foreground">
          {t("groupWizard.subtitle")}
        </p>
      </div>

      {/* Step progress */}
      <div className="flex items-center gap-2" data-testid="group-wizard-steps">
        {STEPS.map((s, i) => {
          const Icon = s.icon;
          const isActive = i === currentStep;
          const isComplete = i < currentStep;
          return (
            <div key={s.id} className="flex flex-1 items-center gap-2">
              <button
                onClick={() => i < currentStep && setCurrentStep(i)}
                disabled={i > currentStep}
                className={cn(
                  "flex h-10 w-10 shrink-0 items-center justify-center rounded-full border-2 transition-all duration-300",
                  isActive &&
                    "border-primary bg-primary text-primary-foreground shadow-md shadow-primary/25 scale-110",
                  isComplete &&
                    "border-emerald-500 bg-emerald-500 text-white",
                  !isActive &&
                    !isComplete &&
                    "border-border bg-card text-muted-foreground",
                )}
              >
                {isComplete ? (
                  <Check className="h-4 w-4" />
                ) : (
                  <Icon className="h-4 w-4" />
                )}
              </button>
              {i < STEPS.length - 1 && (
                <div
                  className={cn(
                    "h-0.5 flex-1 rounded-full transition-colors duration-300",
                    isComplete ? "bg-emerald-500" : "bg-border",
                  )}
                />
              )}
            </div>
          );
        })}
      </div>

      {/* Step content */}
      <div className="rounded-xl border bg-card p-6 shadow-sm">
        {step?.id === "template" && <TemplateStep onSelect={selectTemplate} onSkip={() => setCurrentStep(1)} />}
        {step?.id === "config" && <ConfigStep state={state} onChange={update} />}
        {step?.id === "members" && <MembersStep state={state} onChange={update} styleColors={styleColors} />}
        {step?.id === "review" && <ReviewStep state={state} />}
      </div>

      {/* Navigation */}
      <div className="flex items-center justify-between">
        <button
          onClick={handleBack}
          disabled={currentStep === 0}
          className={cn(
            "inline-flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium transition-colors",
            currentStep === 0
              ? "invisible"
              : "text-muted-foreground hover:bg-secondary hover:text-foreground",
          )}
          data-testid="group-wizard-back"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("common.back", "Back")}
        </button>

        {step?.id !== "review" ? (
          <button
            onClick={handleNext}
            disabled={!canProceed()}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
            data-testid="group-wizard-next"
          >
            {t("common.next", "Next")}
            <ArrowRight className="h-4 w-4" />
          </button>
        ) : (
          <button
            onClick={() => handleCreate()}
            disabled={isCreating}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]"
            data-testid="group-wizard-create"
          >
            {isCreating ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" />
                {creationProgress || t("groupWizard.createGroup")}
              </>
            ) : (
              <>
                <Rocket className="h-4 w-4" />
                {t("groupWizard.createGroup")}
              </>
            )}
          </button>
        )}
      </div>
    </div>
  );
}

/* ================================================================
   Step 1: Choose Template
   ================================================================ */

function TemplateStep({
  onSelect,
  onSkip,
}: {
  onSelect: (tmpl: GroupTemplate) => void;
  onSkip: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.templateTitle")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.templateDesc")}
      </p>

      <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2" data-testid="template-grid">
        {getGroupTemplates(t).map((tmpl) => {
          const styleInfo = styleDisplay(tmpl.style, t);
          return (
            <button
              key={tmpl.key}
              onClick={() => onSelect(tmpl)}
              className="group relative flex flex-col items-start gap-3 rounded-xl border-2 border-border p-5 text-start transition-all duration-200 hover:border-primary/40 hover:shadow-md"
              data-testid={`template-${tmpl.key}`}
            >
              <div className="flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary/10 text-2xl">
                  {tmpl.icon}
                </div>
                <div>
                  <p className="text-sm font-semibold text-foreground">{tmpl.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {styleInfo?.icon} {styleInfo?.label}
                  </p>
                </div>
              </div>
              <p className="text-xs text-muted-foreground leading-relaxed line-clamp-2">
                {tmpl.description}
              </p>
              <div className="flex flex-wrap gap-1.5">
                  {tmpl.roles.slice(0, 4).map((r, ri) => (
                  <Badge key={ri} variant="secondary" className="text-[10px]">
                    {r.role}
                  </Badge>
                ))}
                {tmpl.roles.length > 4 && (
                  <Badge variant="outline" className="text-[10px]">
                    +{tmpl.roles.length - 4}
                  </Badge>
                )}
              </div>
            </button>
          );
        })}

        {/* Blank option */}
        <button
          onClick={onSkip}
          className="flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed border-border p-5 text-center transition-all duration-200 hover:border-primary/40 hover:shadow-md"
          data-testid="template-blank"
        >
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-muted text-muted-foreground">
            <Plus className="h-6 w-6" />
          </div>
          <div>
            <p className="text-sm font-semibold text-foreground">
              {t("groupWizard.startBlank")}
            </p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t("groupWizard.blankDesc")}
            </p>
          </div>
        </button>
      </div>
    </div>
  );
}

/* ================================================================
   Step 2: Group Configuration
   ================================================================ */

function ConfigStep({
  state,
  onChange,
}: {
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const { t } = useTranslation();
  // What the backend actually supports — a style it lacks would fail at save
  // time, and one it added would otherwise never appear here. The CURRENT
  // selection is always kept in the list (a template may have set it before the
  // styles response arrived) so the picker never hides its own selection.
  const { styles: availableStyles, backendLabels } = useAvailableStyles();
  const styleChoices = availableStyles.includes(state.style)
    ? availableStyles
    : [...availableStyles, state.style];

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.configTitle")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.configDesc")}
      </p>

      <div className="mt-6 space-y-5">
        {/* Name */}
        <div>
          <label htmlFor="gw-name" className="mb-1.5 block text-sm font-medium text-foreground">
            {t("common.name", "Name")} *
          </label>
          <input
            id="gw-name"
            type="text"
            value={state.name}
            onChange={(e) => onChange({ name: e.target.value })}
            placeholder={t("groupWizard.namePlaceholder")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="gw-name"
            autoFocus
          />
        </div>

        {/* Description */}
        <div>
          <label htmlFor="gw-desc" className="mb-1.5 block text-sm font-medium text-foreground">
            {t("common.description", "Description")}
          </label>
          <textarea
            id="gw-desc"
            value={state.description}
            onChange={(e) => onChange({ description: e.target.value })}
            placeholder={t("groupWizard.descPlaceholder")}
            rows={2}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y transition-shadow"
            data-testid="gw-description"
          />
        </div>

        {/* Discussion Style — visual cards */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("groups.discussionStyle", "Discussion Style")}
          </label>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {styleChoices.filter((s) => s !== "CUSTOM").map((s) => {
              const info = styleDisplay(s, t, backendLabels[s]);
              const colors = STYLE_COLORS[s] ?? STYLE_COLORS.CUSTOM;
              const selected = state.style === s;
              return (
                <button
                  key={s}
                  onClick={() => onChange({ style: s, approvalPhases: [] })}
                  className={cn(
                    "group relative flex flex-col items-start rounded-lg border-2 p-3 text-start transition-all",
                    selected
                      ? `${colors.border} ${colors.bg} shadow-sm`
                      : "border-border hover:border-primary/30"
                  )}
                  data-testid={`gw-style-${s}`}
                >
                  <div className="flex items-center gap-2">
                    <span className="text-lg">{info?.icon}</span>
                    <span className={cn("text-xs font-semibold", selected ? colors.text : "text-foreground")}>{info?.label}</span>
                  </div>
                  <p className="mt-1 text-[10px] text-muted-foreground leading-snug line-clamp-2">
                    {info?.flow}
                  </p>
                  {selected && (
                    <div className={cn("absolute inset-e-1.5 top-1.5 flex h-5 w-5 items-center justify-center rounded-full text-white", colors.accent)}>
                      <Check className="h-3 w-3" />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Max Rounds */}
        <div>
          <label htmlFor="gw-rounds" className="mb-1.5 block text-sm font-medium text-foreground">
            {t("groups.maxRounds", "Max Rounds")}
          </label>
          <div className="flex items-center gap-3">
            <input
              id="gw-rounds"
              type="range"
              min={1}
              max={10}
              value={state.maxRounds}
              onChange={(e) => {
                const nr = parseInt(e.target.value);
                // Keep approval-point selections valid when the phase list changes.
                const validNames = new Set(getStylePhases(state.style, nr).map((p) => p.name));
                onChange({
                  maxRounds: nr,
                  approvalPhases: state.approvalPhases.filter((n) => validNames.has(n)),
                });
              }}
              className="flex-1 accent-primary"
            />
            <span className="w-8 text-center text-sm font-bold text-foreground">{state.maxRounds}</span>
          </div>
        </div>

        {/* Human-in-the-Loop approval */}
        <HitlWizardSection state={state} onChange={onChange} />
      </div>
    </div>
  );
}

/* ================================================================
   Human-in-the-Loop configuration (part of Step 2)
   ================================================================ */

function HitlWizardSection({
  state,
  onChange,
}: {
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const { t } = useTranslation();
  const phases = useMemo(
    () => getStylePhases(state.style, state.maxRounds),
    [state.style, state.maxRounds],
  );
  const hitl = state.hitl;
  const patchHitl = (u: Partial<GroupHitlConfig>) => onChange({ hitl: { ...hitl, ...u } });

  function toggleEnabled() {
    if (state.hitlEnabled) {
      onChange({ hitlEnabled: false, approvalPhases: [] });
      return;
    }
    // Sensible default gate: approve the plan before execution (TASK_FORCE),
    // else approve the final synthesis.
    const defaultPhase =
      state.style === "TASK_FORCE"
        ? phases.find((p) => p.type === "EXECUTE")?.name
        : phases.find((p) => p.type === "SYNTHESIS")?.name;
    onChange({
      hitlEnabled: true,
      approvalPhases: defaultPhase ? [defaultPhase] : [],
    });
  }

  function togglePhase(name: string) {
    const set = new Set(state.approvalPhases);
    if (set.has(name)) set.delete(name);
    else set.add(name);
    onChange({ approvalPhases: [...set] });
  }

  const finiteTimeout = requiresApprovalTimeout(hitl.timeoutPolicy);
  const timeoutInvalid =
    finiteTimeout && (!hitl.approvalTimeout || !isValidIsoDuration(hitl.approvalTimeout));

  return (
    <div className="rounded-xl border border-border bg-secondary/10 p-4">
      {/* Header + master toggle */}
      <label className="flex items-start gap-3 cursor-pointer" htmlFor="gw-hitl-enable">
        <input
          id="gw-hitl-enable"
          type="checkbox"
          checked={state.hitlEnabled}
          onChange={toggleEnabled}
          className="mt-0.5 h-4 w-4 rounded border-input accent-primary"
          data-testid="gw-hitl-enable"
        />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
            <HandMetal className="h-4 w-4 text-amber-500" aria-hidden="true" />
            {t("groupWizard.hitlTitle", "Require human approval")}
          </span>
          <span className="mt-0.5 block text-xs text-muted-foreground">
            {t("groupWizard.hitlDesc", "Pause the discussion at chosen phases and wait for a human decision before continuing.")}
          </span>
        </span>
      </label>

      {state.hitlEnabled && (
        <div className="mt-4 space-y-4 ps-7">
          {/* Approval points */}
          <div>
            <p className="mb-1.5 text-xs font-medium text-foreground">
              {t("groupWizard.hitlApprovalPoints", "Pause for approval at")}
            </p>
            {phases.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                {t("groupWizard.hitlNoPhases", "This style has no preset phases to gate.")}
              </p>
            ) : (
              <div className="space-y-1.5" data-testid="gw-hitl-phases">
                {phases.map((p, i) => (
                  <label
                    key={p.name}
                    className="flex items-center gap-2 rounded-md border border-border bg-background px-3 py-1.5 text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={state.approvalPhases.includes(p.name)}
                      onChange={() => togglePhase(p.name)}
                      className="h-3.5 w-3.5 rounded border-input accent-primary"
                      data-testid={`gw-hitl-phase-${i}`}
                    />
                    <span className="flex-1 text-foreground">{p.name}</span>
                    <Badge variant="outline" className="text-[10px]">{p.type}</Badge>
                  </label>
                ))}
              </div>
            )}
            {state.approvalPhases.length === 0 && phases.length > 0 && (
              <p className="mt-1.5 flex items-center gap-1 text-xs text-amber-500">
                <AlertTriangle className="h-3 w-3" aria-hidden="true" />
                {t("groupWizard.hitlNoApprovalPoints", "Select at least one phase, or the discussion will never pause.")}
              </p>
            )}
          </div>

          {/* Timeout policy */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label htmlFor="gw-hitl-policy" className="mb-1 block text-xs font-medium text-foreground">
                {t("groupWizard.hitlTimeoutPolicy", "If no decision in time")}
              </label>
              <select
                id="gw-hitl-policy"
                value={hitl.timeoutPolicy ?? "WAIT_INDEFINITELY"}
                onChange={(e) => {
                  const policy = e.target.value as GroupHitlConfig["timeoutPolicy"];
                  const updates: Partial<GroupHitlConfig> = { timeoutPolicy: policy };
                  // Seed a valid default when switching to a finite policy (which
                  // requires a positive timeout) so the config isn't left invalid —
                  // mirrors the agent-level HITL editor.
                  if (
                    requiresApprovalTimeout(policy) &&
                    !(hitl.approvalTimeout && isValidIsoDuration(hitl.approvalTimeout))
                  ) {
                    updates.approvalTimeout = "PT15M";
                  }
                  patchHitl(updates);
                }}
                className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="gw-hitl-policy"
              >
                <option value="WAIT_INDEFINITELY">{t("hitl.timeoutWaitIndefinitely", "Wait Indefinitely")}</option>
                <option value="AUTO_APPROVE">{t("hitl.timeoutAutoApprove", "Auto-Approve")}</option>
                <option value="AUTO_REJECT">{t("hitl.timeoutAutoReject", "Auto-Reject")}</option>
                <option value="ABORT">{t("hitl.timeoutAbort", "Abort")}</option>
              </select>
            </div>

            {/* Approval timeout (only when a finite policy is chosen) */}
            {finiteTimeout && (
              <div>
                <label htmlFor="gw-hitl-timeout" className="mb-1 block text-xs font-medium text-foreground">
                  {t("groupWizard.hitlApprovalTimeout", "Approval timeout")}
                </label>
                <input
                  id="gw-hitl-timeout"
                  type="text"
                  value={hitl.approvalTimeout ?? ""}
                  onChange={(e) => patchHitl({ approvalTimeout: e.target.value || null })}
                  placeholder="PT15M"
                  className={cn(
                    "w-full rounded-lg border bg-background px-3 py-2 text-sm font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                    timeoutInvalid ? "border-destructive" : "border-input",
                  )}
                  data-testid="gw-hitl-timeout"
                  aria-invalid={timeoutInvalid}
                />
                <p className={cn("mt-1 text-[10px]", timeoutInvalid ? "text-destructive" : "text-muted-foreground")}>
                  {timeoutInvalid
                    ? t("groupWizard.hitlTimeoutInvalid", "Enter a positive ISO-8601 duration, e.g. PT15M or PT1H30M.")
                    : t("groupWizard.hitlTimeoutHint", "ISO-8601 duration, e.g. PT30S, PT15M, PT2H.")}
                </p>
              </div>
            )}
          </div>

          {/* Granularity + task-rejection — only meaningful for TASK_FORCE, which
              is the only style with a task-bearing (EXECUTE) phase. */}
          {state.style === "TASK_FORCE" && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label htmlFor="gw-hitl-granularity" className="mb-1 block text-xs font-medium text-foreground">
                {t("groupWizard.hitlGranularity", "Approval granularity")}
              </label>
              <select
                id="gw-hitl-granularity"
                value={hitl.granularity ?? "PHASE"}
                onChange={(e) => patchHitl({ granularity: e.target.value as GroupHitlConfig["granularity"] })}
                className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="gw-hitl-granularity"
              >
                <option value="PHASE">{t("hitl.granularityPhase", "Phase")}</option>
                <option value="TASK">{t("hitl.granularityTask", "Task")}</option>
              </select>
              <p className="mt-1 text-[10px] text-muted-foreground">
                {t("groupWizard.hitlGranularityHint", "Task-level approval applies to TASK_FORCE execution — approve or reject each task individually.")}
              </p>
            </div>

            {hitl.granularity === "TASK" && (
              <div>
                <label htmlFor="gw-hitl-rejection" className="mb-1 block text-xs font-medium text-foreground">
                  {t("groupWizard.hitlOnTaskRejection", "When a task is rejected")}
                </label>
                <select
                  id="gw-hitl-rejection"
                  value={hitl.onTaskRejection ?? "FAIL"}
                  onChange={(e) => patchHitl({ onTaskRejection: e.target.value as GroupHitlConfig["onTaskRejection"] })}
                  className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  data-testid="gw-hitl-rejection"
                >
                  <option value="FAIL">{t("hitl.rejectionFail", "Fail the task")}</option>
                  <option value="RETRY">{t("hitl.rejectionRetry", "Retry the task")}</option>
                </select>
              </div>
            )}
          </div>
          )}

          {/* Pin note */}
          {state.approvalPhases.length > 0 && (
            <p className="text-[10px] text-muted-foreground">
              {t("groupWizard.hitlPinNote", "Enabling approval points fixes this group's discussion phases ({{granularity}} granularity).", { granularity: granularityLabel(t, state.style === "TASK_FORCE" ? hitl.granularity : "PHASE") })}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/* ================================================================
   Step 3: Members — Card-based with per-slot Create New / Existing
   ================================================================ */

function MembersStep({
  state,
  onChange,
  styleColors,
}: {
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
  styleColors: typeof STYLE_COLORS[DiscussionStyle];
}) {
  const { t } = useTranslation();
  const { data: agentDescriptors, refetch: refetchAgents } = useAgentDescriptors(100);
  const agents = agentDescriptors ? groupAgentsByName(agentDescriptors) : [];

  function addMember() {
    onChange({
      members: [
        ...state.members,
        createEmptySlot(state.members.length),
      ],
    });
  }

  function removeMember(idx: number) {
    onChange({ members: state.members.filter((_, i) => i !== idx) });
  }

  function updateMember(idx: number, updates: Partial<MemberSlot>) {
    onChange({
      members: state.members.map((m, i) => (i === idx ? { ...m, ...updates } : m)),
    });
  }

  async function createAgentForSlot(idx: number) {
    const slot = state.members[idx];
    if (!slot || !slot.displayName.trim()) return;

    updateMember(idx, { creating: true });

    const req: SetupAgentRequest = {
      name: `${state.name} — ${slot.displayName}`.trim(),
      systemPrompt: slot.systemPrompt || `You are ${slot.displayName}${slot.role ? `, a ${slot.role} expert` : ""}. Provide clear, actionable insights from your domain perspective.`,
      provider: slot.provider || "anthropic",
      model: slot.model || (LLM_PROVIDERS.find(p => p.id === (slot.provider || "anthropic"))?.defaultModel ?? "claude-sonnet-4-6"),
      apiKey: slot.apiKey || undefined,
      deploy: true,
    };

    try {
      const result = await setupAgent(req);
      updateMember(idx, {
        agentId: result.agentId,
        created: true,
        creating: false,
      });
      toast.success(t("groupWizard.agentCreated", { name: slot.displayName }));
    } catch (err) {
      updateMember(idx, { creating: false });
      toast.error(t("groupWizard.agentCreateFailed", {
        error: err instanceof Error ? err.message : String(err),
      }));
    }
  }

  async function createModeratorAgent() {
    const mod = state.moderator;
    if (!mod) return;

    onChange({ moderator: { ...mod, creating: true } });

    const req: SetupAgentRequest = {
      name: `${state.name} — Moderator`.trim(),
      systemPrompt: mod.systemPrompt || "You are a skilled moderator. Synthesize the group's discussion into a clear, balanced summary that captures key insights, areas of agreement, and remaining disagreements.",
      provider: mod.provider || "anthropic",
      model: mod.model || (LLM_PROVIDERS.find(p => p.id === (mod.provider || "anthropic"))?.defaultModel ?? "claude-sonnet-4-6"),
      apiKey: mod.apiKey || undefined,
      deploy: true,
    };

    try {
      const result = await setupAgent(req);
      onChange({
        moderator: { ...mod, agentId: result.agentId, created: true, creating: false },
      });
      toast.success(t("groupWizard.moderatorCreated"));
    } catch (err) {
      onChange({ moderator: { ...mod, creating: false } });
      toast.error(t("groupWizard.agentCreateFailed", {
        error: err instanceof Error ? err.message : String(err),
      }));
    }
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-xl font-semibold text-foreground">
          {t("groupWizard.membersTitle")}
        </h2>
        <div className="flex items-center gap-2">
          <button
            onClick={() => refetchAgents()}
            className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
            title={t("groupWizard.refreshAgents")}
          >
            <RefreshCw className="h-3 w-3" />
          </button>
          <Badge variant="secondary" className="text-xs">
            {agents.length} {t("groupWizard.existingAgents")}
          </Badge>
        </div>
      </div>
      <p className="text-sm text-muted-foreground mb-4">
        {t("groupWizard.membersDesc")}
      </p>

      {/* Member Cards */}
      <div className="space-y-3 max-h-[500px] overflow-y-auto pe-1">
        {state.members.map((member, idx) => (
          <MemberCard
            key={idx}
            member={member}
            index={idx}
            agents={agents}
            styleColors={styleColors}
            onUpdate={(updates) => updateMember(idx, updates)}
            onRemove={() => removeMember(idx)}
            onCreateAgent={() => createAgentForSlot(idx)}
            t={t}
          />
        ))}
      </div>

      {/* Add member button */}
      <button
        onClick={addMember}
        className="flex w-full items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border p-3 mt-3 text-sm font-medium text-muted-foreground hover:border-primary/40 hover:text-primary transition-colors"
        data-testid="gw-add-member"
      >
        <Plus className="h-4 w-4" />
        {t("groupWizard.addMember")}
      </button>

      {/* Moderator Section */}
      <div className={cn("mt-5 rounded-xl border-2 p-4", styleColors.border, styleColors.bg)}>
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <Star className="h-4 w-4 text-primary" />
            <span className="text-sm font-semibold text-foreground">
              {t("groupWizard.moderator")}
            </span>
            <span className="text-xs text-muted-foreground">
              {t("groupWizard.moderatorHint")}
            </span>
          </div>
          {!state.moderator ? (
            <button
              onClick={() => onChange({ moderator: createModeratorSlot() })}
              className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary hover:bg-primary/20 transition-colors"
            >
              <UserPlus className="h-3 w-3" />
              {t("groupWizard.addModerator")}
            </button>
          ) : (
            <button
              onClick={() => onChange({ moderator: null })}
              className="rounded-md p-1 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
              title={t("groupWizard.removeModerator")}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {state.moderator && (
          <ModeratorCard
            moderator={state.moderator}
            agents={agents}
            onChange={(updates) => onChange({ moderator: { ...state.moderator!, ...updates } })}
            onCreateAgent={createModeratorAgent}
            t={t}
          />
        )}
      </div>

      {/* Member count indicator */}
      {state.members.length < 2 && (
        <p className="mt-3 text-xs text-amber-500 font-medium flex items-center gap-1.5">
          <AlertTriangle className="h-3.5 w-3.5" />
          {t("groupWizard.needMembers")}
        </p>
      )}

      {/* HUMAN member turn settings (I6) — only relevant once a HUMAN member exists. */}
      {state.members.some((m) => m.memberType === "HUMAN") && (
        <div className="mt-4 rounded-xl border border-primary/30 bg-primary/5 p-4" data-testid="human-turn-settings">
          <h3 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            <UserCheck className="h-3.5 w-3.5" />
            {t("groupWizard.humanTurnSettingsTitle", "Human member turn settings")}
          </h3>
          <p className="mt-1 text-xs text-muted-foreground">
            {t(
              "groupWizard.humanTurnSettingsHint",
              "The discussion pauses and waits when it's a human member's turn to speak. Choose what happens if they don't respond in time.",
            )}
          </p>
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 flex items-center gap-1 text-xs font-medium text-muted-foreground">
                <Clock className="h-3 w-3" />
                {t("groupWizard.humanTurnTimeoutLabel", "Turn timeout (ISO-8601 duration)")}
              </label>
              <input
                value={state.humanTurnTimeout}
                onChange={(e) => onChange({ humanTurnTimeout: e.target.value })}
                placeholder={t("groupWizard.humanTurnTimeoutPlaceholder", "e.g. PT24H — blank waits indefinitely")}
                className={cn(
                  "w-full rounded-lg border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                  state.humanTurnTimeout.trim() && !isValidIsoDuration(state.humanTurnTimeout)
                    ? "border-destructive"
                    : "border-input",
                )}
                data-testid="human-turn-timeout-input"
              />
              {state.humanTurnTimeout.trim() && !isValidIsoDuration(state.humanTurnTimeout) && (
                <p className="mt-1 text-[11px] text-destructive">
                  {t("groupWizard.humanTurnTimeoutInvalid", "Not a valid ISO-8601 duration, e.g. PT24H or P1D.")}
                </p>
              )}
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                {t("groupWizard.humanOnTimeoutLabel", "On timeout")}
              </label>
              <select
                value={state.humanOnTimeout}
                onChange={(e) => onChange({ humanOnTimeout: e.target.value as OnHumanTimeout })}
                disabled={!state.humanTurnTimeout.trim()}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
                data-testid="human-on-timeout-select"
              >
                <option value="SKIP_TURN">
                  {t("groupWizard.humanOnTimeoutSkip", "Skip their turn and continue")}
                </option>
                <option value="ABORT">
                  {t("groupWizard.humanOnTimeoutAbort", "Abort the discussion")}
                </option>
              </select>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ================================================================
   Member Card Component
   ================================================================ */

function MemberCard({
  member,
  index,
  agents,
  styleColors,
  onUpdate,
  onRemove,
  onCreateAgent,
  t,
}: {
  member: MemberSlot;
  index: number;
  agents: { id: string; name: string }[];
  styleColors: typeof STYLE_COLORS[DiscussionStyle];
  onUpdate: (updates: Partial<MemberSlot>) => void;
  onRemove: () => void;
  onCreateAgent: () => void;
  t: ReturnType<typeof useTranslation>["t"];
}) {
  const providerConfig = LLM_PROVIDERS.find((p) => p.id === member.provider);

  /**
   * Switching a member's type must clear its identity. `agentId` means a
   * DIFFERENT thing per type — a deployed agent, a nested group's config id, or
   * a human's principal id — so carrying it across a switch would submit, say,
   * an agent id as somebody's login. `created`/`creating` belong to the
   * agent-creation flow; leaving `created` true additionally suppressed the
   * whole card body, hiding the principal-id input a HUMAN member needs.
   */
  const selectMemberType = (memberType: NonNullable<MemberSlot["memberType"]>) => {
    if (member.memberType === memberType) return;
    onUpdate({
      memberType,
      agentId: "",
      created: false,
      creating: false,
      // Only AGENT can be created from here; GROUP and HUMAN are always a
      // reference to something that already exists. See `needsAgentCreation`.
      mode: memberType === "AGENT" ? "new" : "existing",
    });
  };

  return (
    <div
      className={cn(
        "rounded-xl border bg-card overflow-hidden transition-all",
        member.created
          ? "border-emerald-500/40"
          : member.mode === "new"
            ? styleColors.border
            : "border-border",
      )}
      data-testid={`member-card-${index}`}
    >
      {/* Card header */}
      <div className="flex items-center gap-3 px-4 py-3 bg-secondary/20">
        <div
          className={cn(
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white shadow-sm",
            member.created ? "bg-emerald-500" : hashColor(member.agentId || String(index))
          )}
        >
          {member.created ? <Check className="h-4 w-4" /> : getInitials(member.displayName || "?")}
        </div>

        <div className="flex-1 min-w-0 space-y-1">
          <div className={cn(
            "flex items-center gap-1.5 rounded-md border px-2 py-0.5 transition-colors",
            member.displayName.trim()
              ? "border-border/60 focus-within:border-primary/50"
              : "border-amber-500/50 bg-amber-500/5"
          )}>
            <Pencil className="h-3 w-3 shrink-0 text-muted-foreground/50" />
            <input
              value={member.displayName}
              onChange={(e) => onUpdate({ displayName: e.target.value })}
              className="w-full bg-transparent text-sm font-semibold text-foreground focus:outline-none placeholder:text-muted-foreground/60"
              placeholder={t("groupWizard.displayNamePlaceholder")}
              autoFocus={!member.displayName && !member.created}
              data-testid={`member-name-${index}`}
            />
          </div>
          <input
            value={member.role ?? ""}
            onChange={(e) => onUpdate({ role: e.target.value || null })}
            className="w-full bg-transparent text-xs text-muted-foreground ps-2.5 focus:outline-none placeholder:text-muted-foreground/50"
            placeholder={t("groupWizard.rolePlaceholder")}
          />
        </div>

        {/* Type toggle: Agent / Group / Human */}
        <div className="flex items-center rounded-md border border-border bg-background overflow-hidden shrink-0">
          <button
            onClick={() => selectMemberType("AGENT")}
            className={cn(
              "px-2.5 py-1 text-[10px] font-medium transition-colors",
              member.memberType === "AGENT"
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            Agent
          </button>
          <button
            onClick={() => selectMemberType("GROUP")}
            className={cn(
              "px-2.5 py-1 text-[10px] font-medium transition-colors",
              member.memberType === "GROUP"
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            <Users className="inline h-2.5 w-2.5 me-0.5" />
            Group
          </button>
          <button
            onClick={() => selectMemberType("HUMAN")}
            className={cn(
              "px-2.5 py-1 text-[10px] font-medium transition-colors",
              member.memberType === "HUMAN"
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground"
            )}
            data-testid={`member-type-human-${index}`}
          >
            <UserCheck className="inline h-2.5 w-2.5 me-0.5" />
            {t("groupWizard.memberTypeHuman", "Human")}
          </button>
        </div>

        <button
          onClick={onRemove}
          className="shrink-0 rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
          title={t("common.delete")}
          data-testid={`remove-member-${index}`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* Card body — mode toggle + form */}
      {!member.created && (
        <div className="px-4 py-3 space-y-3">
          {/* GROUP type: just show a group selector */}
          {member.memberType === "GROUP" ? (
            <GroupMemberPicker
              agentId={member.agentId}
              onUpdate={onUpdate}
              t={t}
            />
          ) : member.memberType === "HUMAN" ? (
            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-muted-foreground">
                {t("groupWizard.humanPrincipalId", "Principal ID")}
              </label>
              <input
                value={member.agentId}
                onChange={(e) => onUpdate({ agentId: e.target.value })}
                placeholder={t("groupWizard.humanPrincipalIdPlaceholder", "The user's login/principal id")}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid={`human-principal-id-${index}`}
              />
              <p className="text-[11px] text-muted-foreground">
                {t(
                  "groupWizard.humanPrincipalIdHint",
                  "The identity that may submit this member's turns — the discussion pauses and waits for them when it's their turn to speak.",
                )}
              </p>
            </div>
          ) : (
            <>
              {/* Mode toggle */}
              <div className="flex gap-2">
                <button
                  onClick={() => onUpdate({ mode: "existing", agentId: "" })}
                  className={cn(
                    "flex-1 flex items-center justify-center gap-2 rounded-lg border-2 py-2 text-xs font-medium transition-all",
                    member.mode === "existing"
                      ? "border-primary bg-primary/5 text-primary"
                      : "border-border text-muted-foreground hover:border-primary/30"
                  )}
                >
                  <Link2 className="h-3.5 w-3.5" />
                  {t("groupWizard.useExisting")}
                </button>
                <button
                  onClick={() => onUpdate({ mode: "new", agentId: "" })}
                  className={cn(
                    "flex-1 flex items-center justify-center gap-2 rounded-lg border-2 py-2 text-xs font-medium transition-all",
                    member.mode === "new"
                      ? `${styleColors.border} ${styleColors.bg} ${styleColors.text}`
                      : "border-border text-muted-foreground hover:border-primary/30"
                  )}
                >
                  <Sparkles className="h-3.5 w-3.5" />
                  {t("groupWizard.createNew")}
                </button>
              </div>

              {/* Existing agent mode */}
              {member.mode === "existing" && (
                <div className="relative">
                  <select
                    value={member.agentId}
                    onChange={(e) => onUpdate({ agentId: e.target.value })}
                    className={cn(
                      "w-full appearance-none rounded-lg border bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                      !member.agentId ? "border-amber-400/50" : "border-input"
                    )}
                  >
                    <option value="">{t("groupWizard.selectAgent")}</option>
                    {agents.map((agent) => (
                      <option key={agent.id} value={agent.id}>
                        {agent.name || agent.id.slice(0, 12)}
                      </option>
                    ))}
                  </select>
                  <ChevronDown className="pointer-events-none absolute inset-e-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                </div>
              )}

              {/* Create new agent mode */}
              {member.mode === "new" && (
                <div className="space-y-2.5">
                  {/* System prompt */}
                  <div>
                    <label className="mb-1 block text-xs font-medium text-muted-foreground">
                      {t("groupWizard.systemPrompt")}
                    </label>
                    <textarea
                      value={member.systemPrompt}
                      onChange={(e) => onUpdate({ systemPrompt: e.target.value })}
                      placeholder={`You are ${member.displayName}${member.role ? `, a ${member.role} expert` : ""}. Provide insightful analysis from your domain perspective.`}
                      rows={2}
                      className="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring resize-y"
                    />
                  </div>

                  {/* Provider + Model row */}
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label className="mb-1 block text-xs font-medium text-muted-foreground">
                        {t("groupWizard.provider")}
                      </label>
                      <div className="relative">
                        <select
                          value={member.provider}
                          onChange={(e) => {
                            onUpdate({
                              provider: e.target.value,
                              model: "",
                            });
                          }}
                          className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-1.5 pe-7 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        >
                          {LLM_PROVIDERS.map((p) => (
                            <option key={p.id} value={p.id}>{p.name}</option>
                          ))}
                        </select>
                        <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
                      </div>
                    </div>
                    <div>
                      <label className="mb-1 block text-xs font-medium text-muted-foreground">
                        {t("groupWizard.model")}
                      </label>
                      <input
                        value={member.model}
                        onChange={(e) => onUpdate({ model: e.target.value })}
                        className="w-full rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        placeholder={`e.g. ${providerConfig?.defaultModel ?? ""}`}
                      />
                    </div>
                  </div>

                  {/* API Key (only if provider needs it) */}
                  {providerConfig?.needsKey && (
                    <div>
                      <label className="mb-1 block text-xs font-medium text-muted-foreground">
                        {t("groupWizard.apiKey")}
                      </label>
                      <SecretKeyPicker
                        value={member.apiKey}
                        onChange={(v) => onUpdate({ apiKey: v })}
                        placeholder={t("groupWizard.apiKeyPlaceholder")}
                        testId={`gw-apikey-${index}`}
                      />
                    </div>
                  )}

                  {/* Create button */}
                  <button
                    onClick={onCreateAgent}
                    disabled={member.creating || !member.displayName.trim()}
                    className="w-full inline-flex items-center justify-center gap-2 rounded-lg bg-primary/10 px-4 py-2 text-xs font-medium text-primary hover:bg-primary/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {member.creating ? (
                      <RefreshCw className="h-3 w-3 animate-spin" />
                    ) : (
                      <Wand2 className="h-3 w-3" />
                    )}
                    {member.creating
                      ? t("groupWizard.creatingAgent")
                      : t("groupWizard.createThisAgent")}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Created confirmation */}
      {member.created && (
        <div className="px-4 py-2 bg-emerald-500/5 border-t border-emerald-500/20 flex items-center gap-2">
          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
          <span className="text-xs text-emerald-600 dark:text-emerald-400 font-medium">
            {t("groupWizard.agentReady")}
          </span>
          <span className="text-[10px] text-muted-foreground font-mono ms-auto">
            {member.agentId.slice(0, 12)}…
          </span>
        </div>
      )}
    </div>
  );
}

/* ================================================================
   Moderator Card (compact version of MemberCard)
   ================================================================ */

function ModeratorCard({
  moderator,
  agents,
  onChange,
  onCreateAgent,
  t,
}: {
  moderator: MemberSlot;
  agents: { id: string; name: string }[];
  onChange: (updates: Partial<MemberSlot>) => void;
  onCreateAgent: () => void;
  t: ReturnType<typeof useTranslation>["t"];
}) {
  const providerConfig = LLM_PROVIDERS.find((p) => p.id === moderator.provider);

  if (moderator.created) {
    return (
      <div className="flex items-center gap-2 rounded-lg bg-emerald-500/5 border border-emerald-500/20 p-2.5">
        <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
        <span className="text-xs text-emerald-600 dark:text-emerald-400 font-medium flex-1">
          {t("groupWizard.moderatorReady")}
        </span>
        <span className="text-[10px] text-muted-foreground font-mono">
          {moderator.agentId.slice(0, 12)}…
        </span>
      </div>
    );
  }

  return (
    <div className="space-y-2.5">
      {/* Mode toggle */}
      <div className="flex gap-2">
        <button
          onClick={() => onChange({ mode: "existing", agentId: "" })}
          className={cn(
            "flex-1 flex items-center justify-center gap-2 rounded-lg border-2 py-2 text-xs font-medium transition-all",
            moderator.mode === "existing"
              ? "border-primary bg-primary/5 text-primary"
              : "border-border text-muted-foreground hover:border-primary/30"
          )}
        >
          <Link2 className="h-3.5 w-3.5" />
          {t("groupWizard.useExisting")}
        </button>
        <button
          onClick={() => onChange({ mode: "new", agentId: "" })}
          className={cn(
            "flex-1 flex items-center justify-center gap-2 rounded-lg border-2 py-2 text-xs font-medium transition-all",
            moderator.mode === "new"
              ? "border-primary bg-primary/5 text-primary"
              : "border-border text-muted-foreground hover:border-primary/30"
          )}
        >
          <Sparkles className="h-3.5 w-3.5" />
          {t("groupWizard.createNew")}
        </button>
      </div>

      {moderator.mode === "existing" ? (
        <div className="relative">
          <select
            value={moderator.agentId}
            onChange={(e) => onChange({ agentId: e.target.value })}
            className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">{t("groupWizard.selectAgent")}</option>
            {agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name || agent.id.slice(0, 12)}
              </option>
            ))}
          </select>
          <ChevronDown className="pointer-events-none absolute inset-e-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        </div>
      ) : (
        <div className="space-y-2">
          <textarea
            value={moderator.systemPrompt}
            onChange={(e) => onChange({ systemPrompt: e.target.value })}
            placeholder="You are a skilled moderator. Synthesize the group's discussion into a clear, balanced summary…"
            rows={2}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring resize-y"
          />
          <div className="grid grid-cols-2 gap-2">
            <div className="relative">
              <select
                value={moderator.provider}
                onChange={(e) => {
                  onChange({ provider: e.target.value, model: "" });
                }}
                className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-1.5 pe-7 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              >
                {LLM_PROVIDERS.map((p) => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
              <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
            </div>
            <input
              value={moderator.model}
              onChange={(e) => onChange({ model: e.target.value })}
              className="rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              placeholder={`e.g. ${providerConfig?.defaultModel ?? ""}`}
            />
          </div>
          {providerConfig?.needsKey && (
            <div>
              <label className="mb-1 block text-xs font-medium text-muted-foreground">
                {t("groupWizard.apiKey")}
              </label>
              <SecretKeyPicker
                value={moderator.apiKey}
                onChange={(v) => onChange({ apiKey: v })}
                placeholder={t("groupWizard.apiKeyPlaceholder")}
                testId="gw-moderator-apikey"
              />
            </div>
          )}
          <button
            onClick={onCreateAgent}
            disabled={moderator.creating}
            className="w-full inline-flex items-center justify-center gap-2 rounded-lg bg-primary/10 px-4 py-2 text-xs font-medium text-primary hover:bg-primary/20 transition-colors disabled:opacity-50"
          >
            {moderator.creating ? (
              <RefreshCw className="h-3 w-3 animate-spin" />
            ) : (
              <Wand2 className="h-3 w-3" />
            )}
            {moderator.creating
              ? t("groupWizard.creatingAgent")
              : t("groupWizard.createModerator")}
          </button>
        </div>
      )}
    </div>
  );
}

/* ================================================================
   Step 4: Review
   ================================================================ */

function ReviewStep({
  state,
}: {
  state: WizardState;
}) {
  const { t } = useTranslation();
  const styleInfo = styleDisplay(state.style, t);
  // `?? CUSTOM`: pickers may offer a backend-only style with no color entry.
  const colors = STYLE_COLORS[state.style] ?? STYLE_COLORS.CUSTOM;

  const willCreateCount = state.members.filter(needsAgentCreation).length
    + (state.moderator && needsAgentCreation(state.moderator) ? 1 : 0);
  // Scoped to AGENT for the same reason as `needsAgentCreation`: this warns about
  // an agent that was never picked, and a GROUP/HUMAN member carries `mode:
  // "existing"` without one ever being relevant.
  const unassignedExistingCount = state.members.filter(
    (m) => m.memberType === "AGENT" && m.mode === "existing" && !m.agentId,
  ).length;
  const newCount = state.members.filter((m) => m.created).length;

  // A DEBATE with nobody on CON, a DEVIL_ADVOCATE group with no advocate: the
  // phases addressed to that role would run with zero speakers, and nothing
  // downstream ever says so. Warn here, at the last screen before Create.
  const roleGaps = uncoveredRolePhases({
    members: state.members,
    phases: null,
    style: state.style,
    maxRounds: state.maxRounds,
  });

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.reviewTitle")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.reviewDesc")}
      </p>

      <div className="mt-6 space-y-5">
        {/* Summary card */}
        <div className={cn("rounded-xl border-2 p-5 space-y-3", colors.border, colors.bg)}>
          <h3 className="text-lg font-bold text-foreground">{state.name}</h3>
          {state.description && (
            <p className="text-sm text-muted-foreground">{state.description}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Badge variant="outline" className={cn("text-xs", colors.text)}>
              {styleInfo?.icon} {styleInfo?.label}
            </Badge>
            <Badge variant="secondary" className="text-xs">
              <Users className="me-1 h-3 w-3" />
              {state.members.length} members
            </Badge>
            <Badge variant="secondary" className="text-xs">
              {state.maxRounds} rounds
            </Badge>
            {state.moderator && (
              <Badge variant="default" className="text-xs">
                <Star className="me-1 h-3 w-3" /> Moderator
              </Badge>
            )}
            {state.hitlEnabled && (
              <Badge variant="outline" className="text-xs text-amber-600 dark:text-amber-400">
                <HandMetal className="me-1 h-3 w-3" />
                {t("groupWizard.hitlReviewBadge", "Human approval")}
                {state.approvalPhases.length > 0 && ` · ${state.approvalPhases.length}`}
              </Badge>
            )}
          </div>
        </div>

        {/* Auto-creation notice */}
        {willCreateCount > 0 && (
          <div className="flex items-start gap-2 rounded-lg border border-primary/30 bg-primary/5 p-3" data-testid="auto-create-notice">
            <Sparkles className="h-4 w-4 text-primary shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-medium text-primary">
                {t("groupWizard.autoCreateNotice", { count: willCreateCount })}
              </p>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t("groupWizard.autoCreateHint")}
              </p>
            </div>
          </div>
        )}

        {/* Unassigned existing warning */}
        {unassignedExistingCount > 0 && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
            <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0 mt-0.5" />
            <div>
              <p className="text-sm font-medium text-amber-600 dark:text-amber-400">
                {t("groupWizard.unassignedWarning", { count: unassignedExistingCount })}
              </p>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t("groupWizard.unassignedHint")}
              </p>
            </div>
          </div>
        )}

        {/* Role coverage warning — a ROLE:<x> phase with no member carrying <x> */}
        {roleGaps.length > 0 && (
          <div
            className="flex flex-col gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3"
            data-testid="wizard-role-coverage-warning"
          >
            {roleGaps.map((gap) => (
              <div key={gap.role} className="flex items-start gap-2">
                <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-medium text-amber-600 dark:text-amber-400">
                    {t(
                      "groupWizard.roleCoverageWarning",
                      'No member has the role "{{role}}"',
                      { role: gap.role },
                    )}
                  </p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {t(
                      "groupWizard.roleCoverageHint",
                      "{{phases}} would run with no speakers. Go back to Members and set the role field.",
                      { phases: gap.phaseNames.join(", ") },
                    )}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Members */}
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
            {t("groupWizard.reviewMembers")}
            {newCount > 0 && (
              <span className="ms-2 font-normal normal-case text-emerald-500">
                ({newCount} newly created)
              </span>
            )}
          </h4>
          <div className="space-y-1">
            {state.members.map((m, i) => (
              <div key={i} className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-secondary/30 transition-colors">
                <div
                  className={cn(
                    "flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white",
                    m.created ? "bg-emerald-500" : hashColor(m.agentId || String(i))
                  )}
                >
                  {m.created ? <Check className="h-3 w-3" /> : getInitials(m.displayName || "?")}
                </div>
                <span className="text-sm font-medium text-foreground flex-1">{m.displayName}</span>
                {m.role && (
                  <Badge variant="outline" className="text-[10px]">{m.role}</Badge>
                )}
                {m.memberType === "GROUP" && (
                  <Badge variant="secondary" className="text-[10px]">
                    <Users className="h-2.5 w-2.5 me-0.5" /> Group
                  </Badge>
                )}
                {m.created && (
                  <Badge variant="success" className="text-[10px]">✓ new</Badge>
                )}
                <span className="font-mono text-[10px] text-muted-foreground">
                  {m.agentId ? m.agentId.slice(0, 8) + "…" : "unassigned"}
                </span>
              </div>
            ))}

            {/* Moderator in review */}
            {state.moderator && (
              <div className="flex items-center gap-3 rounded-lg px-3 py-2 bg-primary/5 border border-primary/20">
                <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground text-[10px] font-bold">
                  <Star className="h-3 w-3" />
                </div>
                <span className="text-sm font-medium text-foreground flex-1">Moderator</span>
                <Badge variant="default" className="text-[10px]">⭐ Synthesis</Badge>
                <span className="font-mono text-[10px] text-muted-foreground">
                  {state.moderator.agentId ? state.moderator.agentId.slice(0, 8) + "…" : "unassigned"}
                </span>
              </div>
            )}
          </div>
        </div>

        {/* Flow preview */}
        {styleInfo && (
          <div className={cn("rounded-lg border p-3", colors.border, colors.bg)}>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1">
              {t("groupWizard.flowPreview")}
            </p>
            <p className={cn("text-xs font-medium", colors.text)}>
              {styleInfo.flow}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

/* ================================================================
   Group Member Picker — shown when memberType is GROUP
   ================================================================ */

function GroupMemberPicker({
  agentId,
  onUpdate,
  t,
}: {
  agentId: string;
  onUpdate: (updates: Partial<MemberSlot>) => void;
  t: ReturnType<typeof useTranslation>["t"];
}) {
  const { data: groups, isLoading } = useEnrichedGroupDescriptors(100);
  const hasGroups = groups && groups.length > 0;

  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-muted-foreground">
        {t("groupWizard.selectGroup", "Select existing group")}
      </label>

      {isLoading ? (
        <div className="rounded-lg border border-input bg-background px-3 py-2 text-xs text-muted-foreground animate-pulse">
          {t("common.loading", "Loading…")}
        </div>
      ) : !hasGroups ? (
        <div className="rounded-lg border border-dashed border-amber-400/50 bg-amber-400/5 px-3 py-3 text-center">
          <Users className="h-4 w-4 text-amber-500 mx-auto mb-1" />
          <p className="text-xs font-medium text-amber-600 dark:text-amber-400">
            {t("groupWizard.noGroupsYet", "No groups available")}
          </p>
          <p className="text-[10px] text-muted-foreground mt-0.5">
            {t("groupWizard.noGroupsHint", "Create a group first, then add it as a nested sub-group member.")}
          </p>
        </div>
      ) : (
        <div className="relative">
          <select
            value={agentId}
            onChange={(e) => onUpdate({ agentId: e.target.value, mode: "existing" })}
            className={cn(
              "w-full appearance-none rounded-lg border bg-background px-3 py-2 pe-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring",
              !agentId ? "border-amber-400/50" : "border-input"
            )}
          >
            <option value="">{t("groupWizard.selectGroup", "Select existing group…")}</option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name || group.id.slice(0, 12)} ({group.memberCount} members)
              </option>
            ))}
          </select>
          <ChevronDown className="pointer-events-none absolute inset-e-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        </div>
      )}

      {hasGroups && (
        <p className="mt-1 text-[10px] text-muted-foreground">
          {t("groupWizard.groupMemberHint", "This group will participate as a nested sub-group in the discussion.")}
        </p>
      )}
    </div>
  );
}
