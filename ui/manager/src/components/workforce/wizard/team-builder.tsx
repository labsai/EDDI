import { useState, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronUp, Plus, Sparkles, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import { AgentPicker } from "@/components/shared/agent-picker";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { LLM_PROVIDERS } from "@/lib/api/agent-setup";
import {
  memberIssue,
  effectiveLlm,
  providerNeedsKey,
  providerLabel,
  starterPrompt,
  type LlmDefaults,
  type MemberIssue,
} from "./member-validation";

// ─── Types ──────────────────────────────────────────────────────────────────

export interface MemberSlot {
  id: string; // unique client-side ID (crypto.randomUUID())
  displayName: string;
  role: string;
  mode: "existing" | "new";
  // For existing — and, once a `new` advisor has been provisioned by an
  // earlier (partially failed) creation attempt, the id it was given, so a
  // retry reuses it instead of creating a duplicate.
  agentId: string;
  // For new — each may be blank to inherit the workforce-wide LlmDefaults.
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
  llmDefaults: LlmDefaults;
  onLlmDefaultsChange: (defaults: LlmDefaults) => void;
  /** Reveal validation messages — set once the user has tried to continue. */
  showErrors?: boolean;
}

const inputClass =
  "h-9 w-full rounded-lg border bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring";
const labelClass = "mb-1.5 block text-xs font-medium text-muted-foreground";

// ─── LlmFields (internal) ───────────────────────────────────────────────────

/**
 * Provider / model / API key — used once for the workforce-wide defaults and
 * once per new advisor. In the per-advisor case every field may be left blank
 * to inherit, and the controls say what they would inherit.
 */
function LlmFields({
  idPrefix,
  value,
  onChange,
  inherit,
  keyInvalid,
  keyError,
}: {
  idPrefix: string;
  value: LlmDefaults;
  onChange: (patch: Partial<LlmDefaults>) => void;
  /** The workforce defaults this member inherits from; omitted for the defaults block itself. */
  inherit?: LlmDefaults;
  keyInvalid: boolean;
  /** Message shown under the key field while `keyInvalid`. */
  keyError?: string;
}) {
  const { t } = useTranslation();
  const effectiveProvider = value.provider || inherit?.provider || "";
  const needsKey = providerNeedsKey(effectiveProvider);

  return (
    <div className="space-y-3">
      <div>
        <label htmlFor={`provider-${idPrefix}`} className={labelClass}>
          {t("Workforce.wizard.provider", "LLM Provider")}
        </label>
        <select
          id={`provider-${idPrefix}`}
          value={value.provider}
          onChange={(e) => {
            const prov = LLM_PROVIDERS.find((p) => p.id === e.target.value);
            // Switching provider resets the model to that provider's default;
            // a per-advisor "inherit" pick clears the model too so it inherits.
            onChange({ provider: e.target.value, model: prov?.defaultModel ?? "" });
          }}
          className={cn(inputClass, "border-input")}
        >
          {/* Only a per-advisor field can be left blank (to inherit); the
              defaults block always names a provider, since a blank one would
              just be anthropic on the server anyway. */}
          {inherit && (
            <option value="">
              {t("Workforce.wizard.useDefault", "Workforce default ({{value}})", {
                value: providerLabel(inherit.provider),
              })}
            </option>
          )}
          {LLM_PROVIDERS.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor={`model-${idPrefix}`} className={labelClass}>
          {t("Workforce.wizard.model", "Model")}
        </label>
        <input
          id={`model-${idPrefix}`}
          type="text"
          value={value.model}
          onChange={(e) => onChange({ model: e.target.value })}
          placeholder={
            inherit?.model
              ? t("Workforce.wizard.useDefault", "Workforce default ({{value}})", {
                  value: inherit.model,
                })
              : t("Workforce.wizard.modelPlaceholder", "e.g. claude-sonnet-5")
          }
          className={cn(inputClass, "border-input")}
        />
      </div>

      {/* Local providers (ollama, jlama, bedrock, oracle-genai) take no key —
          the backend does not ask for one, so neither do we. */}
      {needsKey && (
        <div data-invalid={keyInvalid || undefined} data-testid={`apikey-${idPrefix}`}>
          <label htmlFor={`apikey-${idPrefix}`} className={labelClass}>
            {t("Workforce.wizard.apiKey", "API Key")}
            {!inherit?.apiKey && <span className="text-destructive"> *</span>}
          </label>
          <SecretKeyPicker
            value={value.apiKey}
            onChange={(v) => onChange({ apiKey: v })}
            testId={`apikey-${idPrefix}-picker`}
            placeholder={
              inherit?.apiKey
                ? t("Workforce.wizard.useDefaultKey", "Workforce default key")
                : undefined
            }
          />
          {keyInvalid && keyError && (
            <p role="alert" className="mt-1.5 text-xs text-destructive">
              {keyError}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

// ─── MemberCard (internal) ──────────────────────────────────────────────────

function MemberCard({
  member,
  canRemove,
  issue,
  showErrors,
  llmDefaults,
  onUpdate,
  onRemove,
}: {
  member: MemberSlot;
  canRemove: boolean;
  issue: MemberIssue | null;
  showErrors: boolean;
  llmDefaults: LlmDefaults;
  onUpdate: (updated: MemberSlot) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const invalid = showErrors && issue !== null;
  /** Provisioned by an earlier, partially failed attempt — reused as is. */
  const created = member.mode === "new" && member.agentId !== "";

  // The system prompt, the API key and the agent picker all sit inside the
  // collapsed half of the card, so flagging one while the card is shut points
  // the user at a field they cannot see. While such a field is flagged the
  // card is held open — derived, not an effect, so the field is in the DOM in
  // the same render the flag appears and the wizard can move focus to it.
  const heldOpen = invalid && issue !== "name";
  const open = expanded || heldOpen;

  // Latch: once a flag has opened the card, keep it open after the flag
  // clears. Without this, typing the first character into a flagged prompt
  // (with the key already set) resolved the advisor, dropped `heldOpen`, and
  // collapsed the card — unmounting the textarea under the cursor.
  useEffect(() => {
    if (heldOpen) setExpanded(true);
  }, [heldOpen]);

  const update = useCallback(
    (patch: Partial<MemberSlot>) => {
      onUpdate({ ...member, ...patch });
    },
    [member, onUpdate],
  );

  const errorText =
    issue === "name"
      ? t("Workforce.wizard.nameRequired", "Give this advisor a name.")
      : issue === "agent"
        ? t("Workforce.wizard.agentRequired", "Pick the existing agent this advisor should use.")
        : issue === "apiKey"
          ? t(
              "Workforce.wizard.apiKeyRequired",
              "{{provider}} needs an API key. Add one here or in the LLM defaults above.",
              { provider: providerLabel(effectiveLlm(member, llmDefaults).provider) },
            )
          : t(
              "Workforce.wizard.promptRequired",
              "A system prompt is required. Describe this advisor's expertise and how it should answer.",
            );

  return (
    <div
      className={cn(
        "rounded-xl border p-4 transition-all",
        invalid ? "border-destructive" : "border-border",
      )}
      data-testid={`member-card-${member.id}`}
    >
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
            aria-invalid={showErrors && issue === "name"}
            aria-describedby={showErrors && issue === "name" ? `member-error-${member.id}` : undefined}
            className={cn(
              "h-8 flex-1 rounded-lg border bg-background ps-3 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring",
              showErrors && issue === "name" ? "border-destructive" : "border-input",
            )}
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
          {created && (
            <Badge variant="secondary" className="me-1 text-[10px]">
              {t("Workforce.wizard.alreadyCreated", "Created")}
            </Badge>
          )}
          <button
            type="button"
            onClick={() => setExpanded((p) => !p)}
            aria-expanded={open}
            // Cannot be collapsed while it holds a flagged field.
            disabled={heldOpen}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
            aria-label={
              open
                ? t("Workforce.wizard.collapse", "Collapse")
                : t("Workforce.wizard.expand", "Expand")
            }
          >
            {open ? (
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

      {/* Why this member blocks the wizard. Rendered under the header so it is
          visible whether the card is open or shut. */}
      {invalid && (
        <p
          id={`member-error-${member.id}`}
          role="alert"
          data-testid={`member-error-${member.id}`}
          className="mt-2 text-xs text-destructive"
        >
          {errorText}
        </p>
      )}

      {/* Expandable config */}
      {open && created && (
        <p
          className="mt-4 border-t border-border/50 pt-4 text-xs text-muted-foreground"
          data-testid={`member-created-note-${member.id}`}
        >
          {t(
            "Workforce.wizard.alreadyCreatedNote",
            "Already created in a previous attempt — it will be reused as is, so its prompt and LLM settings can no longer be changed here.",
          )}
        </p>
      )}
      {open && !created && (
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
            <div data-invalid={(showErrors && issue === "agent") || undefined}>
              <label htmlFor={`agent-${member.id}`} className={labelClass}>
                {t("Workforce.wizard.selectAgent", "Select agent")}
                <span className="text-destructive"> *</span>
              </label>
              <AgentPicker
                value={member.agentId}
                onChange={(id) => update({ agentId: id })}
              />
            </div>
          ) : (
            <div className="space-y-3">
              {/* System prompt first — it is the one field every new advisor
                  must fill by hand, so it should not hide below the LLM plumbing. */}
              <div>
                <label htmlFor={`prompt-${member.id}`} className={labelClass}>
                  {t("Workforce.wizard.personality", "Personality & expertise")}
                  <span className="text-destructive"> *</span>
                </label>
                <textarea
                  id={`prompt-${member.id}`}
                  value={member.systemPrompt}
                  onChange={(e) => update({ systemPrompt: e.target.value })}
                  rows={4}
                  aria-required="true"
                  aria-invalid={showErrors && issue === "prompt"}
                  aria-describedby={[
                    `prompt-hint-${member.id}`,
                    showErrors && issue === "prompt" ? `member-error-${member.id}` : null,
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  placeholder={t(
                    "Workforce.wizard.personalityPlaceholder",
                    "Describe this advisor's expertise, perspective, and communication style…",
                  )}
                  className={cn(
                    "w-full rounded-lg border bg-background p-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring",
                    showErrors && issue === "prompt" ? "border-destructive" : "border-input",
                  )}
                />
                <div className="mt-1.5 flex flex-wrap items-center justify-between gap-2">
                  <p id={`prompt-hint-${member.id}`} className="text-xs text-muted-foreground">
                    {t(
                      "Workforce.wizard.personalityHint",
                      "Required — sent to the agent as its system prompt.",
                    )}
                  </p>
                  {!member.systemPrompt.trim() && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      data-testid={`starter-prompt-${member.id}`}
                      onClick={() =>
                        update({ systemPrompt: starterPrompt(member.displayName, member.role, t) })
                      }
                    >
                      <Sparkles className="h-3.5 w-3.5" />
                      {t("Workforce.wizard.useStarterPrompt", "Insert a starter prompt")}
                    </Button>
                  )}
                </div>
              </div>

              <LlmFields
                idPrefix={member.id}
                value={{ provider: member.provider, model: member.model, apiKey: member.apiKey }}
                onChange={(patch) => update(patch)}
                inherit={llmDefaults}
                keyInvalid={showErrors && issue === "apiKey"}
              />
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
  llmDefaults,
  onLlmDefaultsChange,
  showErrors = false,
}: TeamBuilderProps) {
  const { t } = useTranslation();
  const boardNameMissing = showErrors && boardName.trim().length === 0;
  const hasNewMembers = members.some((m) => m.mode === "new" && !m.agentId);
  // Flag the shared key block itself when advisors are waiting on it — it
  // sits above the cards, so a failed Next lands focus on the one field that
  // fixes all of them, rather than on the first advisor's own key input.
  const defaultsKeyMissing =
    showErrors &&
    members.some((m) => !m.provider && memberIssue(m, llmDefaults) === "apiKey");

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
    onMembersChange([
      ...members,
      {
        id: crypto.randomUUID(),
        displayName: "",
        role: "",
        mode: "new",
        agentId: "",
        systemPrompt: "",
        provider: "",
        model: "",
        apiKey: "",
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
          aria-invalid={boardNameMissing}
          aria-describedby={boardNameMissing ? "board-name-error" : undefined}
          placeholder={t(
            "Workforce.wizard.boardNamePlaceholder",
            "e.g. Product Strategy Board",
          )}
          className={cn(
            "h-11 w-full rounded-lg border bg-background ps-4 pe-4 text-lg text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
            boardNameMissing ? "border-destructive" : "border-input",
          )}
        />
        {boardNameMissing && (
          <p
            id="board-name-error"
            role="alert"
            data-testid="board-name-error"
            className="mt-1.5 text-xs text-destructive"
          >
            {t("Workforce.wizard.boardNameRequired", "Give this workforce a name.")}
          </p>
        )}
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

      {/* LLM defaults — one provider/model/key for every advisor created
          here, instead of the same three fields five times over. Hidden when
          nothing will be created (all advisors are existing agents). */}
      {hasNewMembers && (
        <div className="rounded-xl border border-border p-4" data-testid="llm-defaults">
          <h3 className="text-sm font-semibold text-foreground">
            {t("Workforce.wizard.llmDefaults", "LLM for new advisors")}
          </h3>
          <p className="mt-1 mb-4 text-xs text-muted-foreground">
            {t(
              "Workforce.wizard.llmDefaultsDesc",
              "Used by every advisor created here unless the advisor sets its own below.",
            )}
          </p>
          <LlmFields
            idPrefix="defaults"
            value={llmDefaults}
            onChange={(patch) => onLlmDefaultsChange({ ...llmDefaults, ...patch })}
            keyInvalid={defaultsKeyMissing}
            keyError={t(
              "Workforce.wizard.llmDefaultsKeyRequired",
              "Add a key here to cover every advisor that does not set its own.",
            )}
          />
        </div>
      )}

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
            issue={memberIssue(member, llmDefaults)}
            showErrors={showErrors}
            llmDefaults={llmDefaults}
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
      >
        <Plus className="h-4 w-4" />
        {t("Workforce.wizard.addAdvisor", "Add Advisor")}
      </Button>
    </div>
  );
}

export { TeamBuilder };
export type { TeamBuilderProps };
