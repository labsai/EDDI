import { useTranslation } from "react-i18next";
import { ScrollText, RotateCcw } from "lucide-react";
import { EditorSection } from "../editor-section";
import { ContentEditor } from "../content-editor";
import { MODEL_TYPES } from "./types";
import type { TaskSectionProps } from "./task-section-props";

/**
 * Conversation Memory (Rolling Summary) + Retry Configuration sections.
 * Grouped together as they're both operational concerns for a single LLM task.
 */
export function TaskMemorySection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();

  return (
    <>
      {/* ══════ Token-Aware Context Window ══════ */}
      <EditorSection
        label={t("llmEditor.tokenWindow", "Token-Aware Context Window")}
        icon={ScrollText}
        accent="text-cyan-500"
        defaultOpen={task.maxContextTokens != null && task.maxContextTokens > 0}
      >
        <div className="space-y-3" data-testid="token-window-section">
          <p className="text-[10px] text-muted-foreground leading-relaxed">
            {t("llmEditor.tokenWindowDesc", "Replace step-count-based history with token-aware packing. When maxContextTokens is set (> 0), the engine fits as many recent turns as possible within the budget, optionally anchoring the opening turns.")}
          </p>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.maxContextTokens", "Max Context Tokens")}
              </label>
              <input
                type="number"
                value={task.maxContextTokens ?? -1}
                onChange={(e) =>
                  onChange({
                    ...task,
                    maxContextTokens: parseInt(e.target.value, 10) || -1,
                  })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                data-testid="max-context-tokens"
              />
              <p className="mt-0.5 text-[10px] text-muted-foreground">
                {t("llmEditor.maxContextTokensHint", "-1 = use step count instead")}
              </p>
            </div>
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.anchorFirstSteps", "Anchor First Steps")}
              </label>
              <input
                type="number"
                value={task.anchorFirstSteps ?? 2}
                onChange={(e) =>
                  onChange({
                    ...task,
                    anchorFirstSteps: parseInt(e.target.value, 10) || 0,
                  })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                data-testid="anchor-first-steps"
              />
              <p className="mt-0.5 text-[10px] text-muted-foreground">
                {t("llmEditor.anchorFirstStepsHint", "Opening turns always retained (0 = none)")}
              </p>
            </div>
          </div>
        </div>
      </EditorSection>

      {/* ══════ Conversation Memory (Rolling Summary) ══════ */}
      <EditorSection
        label={t("llmEditor.conversationMemory", "Conversation Memory")}
        icon={ScrollText}
        accent="text-teal-500"
        defaultOpen={!!(task.conversationSummary?.enabled)}
      >
        <div className="space-y-3" data-testid="conversation-memory-section">
          <p className="text-[10px] text-muted-foreground leading-relaxed">
            {t("llmEditor.conversationMemoryDesc", "Older turns are incrementally compressed into a rolling summary. The LLM sees: [system prompt + summary] + [recent N turns verbatim]. A built-in conversationRecall tool allows the agent to drill back into summarized turns on demand.")}
          </p>

          <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
            <input
              type="checkbox"
              checked={task.conversationSummary?.enabled ?? false}
              onChange={(e) =>
                onChange({
                  ...task,
                  conversationSummary: {
                    ...task.conversationSummary,
                    enabled: e.target.checked,
                    llmProvider: task.conversationSummary?.llmProvider ?? "anthropic",
                    llmModel: task.conversationSummary?.llmModel ?? "claude-sonnet-4-6",
                    maxSummaryTokens: task.conversationSummary?.maxSummaryTokens ?? 800,
                    recentWindowSteps: task.conversationSummary?.recentWindowSteps ?? 5,
                    maxRecallTurns: task.conversationSummary?.maxRecallTurns ?? 20,
                  },
                })
              }
              disabled={readOnly}
              className="h-3.5 w-3.5 rounded border-input accent-primary"
              data-testid="summary-enable"
            />
            <ScrollText className="h-3.5 w-3.5 text-teal-500" />
            {t("llmEditor.enableSummary", "Enable Rolling Summary")}
          </label>

          {task.conversationSummary?.enabled && (
            <div className="space-y-3 rounded-lg border border-teal-500/20 bg-teal-500/5 p-3">
              {/* Provider + Model */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("llmEditor.summaryProvider", "Summary Provider")}
                  </label>
                  <select
                    value={task.conversationSummary.llmProvider ?? "anthropic"}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        conversationSummary: { ...task.conversationSummary!, llmProvider: e.target.value },
                      })
                    }
                    disabled={readOnly}
                    className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                  >
                    {MODEL_TYPES.map((mt) => (
                      <option key={mt} value={mt}>{mt}</option>
                    ))}
                  </select>
                  <p className="mt-0.5 text-[10px] text-muted-foreground">
                    {t("llmEditor.summaryProviderHint", "Use a cheap/fast model for summarization")}
                  </p>
                </div>
                <div>
                  <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                    {t("llmEditor.summaryModel", "Summary Model")}
                  </label>
                  <input
                    type="text"
                    value={task.conversationSummary.llmModel ?? ""}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        conversationSummary: { ...task.conversationSummary!, llmModel: e.target.value },
                      })
                    }
                    readOnly={readOnly}
                    placeholder="claude-sonnet-4-6"
                    className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              </div>

              {/* Window + Recall + Tokens */}
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.recentWindow", "Recent Window (steps)")}
                  </label>
                  <input
                    type="number"
                    value={task.conversationSummary.recentWindowSteps ?? 5}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        conversationSummary: {
                          ...task.conversationSummary!,
                          recentWindowSteps: parseInt(e.target.value, 10) || 5,
                        },
                      })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                  <p className="mt-0.5 text-[10px] text-muted-foreground">
                    {t("llmEditor.recentWindowHint", "Turns kept verbatim")}
                  </p>
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.maxRecallTurns", "Max Recall Turns")}
                  </label>
                  <input
                    type="number"
                    value={task.conversationSummary.maxRecallTurns ?? 20}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        conversationSummary: {
                          ...task.conversationSummary!,
                          maxRecallTurns: parseInt(e.target.value, 10) || 20,
                        },
                      })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                  <p className="mt-0.5 text-[10px] text-muted-foreground">
                    {t("llmEditor.maxRecallHint", "Per recall invocation")}
                  </p>
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.maxSummaryTokens", "Max Summary Tokens")}
                  </label>
                  <input
                    type="number"
                    value={task.conversationSummary.maxSummaryTokens ?? 800}
                    onChange={(e) =>
                      onChange({
                        ...task,
                        conversationSummary: {
                          ...task.conversationSummary!,
                          maxSummaryTokens: parseInt(e.target.value, 10) || 800,
                        },
                      })
                    }
                    readOnly={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
              </div>

              {/* Options */}
              <label className="inline-flex items-center gap-2 text-xs text-foreground">
                <input
                  type="checkbox"
                  checked={task.conversationSummary.excludePropertiesFromSummary ?? true}
                  onChange={(e) =>
                    onChange({
                      ...task,
                      conversationSummary: {
                        ...task.conversationSummary!,
                        excludePropertiesFromSummary: e.target.checked,
                      },
                    })
                  }
                  disabled={readOnly}
                  className="h-3.5 w-3.5 rounded border-input accent-primary"
                />
                {t("llmEditor.excludeProps", "Exclude properties from summary")}
              </label>
              <p className="text-[10px] text-muted-foreground ps-5 -mt-2">
                {t("llmEditor.excludePropsDesc", "Skip facts already captured as persistent properties — focus on reasoning and implicit context")}
              </p>

              {/* Custom summarization prompt */}
              <div>
                <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.summarizationPrompt", "Custom Summarization Prompt (optional)")}
                </label>
                <ContentEditor
                  value={task.conversationSummary.summarizationPrompt ?? ""}
                  onChange={(v) =>
                    onChange({
                      ...task,
                      conversationSummary: {
                        ...task.conversationSummary!,
                        summarizationPrompt: v || undefined,
                      },
                    })
                  }
                  readOnly={readOnly}
                  language="prompt"
                  label={t("llmEditor.summarizationPrompt", "Custom Summarization Prompt")}
                  placeholder={t("llmEditor.summarizationPromptPlaceholder", "Leave empty to use the default structured prompt that preserves goals, decisions, reasoning, and tone.")}
                  testId="summarization-prompt"
                />
              </div>
            </div>
          )}
        </div>
      </EditorSection>

      {/* ══════ Retry Configuration ══════ */}
      <EditorSection
        label={t("llmEditor.retryConfig", "Retry Configuration")}
        icon={RotateCcw}
        accent="text-orange-500"
        defaultOpen={false}
      >
        <div className="space-y-2" data-testid="retry-section">
          <p className="text-[10px] text-muted-foreground">
            {t("llmEditor.retryDesc", "Configure automatic retries for failed LLM API calls with exponential backoff.")}
          </p>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                <RotateCcw className="inline h-3 w-3 me-1" />
                {t("llmEditor.retryMaxAttempts", "Max Attempts")}
              </label>
              <input
                type="number"
                value={task.retry?.maxAttempts ?? 3}
                onChange={(e) =>
                  onChange({ ...task, retry: { ...task.retry, maxAttempts: parseInt(e.target.value, 10) || 1 } })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.retryDelay", "Initial Delay (ms)")}
              </label>
              <input
                type="number"
                value={task.retry?.backoffDelayMs ?? 1000}
                onChange={(e) =>
                  onChange({ ...task, retry: { ...task.retry, backoffDelayMs: parseInt(e.target.value, 10) || 0 } })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.retryMultiplier", "Backoff Multiplier")}
              </label>
              <input
                type="number"
                step="0.1"
                value={task.retry?.backoffMultiplier ?? 2.0}
                onChange={(e) =>
                  onChange({ ...task, retry: { ...task.retry, backoffMultiplier: parseFloat(e.target.value) || 1.0 } })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
            <div>
              <label className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("llmEditor.retryMaxDelay", "Max Delay (ms)")}
              </label>
              <input
                type="number"
                value={task.retry?.maxBackoffDelayMs ?? 10000}
                onChange={(e) =>
                  onChange({ ...task, retry: { ...task.retry, maxBackoffDelayMs: parseInt(e.target.value, 10) || 0 } })
                }
                readOnly={readOnly}
                className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            </div>
          </div>
        </div>
      </EditorSection>
    </>
  );
}
