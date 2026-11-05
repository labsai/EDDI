import { memo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Palette } from "lucide-react";
import type { Agent, AgentAppearance, ConversationControls, TitleGeneration } from "@/lib/api/agents";
import type { AgentEdit } from "@/lib/agent-draft";
import { EditorSection } from "./editor-section";

/** The switches that default to on: absent keeps the control. */
const DEFAULT_ON_CONTROLS = ["undo", "redo", "attachments", "rating", "quickReplies", "streaming"] as const;

/** Mirrors the backend's AgentPresentationValidator caps. */
const MAX_STARTERS = 6;
const MAX_STARTER_LENGTH = 200;

/** The starters a textarea's text stands for: non-blank lines, capped. Blank lines never reach the draft. */
function parseStarters(text: string): string[] {
  return text
    .split("\n")
    .map((line) => line.slice(0, MAX_STARTER_LENGTH))
    .filter((line) => line.trim())
    .slice(0, MAX_STARTERS);
}

/**
 * The text with the caps applied, so the textarea never shows what would not be saved:
 * each line cut at MAX_STARTER_LENGTH, and nothing kept after the MAX_STARTERS-th starter.
 * Blank lines before that stay, so a new line can still be started.
 */
function capStartersText(text: string): string {
  const kept: string[] = [];
  let starters = 0;
  for (const raw of text.split("\n")) {
    if (starters === MAX_STARTERS) break;
    const line = raw.slice(0, MAX_STARTER_LENGTH);
    if (line.trim()) starters++;
    kept.push(line);
  }
  return kept.join("\n");
}

/**
 * How the agent presents itself to the people chatting with it — `appearance`,
 * `conversationControls` — and whether a model writes conversation titles
 * (`titleGeneration`). All optional; the backend validates every field on save.
 *
 * Kept deliberately small: plain inputs, no preview. `rawHtml` is the one
 * control that is off unless switched on, so its checkbox starts unchecked.
 */
