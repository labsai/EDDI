import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { X, ChevronRight, ChevronLeft, Users, Plus, Trash2, AlertTriangle, Check, Clock, Shield, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useCreateGroup, useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  DISCUSSION_STYLES,
  STYLE_INFO,
  type DiscussionStyle,
  type GroupMember,
  type AgentGroupConfiguration,
} from "@/lib/api/groups";
import { getGroupTemplates, type GroupTemplate } from "@/lib/group-templates";

interface CreateGroupDialogProps {
  open: boolean;
  onClose: () => void;
  template?: GroupTemplate | null;
}

type Step = "template" | "basics" | "members" | "review";

export function CreateGroupDialog({ open, onClose, template: initialTemplate }: CreateGroupDialogProps) {
  const { t } = useTranslation();
  const createMutation = useCreateGroup();
  const { data: agentDescriptors } = useAgentDescriptors(100);
  const agents = useMemo(
    () => (agentDescriptors ? groupAgentsByName(agentDescriptors) : []),
    [agentDescriptors],
  );

  const [step, setStep] = useState<Step>(initialTemplate ? "basics" : "template");
  const [, setSelectedTemplate] = useState<GroupTemplate | null>(initialTemplate ?? null);
  const [name, setName] = useState(initialTemplate?.name ?? "");
  const [description, setDescription] = useState(initialTemplate?.description ?? "");
  const [style, setStyle] = useState<DiscussionStyle>(initialTemplate?.style ?? "ROUND_TABLE");
  const [maxRounds, setMaxRounds] = useState(initialTemplate?.maxRounds ?? 2);
  const [members, setMembers] = useState<GroupMember[]>(
    initialTemplate
      ? initialTemplate.roles.map((r, i) => ({
          agentId: "",
          displayName: r.displayName,
          speakingOrder: i + 1,
          role: r.role,
          memberType: "AGENT" as const,
        }))
      : []
  );
  const [moderatorAgentId, setModeratorAgentId] = useState("");

  // Resolve agent IDs → display names for review step
  const agentNameMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of agents) map.set(a.id, a.name || a.id.slice(0, 12));
    return map;
  }, [agents]);

  const resetAndClose = useCallback(() => {
    setStep("template");
    setSelectedTemplate(null);
    setName("");
    setDescription("");
    setStyle("ROUND_TABLE");
    setMaxRounds(2);
    setMembers([]);
    setModeratorAgentId("");
    onClose();
  }, [onClose]);

  function selectTemplate(tmpl: GroupTemplate) {
    setSelectedTemplate(tmpl);
    setName(tmpl.name);
    setDescription(tmpl.description);
    setStyle(tmpl.style);
    setMaxRounds(tmpl.maxRounds);
    setMembers(
      tmpl.roles.map((r, i) => ({
        agentId: "",
        displayName: r.displayName,
        speakingOrder: i + 1,
        role: r.role,
        memberType: "AGENT" as const,
      }))
    );
    setStep("basics");
  }

  function startBlank() {
    setSelectedTemplate(null);
    setStep("basics");
  }

  function addMember() {
    setMembers([
      ...members,
      {
        agentId: "",
        displayName: "",
        speakingOrder: members.length + 1,
        role: null,
        memberType: "AGENT",
      },
    ]);
  }

  function removeMember(idx: number) {
    setMembers(members.filter((_, i) => i !== idx));
  }

  function updateMember(idx: number, updates: Partial<GroupMember>) {
    setMembers(members.map((m, i) => (i === idx ? { ...m, ...updates } : m)));
  }

  function handleCreate() {
    const config: AgentGroupConfiguration = {
      name,
      description,
      members: members.filter((m) => m.agentId || m.displayName),
      moderatorAgentId: moderatorAgentId || null,
      style,
      maxRounds,
      phases: null,
      protocol: {
        agentTimeoutSeconds: 60,
        onAgentFailure: "SKIP",
        maxRetries: 2,
        onMemberUnavailable: "SKIP",
      },
    };

    createMutation.mutate(config, {
      onSuccess: () => {
        toast.success(t("groups.createSuccess", "Group created successfully"));
        resetAndClose();
      },
      onError: () => toast.error(t("common.error")),
    });
  }

  if (!open) return null;

  const steps: Step[] = ["template", "basics", "members", "review"];
  const stepIdx = steps.indexOf(step);
  const canNext =
    step === "template" ||
    (step === "basics" && name.trim()) ||
    (step === "members" && members.length >= 2) ||
    step === "review";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-2xl max-h-[85vh] overflow-hidden rounded-xl border border-border bg-card shadow-2xl flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 className="text-lg font-bold">{t("groups.createGroup", "Create Group")}</h2>
          <button onClick={resetAndClose} className="rounded-md p-1 hover:bg-secondary transition-colors">
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-2 px-5 py-2 border-b border-border bg-secondary/20">
          {steps.map((s, i) => (
            <div key={s} className={cn("flex items-center gap-1", i > 0 && "ms-2")}>
              {i > 0 && <ChevronRight className="h-3 w-3 text-muted-foreground" />}
              <span
                className={cn(
                  "text-xs font-medium px-2 py-0.5 rounded-full transition-colors",
                  s === step
                    ? "bg-primary text-primary-foreground"
                    : i < stepIdx
                      ? "text-primary"
                      : "text-muted-foreground"
                )}
              >
                {s === "template"
                  ? t("groups.stepTemplate", "Template")
                  : s === "basics"
                    ? t("groups.stepBasics", "Basics")
                    : s === "members"
                      ? t("groups.stepMembers", "Members")
                      : t("groups.stepReview", "Review")}
              </span>
            </div>
          ))}
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-5">
          {/* Template step */}
          {step === "template" && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                {t("groups.chooseTemplate", "Choose a template or start from scratch.")}
              </p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                {getGroupTemplates(t).map((tmpl) => (
                  <button
                    key={tmpl.key}
                    onClick={() => selectTemplate(tmpl)}
                    className="text-start rounded-lg border border-border p-3 hover:border-primary/50 hover:bg-primary/5 transition-all"
                    data-testid={`template-${tmpl.key}`}
                  >
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-lg">{tmpl.icon}</span>
                      <span className="text-sm font-semibold">{tmpl.name}</span>
                    </div>
                    <p className="text-xs text-muted-foreground line-clamp-2">{tmpl.description}</p>
                    <div className="flex items-center gap-2 mt-2">
                      <Badge variant="outline" className="text-[10px]">
                        {STYLE_INFO[tmpl.style]?.label}
                      </Badge>
                      <Badge variant="secondary" className="text-[10px]">
                        {tmpl.roles.length} roles
                      </Badge>
                    </div>
                  </button>
                ))}
                {/* Blank option */}
                <button
                  onClick={startBlank}
                  className="text-start rounded-lg border border-dashed border-border p-3 hover:border-primary/50 hover:bg-primary/5 transition-all"
                  data-testid="template-blank"
                >
                  <div className="flex items-center gap-2 mb-1">
                    <Plus className="h-5 w-5 text-muted-foreground" />
                    <span className="text-sm font-semibold">{t("groups.startBlank", "Start from Scratch")}</span>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {t("groups.blankDesc", "Configure everything manually.")}
                  </p>
                </button>
              </div>
            </div>
          )}

          {/* Basics step */}
          {step === "basics" && (
            <div className="space-y-4">
              <div>
                <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("common.name", "Name")} *
                </label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder={t("groups.namePlaceholder", "e.g. Advisory Board")}
                  data-testid="group-name-input"
                />
              </div>
              <div>
                <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("common.description", "Description")}
                </label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                  rows={2}
                />
              </div>
              <div>
                <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("groups.discussionStyle", "Discussion Style")}
                </label>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 mt-1">
                  {DISCUSSION_STYLES.filter((s) => s !== "CUSTOM").map((s) => (
                    <button
                      key={s}
                      onClick={() => setStyle(s)}
                      className={cn(
                        "rounded-lg border p-2 text-start transition-all text-xs",
                        style === s
                          ? "border-primary bg-primary/10"
                          : "border-border hover:border-primary/30"
                      )}
                    >
                      <span className="text-sm">{STYLE_INFO[s]?.icon}</span>{" "}
                      <span className="font-medium">{STYLE_INFO[s]?.label}</span>
                    </button>
                  ))}
                </div>
              </div>
              <div>
                <label className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("groups.maxRounds", "Max Rounds")}
                </label>
                <input
                  type="number"
                  value={maxRounds}
                  onChange={(e) => setMaxRounds(Math.max(1, parseInt(e.target.value) || 1))}
                  className="mt-1 w-20 rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                  min={1}
                  max={10}
                />
              </div>
            </div>
          )}

          {/* Members step */}
          {step === "members" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <p className="text-sm text-muted-foreground">
                  {t("groups.assignAgents", "Assign agents to each member role.")}
                </p>
                <Button variant="outline" size="sm" onClick={addMember}>
                  <Plus className="h-3 w-3" /> {t("groups.addMember", "Add Member")}
                </Button>
              </div>

              <div className="space-y-2 max-h-[400px] overflow-y-auto pe-1">
                {members.map((member, idx) => (
                  <div
                    key={idx}
                    className="rounded-lg border border-border overflow-hidden bg-card"
                  >
                    {/* Card header — type toggle + name + delete */}
                    <div className="flex items-center gap-2 px-3 py-2 bg-secondary/20">
                      {/* Agent/Group type toggle — FIRST (prominent) */}
                      <div className="flex items-center rounded-md border border-border bg-background overflow-hidden shrink-0">
                        <button
                          onClick={() => updateMember(idx, { memberType: "AGENT" })}
                          className={cn(
                            "px-2 py-0.5 text-[10px] font-medium transition-colors",
                            member.memberType === "AGENT"
                              ? "bg-primary text-primary-foreground"
                              : "text-muted-foreground hover:text-foreground"
                          )}
                        >
                          Agent
                        </button>
                        <button
                          onClick={() => updateMember(idx, { memberType: "GROUP" })}
                          className={cn(
                            "px-2 py-0.5 text-[10px] font-medium transition-colors",
                            member.memberType === "GROUP"
                              ? "bg-primary text-primary-foreground"
                              : "text-muted-foreground hover:text-foreground"
                          )}
                        >
                          <Users className="inline h-2.5 w-2.5 me-0.5" />
                          Group
                        </button>
                      </div>

                      <input
                        value={member.displayName}
                        onChange={(e) => updateMember(idx, { displayName: e.target.value })}
                        className="flex-1 min-w-0 bg-transparent text-xs font-semibold text-foreground focus:outline-none placeholder:text-muted-foreground"
                        placeholder={t("groupWizard.displayName", "Display Name")}
                      />

                      <input
                        value={member.role ?? ""}
                        onChange={(e) => updateMember(idx, { role: e.target.value || null })}
                        className="w-24 bg-transparent text-xs text-muted-foreground focus:outline-none placeholder:text-muted-foreground/50"
                        placeholder={t("groupWizard.rolePlaceholder", "Role")}
                      />

                      <button
                        onClick={() => removeMember(idx)}
                        className="shrink-0 rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>

                    {/* Card body — conditional on member type */}
                    <div className="px-3 py-2">
                      {member.memberType === "GROUP" ? (
                        <GroupPickerSelect
                          value={member.agentId}
                          onChange={(v) => updateMember(idx, { agentId: v })}
                        />
                      ) : (
                        <select
                          value={member.agentId}
                          onChange={(e) => updateMember(idx, { agentId: e.target.value })}
                          className={cn(
                            "w-full rounded-md border bg-background px-2 py-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-ring",
                            !member.agentId ? "border-amber-400/50" : "border-input"
                          )}
                        >
                          <option value="">{t("groups.selectAgent", "Select agent…")}</option>
                          {agents.map((agent) => (
                            <option key={agent.id} value={agent.id}>
                              {agent.name || agent.id.slice(0, 12)}
                            </option>
                          ))}
                        </select>
                      )}
                    </div>
                  </div>
                ))}
              </div>

              {/* Moderator */}
              <div className="rounded-lg border-2 border-primary/20 bg-primary/5 p-3">
                <label className="text-xs font-semibold text-foreground flex items-center gap-1.5 mb-2">
                  ⭐ {t("groups.moderator", "Moderator (for synthesis)")}
                </label>
                <select
                  value={moderatorAgentId}
                  onChange={(e) => setModeratorAgentId(e.target.value)}
                  className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  <option value="">{t("groups.noModerator", "No moderator")}</option>
                  {agents.map((agent) => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name || agent.id.slice(0, 12)}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          )}

          {/* Review / Confirmation step */}
          {step === "review" && (
            <ReviewStep
              name={name}
              description={description}
              style={style}
              maxRounds={maxRounds}
              members={members}
              moderatorAgentId={moderatorAgentId}
              agentNameMap={agentNameMap}
            />
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-border px-5 py-3">
          <Button
            variant="outline"
            onClick={() => {
              if (stepIdx > 0) setStep(steps[stepIdx - 1]!);
              else resetAndClose();
            }}
          >
            {stepIdx > 0 ? (
              <>
                <ChevronLeft className="h-4 w-4" /> {t("common.back", "Back")}
              </>
            ) : (
              t("common.cancel", "Cancel")
            )}
          </Button>

          {step === "review" ? (
            <Button onClick={handleCreate} disabled={createMutation.isPending}>
              {createMutation.isPending ? t("common.saving", "Saving…") : t("groups.createGroup", "Create Group")}
            </Button>
          ) : (
            <Button onClick={() => setStep(steps[stepIdx + 1]!)} disabled={!canNext}>
              {t("common.next", "Next")} <ChevronRight className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Review sub-component (extracted to avoid IIFE and aid React DevTools / fast-refresh) ───

interface ReviewStepProps {
  name: string;
  description: string;
  style: DiscussionStyle;
  maxRounds: number;
  members: GroupMember[];
  moderatorAgentId: string;
  agentNameMap: Map<string, string>;
}

function ReviewStep({
  name,
  description,
  style,
  maxRounds,
  members,
  moderatorAgentId,
  agentNameMap,
}: ReviewStepProps) {
  const { t } = useTranslation();
  const unassignedCount = members.filter((m) => !m.agentId).length;

  return (
    <div className="space-y-4" data-testid="review-summary">
      {/* Warning banner for unassigned members */}
      {unassignedCount > 0 && (
        <div className="flex items-start gap-2.5 rounded-lg border border-amber-400/40 bg-amber-400/10 px-3 py-2.5">
          <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0 mt-0.5" />
          <p className="text-xs">
            <span className="font-semibold text-amber-500">
              {t("groups.reviewUnassignedWarning", "{{count}} member(s) unassigned", { count: unassignedCount })}
            </span>
            <span className="text-muted-foreground ms-1">
              — {t("groups.reviewUnassignedHint", "Go back to assign agents before creating.")}
            </span>
          </p>
        </div>
      )}

      {/* Section 1: Group Identity */}
      <div className="rounded-lg border border-border bg-secondary/10 p-3 space-y-2">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-sm font-bold truncate">{name}</h3>
            {description && (
              <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">{description}</p>
            )}
          </div>
          <Badge variant="outline" className="shrink-0">
            {STYLE_INFO[style]?.icon} {STYLE_INFO[style]?.label}
          </Badge>
        </div>
        <p className="text-[10px] text-muted-foreground font-mono">
          {STYLE_INFO[style]?.flow}
        </p>
      </div>

      {/* Section 2: Key settings */}
      <div className="grid grid-cols-3 gap-2">
        <div className="rounded-lg border border-border bg-secondary/10 p-2.5 text-center">
          <Users className="h-3.5 w-3.5 text-muted-foreground inline-block mb-1" />
          <div className="text-sm font-bold">{members.length}</div>
          <div className="text-[10px] text-muted-foreground">
            {t("groups.reviewMembers", "Members")}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-secondary/10 p-2.5 text-center">
          <RotateCcw className="h-3.5 w-3.5 text-muted-foreground inline-block mb-1" />
          <div className="text-sm font-bold">{maxRounds}</div>
          <div className="text-[10px] text-muted-foreground">
            {t("groups.reviewRounds", "Max Rounds")}
          </div>
        </div>
        <div className="rounded-lg border border-border bg-secondary/10 p-2.5 text-center">
          <Clock className="h-3.5 w-3.5 text-muted-foreground inline-block mb-1" />
          <div className="text-sm font-bold">60s</div>
          <div className="text-[10px] text-muted-foreground">
            {t("groups.reviewTimeout", "Agent Timeout")}
          </div>
        </div>
      </div>

      {/* Section 3: Member roster */}
      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
          {t("groups.reviewMemberRoster", "Member Roster")}
        </h4>
        <div className="rounded-lg border border-border overflow-hidden">
          {members.map((m, i) => {
            const agentName = m.agentId ? agentNameMap.get(m.agentId) : null;
            const isUnassigned = !m.agentId;

            return (
              <div
                key={`${m.displayName}-${i}`}
                className={cn(
                  "flex items-center gap-2 px-3 py-2 text-xs",
                  i > 0 && "border-t border-border",
                  isUnassigned && "bg-amber-400/5"
                )}
              >
                {/* Speaking order */}
                <span className="text-[10px] font-mono text-muted-foreground w-4 shrink-0 text-center">
                  {i + 1}
                </span>

                {/* Display name + role */}
                <div className="flex items-center gap-1.5 min-w-0 flex-1">
                  <span className="font-medium truncate">{m.displayName}</span>
                  {m.role && (
                    <Badge variant="outline" className="text-[9px] px-1.5 py-0 shrink-0">
                      {m.role}
                    </Badge>
                  )}
                  {m.memberType === "GROUP" && (
                    <Badge variant="secondary" className="text-[9px] px-1.5 py-0 shrink-0">
                      <Users className="h-2 w-2 me-0.5" />
                      {t("groups.memberTypeGroup", "Group")}
                    </Badge>
                  )}
                </div>

                {/* Agent assignment status */}
                <div className="shrink-0 flex items-center gap-1">
                  {isUnassigned ? (
                    <span className="flex items-center gap-1 text-amber-500 font-medium">
                      <AlertTriangle className="h-3 w-3" />
                      {t("groups.reviewUnassigned", "Unassigned")}
                    </span>
                  ) : (
                    <span className="flex items-center gap-1 text-emerald-500">
                      <Check className="h-3 w-3" />
                      <span className="text-foreground/70 truncate max-w-[140px]">{agentName}</span>
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Section 4: Moderator */}
      <div className="rounded-lg border-2 border-primary/15 bg-primary/5 p-3">
        <div className="flex items-center gap-2 text-xs">
          <span>⭐</span>
          <span className="font-semibold">{t("groups.reviewModerator", "Moderator")}</span>
          <span className="text-muted-foreground">—</span>
          {moderatorAgentId ? (
            <span className="text-foreground font-medium">
              {agentNameMap.get(moderatorAgentId) || moderatorAgentId.slice(0, 12)}
            </span>
          ) : (
            <span className="text-muted-foreground italic">
              {t("groups.reviewNoModerator", "None (auto-synthesis disabled)")}
            </span>
          )}
        </div>
      </div>

      {/* Section 5: Protocol defaults */}
      <div className="flex items-center gap-3 text-[10px] text-muted-foreground px-1">
        <Shield className="h-3 w-3 shrink-0" />
        <span>
          {t("groups.reviewProtocol", "On failure: skip agent • 2 retries • Unavailable members: skip")}
        </span>
      </div>
    </div>
  );
}

/** Reusable group picker for GROUP-type members */
function GroupPickerSelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useTranslation();
  const { data: groups, isLoading } = useEnrichedGroupDescriptors(100);

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={cn(
        "w-full rounded-md border bg-background px-2 py-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-ring",
        !value ? "border-amber-400/50" : "border-input"
      )}
      disabled={isLoading}
    >
      <option value="">
        {isLoading
          ? t("common.loading", "Loading…")
          : t("groupWizard.selectGroup", "Select existing group…")}
      </option>
      {groups?.map((group) => (
        <option key={group.id} value={group.id}>
          {group.name || group.id.slice(0, 12)} ({group.memberCount} members)
        </option>
      ))}
    </select>
  );
}
