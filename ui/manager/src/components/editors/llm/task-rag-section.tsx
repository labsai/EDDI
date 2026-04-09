import { useTranslation } from "react-i18next";
import { Database, Plus, X, Trash2, AlertTriangle } from "lucide-react";
import { EditorSection } from "../editor-section";
import type { TaskSectionProps } from "./task-section-props";

/**
 * RAG (Retrieval Augmented Generation) configuration section.
 * Supports three modes: explicit knowledge bases, workflow auto-discovery, and httpCall RAG.
 */
export function TaskRagSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();

  return (
    <EditorSection
      label={t("llmEditor.ragConfig", "RAG (Knowledge Retrieval)")}
      icon={Database}
      accent="text-emerald-500"
      defaultOpen={false}
    >
      <div className="space-y-4" data-testid="rag-section">
        <p className="text-[10px] text-muted-foreground">
          {t("llmEditor.ragDesc", "Augment LLM responses with relevant documents from knowledge bases. Three modes can be combined.")}
        </p>

        {/* ── Mode 1: Explicit Knowledge Bases ── */}
        <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-foreground">
              {t("llmEditor.knowledgeBases", "Knowledge Bases")}
            </span>
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange({
                  ...task,
                  knowledgeBases: [
                    ...(task.knowledgeBases ?? []),
                    { name: "", maxResults: 5, minScore: 0.6 },
                  ],
                })}
                className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] font-medium text-primary hover:bg-primary/10 transition-colors"
                data-testid="add-kb-ref"
              >
                <Plus className="h-3 w-3" />
                {t("llmEditor.addKnowledgeBase", "Add KB Reference")}
              </button>
            )}
          </div>
          <p className="text-[10px] text-muted-foreground">
            {t("llmEditor.knowledgeBasesHint", "Explicitly reference knowledge bases by name. Each name must match a RagConfiguration in the workflow.")}
          </p>
          {(task.knowledgeBases ?? []).map((kb, kbIdx) => (
            <div key={kbIdx} className="rounded-md border border-border bg-background p-2.5 space-y-2">
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={kb.name ?? ""}
                  onChange={(e) => {
                    const updated = [...(task.knowledgeBases ?? [])];
                    updated[kbIdx] = { ...kb, name: e.target.value || undefined };
                    onChange({ ...task, knowledgeBases: updated });
                  }}
                  readOnly={readOnly}
                  placeholder={t("llmEditor.kbNamePlaceholder", "e.g. product-docs")}
                  className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  data-testid={`kb-name-${kbIdx}`}
                />
                {!readOnly && (
                  <button
                    type="button"
                    onClick={() => {
                      const updated = (task.knowledgeBases ?? []).filter((_, i) => i !== kbIdx);
                      onChange({ ...task, knowledgeBases: updated.length > 0 ? updated : undefined });
                    }}
                    className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                  >
                    <X className="h-3 w-3" />
                  </button>
                )}
              </div>
              <div className="grid grid-cols-3 gap-2">
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.ragMaxResults", "Max Results")}
                  </label>
                  <input
                    type="number"
                    value={kb.maxResults ?? ""}
                    onChange={(e) => {
                      const updated = [...(task.knowledgeBases ?? [])];
                      updated[kbIdx] = { ...kb, maxResults: e.target.value ? parseInt(e.target.value, 10) : undefined };
                      onChange({ ...task, knowledgeBases: updated });
                    }}
                    readOnly={readOnly}
                    placeholder="5"
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.ragMinScore", "Min Score")}
                  </label>
                  <input
                    type="number"
                    step="0.05"
                    min="0"
                    max="1"
                    value={kb.minScore ?? ""}
                    onChange={(e) => {
                      const updated = [...(task.knowledgeBases ?? [])];
                      updated[kbIdx] = { ...kb, minScore: e.target.value ? parseFloat(e.target.value) : undefined };
                      onChange({ ...task, knowledgeBases: updated });
                    }}
                    readOnly={readOnly}
                    placeholder="0.6"
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.injectionStrategy", "Injection")}
                  </label>
                  <select
                    value={kb.injectionStrategy ?? "system_message"}
                    onChange={(e) => {
                      const updated = [...(task.knowledgeBases ?? [])];
                      updated[kbIdx] = { ...kb, injectionStrategy: e.target.value };
                      onChange({ ...task, knowledgeBases: updated });
                    }}
                    disabled={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                  >
                    <option value="system_message">System Message</option>
                    <option value="user_message">User Message</option>
                  </select>
                </div>
              </div>
            </div>
          ))}
          {(task.knowledgeBases ?? []).length === 0 && (
            <p className="text-[10px] text-muted-foreground/60 italic">
              {t("llmEditor.noKnowledgeBases", "No knowledge bases referenced")}
            </p>
          )}
        </div>

        {/* ── Mode 2: Auto-Discovery ── */}
        <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
          <label className="flex items-center gap-2 cursor-pointer">
            <input
              type="checkbox"
              checked={task.enableWorkflowRag ?? false}
              onChange={(e) => onChange({ ...task, enableWorkflowRag: e.target.checked || undefined })}
              disabled={readOnly}
              className="rounded"
              data-testid="enable-workflow-rag"
            />
            <span className="text-xs font-semibold text-foreground">
              {t("llmEditor.enableWorkflowRag", "Auto-Discover Workflow RAG")}
            </span>
          </label>
          <p className="text-[10px] text-muted-foreground">
            {t("llmEditor.workflowRagHint", "Automatically discovers all RAG steps from the workflow. Only used when no explicit knowledge bases are listed above.")}
          </p>
          {task.enableWorkflowRag && (
            <div className="ms-6 space-y-2 border-s-2 border-primary/20 ps-3">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("llmEditor.ragDefaults", "Default Retrieval Parameters")}
              </span>
              <div className="grid grid-cols-3 gap-2">
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.ragMaxResults", "Max Results")}
                  </label>
                  <input
                    type="number"
                    value={task.ragDefaults?.maxResults ?? ""}
                    onChange={(e) =>
                      onChange({ ...task, ragDefaults: { ...task.ragDefaults, maxResults: e.target.value ? parseInt(e.target.value, 10) : undefined } })
                    }
                    readOnly={readOnly}
                    placeholder="5"
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.ragMinScore", "Min Score")}
                  </label>
                  <input
                    type="number"
                    step="0.05"
                    min="0"
                    max="1"
                    value={task.ragDefaults?.minScore ?? ""}
                    onChange={(e) =>
                      onChange({ ...task, ragDefaults: { ...task.ragDefaults, minScore: e.target.value ? parseFloat(e.target.value) : undefined } })
                    }
                    readOnly={readOnly}
                    placeholder="0.6"
                    className="h-7 w-full rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <div>
                  <label className="mb-0.5 block text-[10px] text-muted-foreground">
                    {t("llmEditor.injectionStrategy", "Injection")}
                  </label>
                  <select
                    value={task.ragDefaults?.injectionStrategy ?? "system_message"}
                    onChange={(e) =>
                      onChange({ ...task, ragDefaults: { ...task.ragDefaults, injectionStrategy: e.target.value } })
                    }
                    disabled={readOnly}
                    className="h-7 w-full rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
                  >
                    <option value="system_message">System Message</option>
                    <option value="user_message">User Message</option>
                  </select>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* ── Mode 3: httpCall RAG (zero-infra) ── */}
        <div className="rounded-md border border-border bg-card/30 p-3 space-y-2">
          <span className="text-xs font-semibold text-foreground">
            {t("llmEditor.httpCallRag", "httpCall RAG (Zero Infrastructure)")}
          </span>
          <p className="text-[10px] text-muted-foreground">
            {t("llmEditor.httpCallRagHint", "Execute a named httpCall before the LLM call. The response is injected as context — no vector store needed.")}
          </p>
          <input
            type="text"
            value={task.httpCallRag ?? ""}
            onChange={(e) => onChange({ ...task, httpCallRag: e.target.value || undefined })}
            readOnly={readOnly}
            placeholder={t("llmEditor.httpCallRagPlaceholder", "e.g. search_docs, query_wiki")}
            dir="ltr"
            className="h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="httpcall-rag"
          />
        </div>

        {/* ── Legacy (backward compat, collapsed) ── */}
        {task.retrievalAugmentor && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/5 p-3 space-y-2">
            <div className="flex items-center gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />
              <span className="text-xs font-semibold text-amber-600 dark:text-amber-400">
                {t("llmEditor.legacyRag", "Legacy RAG (deprecated)")}
              </span>
            </div>
            <p className="text-[10px] text-muted-foreground">
              {t("llmEditor.legacyRagHint", "This configuration uses the deprecated retrievalAugmentor format. Migrate to the modes above.")}
            </p>
            <div className="grid grid-cols-2 gap-2 opacity-60">
              <div className="col-span-2">
                <label className="mb-0.5 block text-[10px] text-muted-foreground">httpCall</label>
                <input type="text" value={task.retrievalAugmentor.httpCall ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 font-mono text-xs" />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">embeddingModel</label>
                <input type="text" value={task.retrievalAugmentor.embeddingModel ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 text-xs" />
              </div>
              <div>
                <label className="mb-0.5 block text-[10px] text-muted-foreground">embeddingStore</label>
                <input type="text" value={task.retrievalAugmentor.embeddingStore ?? ""} readOnly className="h-7 w-full rounded border border-input bg-background px-2 text-xs" />
              </div>
            </div>
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange({ ...task, retrievalAugmentor: undefined })}
                className="inline-flex items-center gap-1 rounded px-2 py-1 text-[10px] text-amber-600 dark:text-amber-400 hover:bg-amber-500/10 transition-colors"
              >
                <Trash2 className="h-3 w-3" />
                {t("llmEditor.removeLegacyRag", "Remove Legacy Config")}
              </button>
            )}
          </div>
        )}
      </div>
    </EditorSection>
  );
}