export const AgentPresentationSection = memo(function AgentPresentationSection({
  agent,
  onChange,
  disabled = false,
  modified = false,
}: {
  agent: Agent;
  onChange: AgentEdit;
  disabled?: boolean;
  modified?: boolean;
}) {
  const { t } = useTranslation();
  const appearance: AgentAppearance = agent.appearance ?? {};
  const controls: ConversationControls = agent.conversationControls ?? {};
  const titles: TitleGeneration = agent.titleGeneration ?? {};

  // The textarea keeps what was typed, a trailing blank line included, so a new line can be
  // started; the draft gets only parseStarters(text). When the stored starters change from
  // outside (another agent or version, a discarded draft), the text follows them.
  const storedStarters = (appearance.starters ?? []).join("\n");
  const [startersText, setStartersText] = useState(storedStarters);
  const [startersSource, setStartersSource] = useState(storedStarters);
  if (startersSource !== storedStarters) {
    setStartersSource(storedStarters);
    if (parseStarters(startersText).join("\n") !== storedStarters) setStartersText(storedStarters);
  }

  function patchAppearance(updates: Partial<AgentAppearance>) {
    onChange({ ...agent, appearance: { ...appearance, ...updates } });
  }

  function patchControls(updates: Partial<ConversationControls>) {
    onChange({ ...agent, conversationControls: { ...controls, ...updates } });
  }

  function patchTitles(updates: Partial<TitleGeneration>) {
    onChange({ ...agent, titleGeneration: { ...titles, ...updates } });
  }

  const inputClass =
    "w-full rounded-md border border-input bg-background px-2 py-1.5 text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-ring";

  return (
    <EditorSection
      label={t("agentDetail.presentation.title", "Presentation & titles")}
      icon={Palette}
      accent="text-pink-500"
      variant="card"
      modified={modified}
      defaultOpen={false}
    >
      <div className="space-y-4" data-testid="agent-presentation-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "agentDetail.presentation.description",
            "How chat windows show this agent. Everything is optional; empty fields keep the defaults."
          )}
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block space-y-1 text-[10px] text-muted-foreground">
            <span>{t("agentDetail.presentation.displayName", "Display name")}</span>
            <input
              value={appearance.displayName ?? ""}
              onChange={(e) => patchAppearance({ displayName: e.target.value.trim() ? e.target.value : undefined })}
              maxLength={80}
              disabled={disabled}
              className={inputClass}
              data-testid="appearance-display-name"
            />
          </label>
          <label className="block space-y-1 text-[10px] text-muted-foreground">
            <span>{t("agentDetail.presentation.accentColor", "Accent colour (#rrggbb)")}</span>
            <input
              value={appearance.accentColor ?? ""}
              onChange={(e) => patchAppearance({ accentColor: e.target.value.trim() ? e.target.value.trim() : undefined })}
              maxLength={7}
              placeholder="#4f46e5"
              disabled={disabled}
              className={inputClass}
              data-testid="appearance-accent-color"
            />
          </label>
        </div>

        <label className="block space-y-1 text-[10px] text-muted-foreground">
          <span>{t("agentDetail.presentation.avatar", "Avatar (https URL or small data:image URI)")}</span>
          <input
            value={appearance.avatar ?? ""}
            onChange={(e) => patchAppearance({ avatar: e.target.value.trim() ? e.target.value.trim() : undefined })}
            placeholder="https://"
            disabled={disabled}
            className={inputClass}
            data-testid="appearance-avatar"
          />
        </label>

        <label className="block space-y-1 text-[10px] text-muted-foreground">
          <span>
            {t("agentDetail.presentation.starters", "Conversation starters (one per line, up to {{count}})", {
              count: MAX_STARTERS,
            })}
          </span>
          <textarea
            value={startersText}
            onChange={(e) => {
              const text = capStartersText(e.target.value);
              setStartersText(text);
              patchAppearance({ starters: parseStarters(text) });
            }}
            rows={3}
            disabled={disabled}
            className={inputClass}
            data-testid="appearance-starters"
          />
        </label>

        <fieldset className="space-y-2">
          <legend className="text-[10px] font-medium text-foreground">
            {t("agentDetail.presentation.controls", "Chat controls")}
          </legend>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {DEFAULT_ON_CONTROLS.map((key) => (
              <label key={key} className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={controls[key] !== false}
                  onChange={() => patchControls({ [key]: controls[key] === false ? undefined : false })}
                  disabled={disabled}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                  data-testid={`control-${key}`}
                />
                {t(`agentDetail.presentation.control.${key}`)}
              </label>
            ))}
          </div>
          <label className="inline-flex items-center gap-2 text-xs text-foreground">
            <input
              type="checkbox"
              checked={controls.rawHtml === true}
              onChange={() => patchControls({ rawHtml: controls.rawHtml === true ? undefined : true })}
              disabled={disabled}
              className="h-3.5 w-3.5 rounded border-input accent-primary"
              data-testid="control-rawHtml"
            />
            {t("agentDetail.presentation.control.rawHtml", "Render HTML in replies")}
          </label>
          <p className="text-[10px] text-muted-foreground">
            {t(
              "agentDetail.presentation.rawHtmlNote",
              "Off by default: HTML in replies is shown as text. Turn it on only for an agent whose replies are meant to contain HTML."
            )}
          </p>
        </fieldset>

        <fieldset className="space-y-2">
          <legend className="text-[10px] font-medium text-foreground">
            {t("agentDetail.presentation.titles", "Conversation titles")}
          </legend>
          <label className="inline-flex items-center gap-2 text-xs text-foreground">
            <input
              type="checkbox"
              checked={titles.enabled === true}
              onChange={() => patchTitles({ enabled: !(titles.enabled === true) })}
              disabled={disabled}
              className="h-3.5 w-3.5 rounded border-input accent-primary"
              data-testid="title-generation-enabled"
            />
            {t("agentDetail.presentation.titlesEnable", "Let a model write conversation titles")}
          </label>
          <p className="text-[10px] text-muted-foreground">
            {t(
              "agentDetail.presentation.titlesNote",
              "Otherwise a conversation is titled with its first message. The model runs once per conversation, in the background, and is charged to the cost budget. A title the user set is never replaced."
            )}
          </p>
          {titles.enabled && (
            <div className="grid gap-3 ps-5 sm:grid-cols-3">
              <label className="block space-y-1 text-[10px] text-muted-foreground">
                <span>{t("agentDetail.presentation.titlesProvider", "Provider")}</span>
                <input
                  value={titles.llmProvider ?? ""}
                  onChange={(e) => patchTitles({ llmProvider: e.target.value.trim() || undefined })}
                  placeholder="openai"
                  disabled={disabled}
                  className={inputClass}
                  data-testid="title-generation-provider"
                />
              </label>
              <label className="block space-y-1 text-[10px] text-muted-foreground">
                <span>{t("agentDetail.presentation.titlesModel", "Model")}</span>
                <input
                  value={titles.llmModel ?? ""}
                  onChange={(e) => patchTitles({ llmModel: e.target.value.trim() || undefined })}
                  placeholder="gpt-4o-mini"
                  disabled={disabled}
                  className={inputClass}
                  data-testid="title-generation-model"
                />
              </label>
              <label className="block space-y-1 text-[10px] text-muted-foreground">
                <span>{t("agentDetail.presentation.titlesMaxLength", "Longest title")}</span>
                <input
                  type="number"
                  min={10}
                  max={200}
                  value={titles.maxLength ?? 60}
                  onChange={(e) => patchTitles({ maxLength: e.target.value ? Number(e.target.value) : undefined })}
                  disabled={disabled}
                  className={inputClass}
                  data-testid="title-generation-max-length"
                />
              </label>
              <p className="text-[10px] text-muted-foreground sm:col-span-3">
                {t(
                  "agentDetail.presentation.titlesCredentials",
                  "Credentials go in titleGeneration.parameters of the agent document (for example an apiKey that references a vault secret); see the agent configuration docs."
                )}
              </p>
            </div>
          )}
        </fieldset>
      </div>
    </EditorSection>
  );
});
