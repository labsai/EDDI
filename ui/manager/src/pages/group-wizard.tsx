import { useState, useCallback } from "react";
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
  GripVertical,
  Sparkles,
  CheckCircle2,
  ExternalLink,
  ChevronDown,
  Wand2,
} from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { useCreateGroup } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  DISCUSSION_STYLES,
  STYLE_INFO,
  type DiscussionStyle,
  type GroupMember,
  type AgentGroupConfiguration,
} from "@/lib/api/groups";
import { GROUP_TEMPLATES, type GroupTemplate } from "@/lib/group-templates";

/* ================================================================
   Types & Constants
   ================================================================ */

interface WizardState {
  name: string;
  description: string;
  style: DiscussionStyle;
  maxRounds: number;
  members: GroupMember[];
  moderatorAgentId: string;
}

const INITIAL_STATE: WizardState = {
  name: "",
  description: "",
  style: "ROUND_TABLE",
  maxRounds: 2,
  members: [],
  moderatorAgentId: "",
};

const STEPS = [
  { id: "template", icon: Sparkles },
  { id: "config", icon: Brain },
  { id: "members", icon: Users },
  { id: "review", icon: Rocket },
] as const;

/* ================================================================
   Main Wizard Component
   ================================================================ */

export function GroupWizardPage() {
  const { t } = useTranslation();
  const [currentStep, setCurrentStep] = useState(0);
  const [state, setState] = useState<WizardState>(INITIAL_STATE);
  const [resultId, setResultId] = useState<string | null>(null);

  const createMutation = useCreateGroup();

  const isCreating = createMutation.isPending;
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
      members: tmpl.roles.map((r, i) => ({
        agentId: "",
        displayName: r.displayName,
        speakingOrder: i + 1,
        role: r.role,
        memberType: "AGENT" as const,
      })),
    });
    setCurrentStep(1);
  }

  function canProceed(): boolean {
    if (!step) return false;
    switch (step.id) {
      case "template":
        return true; // Can skip or pick
      case "config":
        return state.name.trim().length > 0;
      case "members":
        return state.members.length >= 2;
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

  function handleCreate() {
    const config: AgentGroupConfiguration = {
      name: state.name,
      description: state.description,
      members: state.members.filter((m) => m.agentId || m.displayName),
      moderatorAgentId: state.moderatorAgentId || null,
      style: state.style,
      maxRounds: state.maxRounds,
      phases: null,
      protocol: {
        agentTimeoutSeconds: 60,
        onAgentFailure: "SKIP",
        maxRetries: 2,
        onMemberUnavailable: "SKIP",
      },
    };

    createMutation.mutate(config, {
      onSuccess: (data) => {
        toast.success(t("groupWizard.success", "Group created successfully!"));
        // Extract ID from Location header or response
        const id = typeof data === "string" ? data : (data as { id?: string })?.id ?? "new";
        setResultId(id);
      },
      onError: () => toast.error(t("common.error")),
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
            {t("groupWizard.created", "Group Created!")}
          </h2>
          <p className="mt-2 text-muted-foreground">
            {state.name} — {state.members.length} {t("groups.members", "members")} · {STYLE_INFO[state.style]?.label}
          </p>

          <div className="mt-6 flex items-center justify-center gap-3">
            <Link
              to={`/manage/groups/${resultId}`}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98]"
            >
              {t("groupWizard.viewGroup", "Open Group")}
              <ExternalLink className="h-4 w-4" />
            </Link>
            <button
              onClick={() => {
                setState(INITIAL_STATE);
                setCurrentStep(0);
                setResultId(null);
              }}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
            >
              {t("groupWizard.createAnother", "Create Another")}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      {/* Header */}
      <div className="space-y-2">
        <Link
          to="/manage/groups"
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          {t("groupWizard.backToGroups", "Back to Groups")}
        </Link>
        <h1 className="flex items-center gap-3 text-3xl font-bold text-foreground">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-linear-to-br from-amber-500 to-orange-600 text-white shadow-lg">
            <Wand2 className="h-5 w-5" />
          </div>
          {t("groupWizard.title", "Group Setup Wizard")}
        </h1>
        <p className="text-muted-foreground">
          {t("groupWizard.subtitle", "Create a multi-agent discussion group in minutes")}
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
        {step?.id === "members" && <MembersStep state={state} onChange={update} />}
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
              <RefreshCw className="h-4 w-4 animate-spin" />
            ) : (
              <Rocket className="h-4 w-4" />
            )}
            {t("groupWizard.createGroup", "Create Group")}
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
        {t("groupWizard.templateTitle", "Start from a template")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.templateDesc", "Choose a preset group configuration or start from scratch.")}
      </p>

      <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2" data-testid="template-grid">
        {GROUP_TEMPLATES.map((tmpl) => {
          const styleInfo = STYLE_INFO[tmpl.style];
          return (
            <button
              key={tmpl.key}
              onClick={() => onSelect(tmpl)}
              className="group relative flex flex-col items-start gap-3 rounded-xl border-2 border-border p-5 text-start transition-all duration-200 hover:border-primary/40 hover:shadow-md"
              data-testid={`template-${tmpl.key}`}
            >
              <div className="flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-linear-to-br from-amber-500/20 to-orange-500/20 text-2xl shadow-sm">
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
                {tmpl.roles.slice(0, 4).map((r) => (
                  <Badge key={r.role} variant="secondary" className="text-[10px]">
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
              {t("groupWizard.startBlank", "Start from Scratch")}
            </p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t("groupWizard.blankDesc", "Configure everything manually")}
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

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.configTitle", "Group Configuration")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.configDesc", "Set up the basics for your discussion group")}
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
            placeholder={t("groupWizard.namePlaceholder", "e.g. Advisory Board")}
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
            placeholder={t("groupWizard.descPlaceholder", "What is this group for?")}
            rows={2}
            className="w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-y transition-shadow"
            data-testid="gw-description"
          />
        </div>

        {/* Discussion Style */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("groups.discussionStyle", "Discussion Style")}
          </label>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {DISCUSSION_STYLES.filter((s) => s !== "CUSTOM").map((s) => {
              const info = STYLE_INFO[s];
              return (
                <button
                  key={s}
                  onClick={() => onChange({ style: s })}
                  className={cn(
                    "group relative flex flex-col items-start rounded-lg border p-3 text-start transition-all",
                    state.style === s
                      ? "border-primary bg-primary/5 shadow-sm"
                      : "border-border hover:border-primary/30"
                  )}
                  data-testid={`gw-style-${s}`}
                >
                  <div className="flex items-center gap-2">
                    <span className="text-lg">{info?.icon}</span>
                    <span className="text-xs font-semibold text-foreground">{info?.label}</span>
                  </div>
                  <p className="mt-1 text-[10px] text-muted-foreground leading-snug line-clamp-2">
                    {info?.flow}
                  </p>
                  {state.style === s && (
                    <div className="absolute inset-e-1.5 top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
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
              onChange={(e) => onChange({ maxRounds: parseInt(e.target.value) })}
              className="flex-1 accent-primary"
            />
            <span className="w-8 text-center text-sm font-bold text-foreground">{state.maxRounds}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ================================================================
   Step 3: Members
   ================================================================ */

function MembersStep({
  state,
  onChange,
}: {
  state: WizardState;
  onChange: (patch: Partial<WizardState>) => void;
}) {
  const { t } = useTranslation();
  const { data: agentDescriptors, refetch: refetchAgents } = useAgentDescriptors(100);
  const agents = agentDescriptors ? groupAgentsByName(agentDescriptors) : [];

  function addMember() {
    onChange({
      members: [
        ...state.members,
        {
          agentId: "",
          displayName: `Agent ${state.members.length + 1}`,
          speakingOrder: state.members.length + 1,
          role: null,
          memberType: "AGENT",
        },
      ],
    });
  }

  function removeMember(idx: number) {
    onChange({ members: state.members.filter((_, i) => i !== idx) });
  }

  function updateMember(idx: number, updates: Partial<GroupMember>) {
    onChange({
      members: state.members.map((m, i) => (i === idx ? { ...m, ...updates } : m)),
    });
  }

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.membersTitle", "Group Members")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.membersDesc", "Add members and assign agents to each role. Minimum 2 members.")}
      </p>

      <div className="mt-6 space-y-4">
        {/* Agent source: existing + create new */}
        <div className="flex items-center justify-between rounded-lg border border-border bg-secondary/20 p-3">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Users className="h-3.5 w-3.5" />
            {agents.length > 0
              ? t("groupWizard.availableAgents", "{{count}} agents available", { count: agents.length })
              : t("groupWizard.noAgentsYet", "No agents created yet")}
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => refetchAgents()}
              className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
              title={t("groupWizard.refreshAgents", "Refresh agent list")}
            >
              <RefreshCw className="h-3 w-3" />
              {t("common.refresh", "Refresh")}
            </button>
            <a
              href="/manage/agents/wizard"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary hover:bg-primary/20 transition-colors"
            >
              <Wand2 className="h-3 w-3" />
              {t("groupWizard.createAgent", "Create Agent")}
              <ExternalLink className="h-2.5 w-2.5 opacity-60" />
            </a>
          </div>
        </div>

        {/* Members list */}
        <div className="space-y-2 max-h-[420px] overflow-y-auto pe-1">
          {state.members.map((member, idx) => (
            <div
              key={idx}
              className="flex items-center gap-3 rounded-lg border border-border p-3 bg-secondary/20 transition-all hover:bg-secondary/30"
              data-testid={`member-row-${idx}`}
            >
              <GripVertical className="h-4 w-4 text-muted-foreground shrink-0 cursor-grab" />

              {/* Avatar */}
              <div
                className={cn(
                  "flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white",
                  hashColor(member.agentId || String(idx))
                )}
              >
                {getInitials(member.displayName || "??")}
              </div>

              {/* Fields */}
              <div className="flex-1 min-w-0 grid grid-cols-1 sm:grid-cols-3 gap-2">
                <input
                  value={member.displayName}
                  onChange={(e) => updateMember(idx, { displayName: e.target.value })}
                  className="rounded-md border border-input bg-background px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  placeholder={t("groupWizard.displayName", "Display Name")}
                />
                <div className="relative">
                  <select
                    value={member.agentId}
                    onChange={(e) => updateMember(idx, { agentId: e.target.value })}
                    className={cn(
                      "w-full appearance-none rounded-md border bg-background px-2.5 py-1.5 pe-7 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring",
                      !member.agentId ? "border-amber-400/50" : "border-input"
                    )}
                  >
                    <option value="">{t("groupWizard.selectAgent", "Select agent…")}</option>
                    {agents.map((agent) => (
                      <option key={agent.id} value={agent.id}>
                        {agent.name || agent.id.slice(0, 12)}
                      </option>
                    ))}
                  </select>
                  <ChevronDown className="pointer-events-none absolute inset-e-2 top-1/2 h-3 w-3 -translate-y-1/2 text-muted-foreground" />
                </div>
                <input
                  value={member.role ?? ""}
                  onChange={(e) => updateMember(idx, { role: e.target.value || null })}
                  className="rounded-md border border-input bg-background px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  placeholder={t("groupWizard.rolePlaceholder", "Role (e.g. Marketing)")}
                />
              </div>

              {/* Type toggle */}
              <Badge
                variant={member.memberType === "GROUP" ? "default" : "secondary"}
                className="text-[9px] cursor-pointer shrink-0"
                onClick={() => updateMember(idx, { memberType: member.memberType === "GROUP" ? "AGENT" : "GROUP" })}
                title={t("groupWizard.toggleType", "Toggle Agent/Group")}
              >
                {member.memberType === "GROUP" ? (
                  <><Users className="h-2.5 w-2.5 me-0.5" /> Group</>
                ) : (
                  "Agent"
                )}
              </Badge>

              <button
                onClick={() => removeMember(idx)}
                className="shrink-0 rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                title={t("common.delete")}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
        </div>

        {/* Add member button */}
        <button
          onClick={addMember}
          className="flex w-full items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border p-3 text-sm font-medium text-muted-foreground hover:border-primary/40 hover:text-primary transition-colors"
          data-testid="gw-add-member"
        >
          <Plus className="h-4 w-4" />
          {t("groupWizard.addMember", "Add Member")}
        </button>

        {/* Moderator */}
        <div className="rounded-lg border border-border bg-secondary/20 p-4">
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            ⭐ {t("groupWizard.moderator", "Moderator (for synthesis)")}
          </label>
          <p className="text-xs text-muted-foreground mb-2">
            {t("groupWizard.moderatorDesc", "The moderator synthesizes the group's discussion into a final answer.")}
          </p>
          <div className="relative">
            <select
              value={state.moderatorAgentId}
              onChange={(e) => onChange({ moderatorAgentId: e.target.value })}
              className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-2 pe-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="gw-moderator"
            >
              <option value="">{t("groupWizard.noModerator", "No moderator")}</option>
              {agents.map((agent) => (
                <option key={agent.id} value={agent.id}>
                  {agent.name || agent.id.slice(0, 12)}
                </option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute inset-e-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>

        {/* Member count indicator */}
        {state.members.length < 2 && (
          <p className="text-xs text-amber-500 font-medium">
            ⚠ {t("groupWizard.needMembers", "Add at least 2 members to proceed")}
          </p>
        )}
      </div>
    </div>
  );
}

/* ================================================================
   Step 4: Review
   ================================================================ */

function ReviewStep({ state }: { state: WizardState }) {
  const { t } = useTranslation();
  const styleInfo = STYLE_INFO[state.style];

  return (
    <div>
      <h2 className="text-xl font-semibold text-foreground">
        {t("groupWizard.reviewTitle", "Review & Create")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("groupWizard.reviewDesc", "Review your group configuration before creating.")}
      </p>

      <div className="mt-6 space-y-5">
        {/* Summary card */}
        <div className="rounded-xl border border-border bg-secondary/20 p-5 space-y-3">
          <h3 className="text-lg font-bold text-foreground">{state.name}</h3>
          {state.description && (
            <p className="text-sm text-muted-foreground">{state.description}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Badge variant="outline" className="text-xs">
              {styleInfo?.icon} {styleInfo?.label}
            </Badge>
            <Badge variant="secondary" className="text-xs">
              <Users className="me-1 h-3 w-3" />
              {state.members.length} members
            </Badge>
            <Badge variant="secondary" className="text-xs">
              {state.maxRounds} rounds
            </Badge>
          </div>
        </div>

        {/* Members */}
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
            {t("groupWizard.reviewMembers", "Members")}
          </h4>
          <div className="space-y-1">
            {state.members.map((m, i) => (
              <div key={i} className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-secondary/30 transition-colors">
                <div
                  className={cn(
                    "flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-[10px] font-bold text-white",
                    hashColor(m.agentId || String(i))
                  )}
                >
                  {getInitials(m.displayName || "?")}
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
                {state.moderatorAgentId && state.moderatorAgentId === m.agentId && (
                  <Badge variant="default" className="text-[10px]">⭐ Mod</Badge>
                )}
                <span className="font-mono text-[10px] text-muted-foreground">
                  {m.agentId ? m.agentId.slice(0, 8) + "…" : "unassigned"}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Flow preview */}
        {styleInfo && (
          <div className="rounded-lg border border-border bg-secondary/20 p-3">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1">
              {t("groupWizard.flowPreview", "Discussion Flow")}
            </p>
            <p className="text-xs text-foreground/80 font-medium">
              {styleInfo.flow}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
