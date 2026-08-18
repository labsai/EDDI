import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronUp, Plus, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentPicker } from "@/components/shared/agent-picker";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { LLM_PROVIDERS } from "@/lib/api/agent-setup";

// ─── Types ──────────────────────────────────────────────────────────────────

export interface MemberSlot {
  id: string; // unique client-side ID (crypto.randomUUID())
  displayName: string;
  role: string;
  mode: "existing" | "new";
  // For existing:
  agentId: string;
  // For new:
  systemPrompt: string;
  provider: string;
  model: string;
  apiKey: string;
}

interface TeamBuilderProps {
  boardName: string;
  onBoardNameChange: (name: string) => void;
  boardDescription: string;
  onBoardDescriptionChange: (desc: string) => void;
  members: MemberSlot[];
  onMembersChange: (members: MemberSlot[]) => void;
}

// ─── MemberCard (internal) ──────────────────────────────────────────────────

function MemberCard({
  member,
  canRemove,
  onUpdate,
  onRemove,
}: {
  member: MemberSlot;
  canRemove: boolean;
  onUpdate: (updated: MemberSlot) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const update = useCallback(
    (patch: Partial<MemberSlot>) => {
      onUpdate({ ...member, ...patch });
    },
    [member, onUpdate],
  );

  return (
    <div className="rounded-xl border border-border p-4 transition-all">
      {/* Header row */}
      <div className="flex items-center gap-3">
        <AdvisorAvatar
          name={member.displayName || "?"}
          agentId={member.id}
          size="sm"
        />

        <div className="flex flex-1 flex-col gap-2 sm:flex-row">
          <input
            id={`name-${member.id}`}
            type="text"
            value={member.displayName}
            onChange={(e) => update({ displayName: e.target.value })}
            placeholder={t("Workforce.wizard.advisorName", "Advisor name")}
            aria-label={t("Workforce.wizard.advisorName", "Advisor name")}
            className="h-8 flex-1 rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
          <input
            id={`role-${member.id}`}
            type="text"
            value={member.role}
            onChange={(e) => update({ role: e.target.value })}
            placeholder={t("Workforce.wizard.role", "Role")}
            aria-label={t("Workforce.wizard.role", "Role")}
            className="h-8 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring sm:w-36"
          />
        </div>

        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => setExpanded((p) => !p)}
            aria-expanded={expanded}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label={
              expanded
                ? t("Workforce.wizard.collapse", "Collapse")
                : t("Workforce.wizard.expand", "Expand")
            }
          >
            {expanded ? (
              <ChevronUp className="h-4 w-4" />
            ) : (
              <ChevronDown className="h-4 w-4" />
            )}
          </button>

          {canRemove && (
            <button
              type="button"
              onClick={onRemove}
              className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
              aria-label={t("Workforce.wizard.removeAdvisor", "Remove advisor")}
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>

      {/* Expandable config */}
      {expanded && (
        <div className="mt-4 space-y-4 border-t border-border/50 pt-4">
          {/* Mode toggle */}
          <div className="flex gap-2">
            <button
              type="button"
              aria-pressed={member.mode === "existing"}
              onClick={() => update({ mode: "existing" })}
              className={cn(
                "rounded-lg ps-3 pe-3 py-1.5 text-xs font-medium transition-colors",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                member.mode === "existing"
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground hover:bg-muted/80",
              )}
            >
              {t("Workforce.wizard.useExisting", "Use existing agent")}
            </button>
            <button
              type="button"
              aria-pressed={member.mode === "new"}
              onClick={() => update({ mode: "new" })}
              className={cn(
                "rounded-lg ps-3 pe-3 py-1.5 text-xs font-medium transition-colors",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                member.mode === "new"
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground hover:bg-muted/80",
              )}
            >
              {t("Workforce.wizard.createNew", "Create new advisor")}
            </button>
          </div>

          {member.mode === "existing" ? (
            <div>
              <label htmlFor={`agent-${member.id}`} className="mb-1.5 block text-xs font-medium text-muted-foreground">
                {t("Workforce.wizard.selectAgent", "Select agent")}
              </label>
              <AgentPicker
                value={member.agentId}
                onChange={(id) => update({ agentId: id })}
              />
            </div>
          ) : (
            <div className="space-y-3">
              {/* Provider */}
              <div>
                <label htmlFor={`provider-${member.id}`} className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t("Workforce.wizard.provider", "LLM Provider")}
                </label>
                <select
                  id={`provider-${member.id}`}
                  value={member.provider}
                  onChange={(e) => {
                    const prov = LLM_PROVIDERS.find(
                      (p) => p.id === e.target.value,
                    );
                    update({
                      provider: e.target.value,
                      model: prov?.defaultModel ?? member.model,
                    });
                  }}
                  className="h-9 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                >
                  <option value="">
                    {t("Workforce.wizard.selectProvider", "Select provider…")}
                  </option>
                  {LLM_PROVIDERS.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </div>

              {/* Model */}
              <div>
                <label htmlFor={`model-${member.id}`} className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t("Workforce.wizard.model", "Model")}
                </label>
                <input
                  id={`model-${member.id}`}
                  type="text"
                  value={member.model}
                  onChange={(e) => update({ model: e.target.value })}
                  placeholder={t(
                    "Workforce.wizard.modelPlaceholder",
                    "e.g. claude-sonnet-5",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>

              {/* API Key */}
              <div>
                <label htmlFor={`apikey-${member.id}`} className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t("Workforce.wizard.apiKey", "API Key")}
                </label>
                <SecretKeyPicker
                  value={member.apiKey}
                  onChange={(v) => update({ apiKey: v })}
                />
              </div>

              {/* System prompt */}
              <div>
                <label htmlFor={`prompt-${member.id}`} className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t(
                    "Workforce.wizard.personality",
                    "Personality & expertise",
                  )}
                </label>
                <textarea
                  id={`prompt-${member.id}`}
                  value={member.systemPrompt}
                  onChange={(e) => update({ systemPrompt: e.target.value })}
                  rows={4}
                  placeholder={t(
                    "Workforce.wizard.personalityPlaceholder",
                    "Describe this advisor's expertise, perspective, and communication style…",
                  )}
                  className="w-full rounded-lg border border-input bg-background p-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                />
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ─── TeamBuilder (exported) ─────────────────────────────────────────────────

function TeamBuilder({
  boardName,
  onBoardNameChange,
  boardDescription,
  onBoardDescriptionChange,
  members,
  onMembersChange,
}: TeamBuilderProps) {
  const { t } = useTranslation();

  const updateMember = useCallback(
    (index: number, updated: MemberSlot) => {
      const next = [...members];
      next[index] = updated;
      onMembersChange(next);
    },
    [members, onMembersChange],
  );

  const removeMember = useCallback(
    (index: number) => {
      onMembersChange(members.filter((_, i) => i !== index));
    },
    [members, onMembersChange],
  );

  const addMember = useCallback(() => {
    // Seed the LLM choice from the last advisor built here. A team is normally
    // one provider and one credential across every member, and without this the
    // vault key had to be picked again for each of them — the repetition this
    // wizard exists to avoid. Still per-member state, so any slot can differ.
    const previous = [...members].reverse().find((m) => m.mode === "new");
    onMembersChange([
      ...members,
      {
        id: crypto.randomUUID(),
        displayName: "",
        role: "",
        mode: "new",
        agentId: "",
        systemPrompt: "",
        provider: previous?.provider ?? "",
        model: previous?.model ?? "",
        apiKey: previous?.apiKey ?? "",
      },
    ]);
  }, [members, onMembersChange]);

  return (
    <div className="space-y-6">
      {/* Board name */}
      <div>
        <label htmlFor="board-name" className="mb-1.5 block text-sm font-medium text-foreground">
          {t("Workforce.wizard.boardName", "Workforce name")}
        </label>
        <input
          id="board-name"
          type="text"
          value={boardName}
          onChange={(e) => onBoardNameChange(e.target.value)}
          placeholder={t(
            "Workforce.wizard.boardNamePlaceholder",
            "e.g. Product Strategy Board",
          )}
          className="h-11 w-full rounded-lg border border-input bg-background ps-4 pe-4 text-lg text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      </div>

      {/* Board description */}
      <div>
        <label htmlFor="board-description" className="mb-1.5 block text-sm font-medium text-foreground">
          {t("Workforce.wizard.boardDescription", "Description (optional)")}
        </label>
        <textarea
          id="board-description"
          value={boardDescription}
          onChange={(e) => onBoardDescriptionChange(e.target.value)}
          rows={2}
          placeholder={t(
            "Workforce.wizard.boardDescPlaceholder",
            "Briefly describe the purpose of this advisory board…",
          )}
          className="w-full rounded-lg border border-input bg-background p-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
        />
      </div>

      {/* Section divider */}
      <div className="flex items-center gap-3">
        <h3 className="text-sm font-semibold text-foreground">
          {t("Workforce.wizard.yourAdvisors", "Your Advisors")}
        </h3>
        <Badge variant="secondary">{members.length}</Badge>
      </div>

      {/* Member cards */}
      <div className="space-y-3">
        {members.map((member, index) => (
          <MemberCard
            key={member.id}
            member={member}
            canRemove={members.length > 2}
            onUpdate={(updated) => updateMember(index, updated)}
            onRemove={() => removeMember(index)}
          />
        ))}
      </div>

      {/* Add advisor button */}
      <Button
        variant="outline"
        className="mt-3 w-full"
        onClick={addMember}
        data-testid="add-advisor-btn"
      >
        <Plus className="h-4 w-4" />
        {t("Workforce.wizard.addAdvisor", "Add Advisor")}
      </Button>
    </div>
  );
}

export { TeamBuilder };
export type { TeamBuilderProps };
