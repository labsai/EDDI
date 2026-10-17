import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDown, ArrowUp, Pencil, Plus, ShieldAlert, Trash2 } from "lucide-react";
import { EditorSection } from "../editor-section";
import { GuardrailSheet } from "./guardrail-sheet";
import {
  ACTION_TEXT,
  TYPE_TEXT,
  effectiveAction,
  isKnownGuardrailType,
  newGuardrail,
  outputGuardrailsBuffer,
  summarizeGuardrail,
  typesForPhase,
  type GuardrailPhaseName,
} from "./guardrails";
import type { TaskSectionProps } from "./task-section-props";
import type { GuardrailConfig, LlmTask } from "./types";

type ListField = "inputGuardrails" | "outputGuardrails";

const FIELD_OF: Record<GuardrailPhaseName, ListField> = {
  input: "inputGuardrails",
  output: "outputGuardrails",
};

/**
 * Writes a list back onto the task. An emptied list removes the field instead of
 * writing `[]`: an older server's strict configuration parser refuses a field it
 * does not know, and "no guardrails" is the same as the field being absent.
 */
function withList(task: LlmTask, field: ListField, list: GuardrailConfig[]): LlmTask {
  const { [field]: _drop, ...rest } = task;
  void _drop;
  return list.length > 0 ? { ...rest, [field]: list } : (rest as LlmTask);
}

/**
 * Input and output guardrails of an LLM task (EDDI 6.6+): two ordered lists, each
 * entry edited in a side sheet. A task with neither list runs no guardrail code.
 */
export function TaskGuardrailsSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<{ phase: GuardrailPhaseName; index: number } | null>(null);
  const count = (task.inputGuardrails?.length ?? 0) + (task.outputGuardrails?.length ?? 0);

  const editedList = editing ? task[FIELD_OF[editing.phase]] : undefined;
  const edited = editing ? editedList?.[editing.index] : undefined;

  return (
    <EditorSection
      label={t("llmEditor.grSectionTitle", "Guardrails")}
      icon={ShieldAlert}
      accent="text-rose-500"
      defaultOpen={count > 0}
    >
      <div className="space-y-4" data-testid="task-guardrails-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "llmEditor.grIntro",
            "Screen the user's message before the model is called, and the model's answer before it is stored or delivered. Nothing is screened unless you add a guardrail. Needs EDDI 6.6 or later: an older server ignores these lists and runs the agent unguarded.",
          )}
        </p>

        <GuardrailList phase="input" task={task} onChange={onChange} readOnly={readOnly} onEdit={setEditing} />
        <GuardrailList phase="output" task={task} onChange={onChange} readOnly={readOnly} onEdit={setEditing} />
      </div>

      {editing && edited && (
        <GuardrailSheet
          phase={editing.phase}
          value={edited}
          readOnly={readOnly}
          onClose={() => setEditing(null)}
          onChange={(g) => {
            const field = FIELD_OF[editing.phase];
            const list = [...(task[field] ?? [])];
            list[editing.index] = g;
            onChange({ ...task, [field]: list });
          }}
        />
      )}
    </EditorSection>
  );
}

function GuardrailList({
  phase,
  task,
  onChange,
  readOnly,
  onEdit,
}: TaskSectionProps & {
  phase: GuardrailPhaseName;
  onEdit: (target: { phase: GuardrailPhaseName; index: number }) => void;
}) {
  const { t } = useTranslation();
  const selectId = useId();
  const field = FIELD_OF[phase];
  const list = task[field] ?? [];
  const [pending, setPending] = useState<string>(typesForPhase(phase)[0]!);
  const buffers = phase === "output" && outputGuardrailsBuffer(list);

  const commit = (next: GuardrailConfig[]) => onChange(withList(task, field, next));
  const move = (index: number, by: -1 | 1) => {
    const next = [...list];
    const [item] = next.splice(index, 1);
    next.splice(index + by, 0, item!);
    commit(next);
  };

  const typeName = (type: string) =>
    isKnownGuardrailType(type)
      ? t(TYPE_TEXT[type][0], TYPE_TEXT[type][1])
      : t("llmEditor.unknownType", "{{type}} (custom)", { type });

  return (
    <div className="space-y-2" data-testid={`guardrails-${phase}`}>
      <div>
        <h4 className="text-xs font-semibold text-foreground">
          {phase === "input"
            ? t("llmEditor.grInput", "Input guardrails")
            : t("llmEditor.grOutput", "Output guardrails")}
        </h4>
        <p className="text-[10px] text-muted-foreground">
          {phase === "input"
            ? t("llmEditor.grInputHint", "Run in order on the user's message, before retrieval and the model call.")
            : t(
                "llmEditor.grOutputHint",
                "Run in order on the model's answer, before it is stored, parsed or delivered.",
              )}
        </p>
      </div>

      {list.length === 0 ? (
        <p className="text-[10px] italic text-muted-foreground" data-testid={`guardrails-${phase}-empty`}>
          {t("llmEditor.grEmpty", "None configured: nothing is screened on this side.")}
        </p>
      ) : (
        <ol className="space-y-1.5">
          {list.map((g, index) => {
            const action = effectiveAction(g);
            const summary = summarizeGuardrail(g);
            const name = typeName(g.type);
            return (
              <li
                key={index}
                className="flex items-center gap-2 rounded-lg border border-border bg-background px-2.5 py-1.5"
                data-testid={`guardrail-row-${phase}-${index}`}
              >
                <span className="w-4 text-center text-[10px] text-muted-foreground">{index + 1}</span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-xs font-medium text-foreground">{name}</span>
                    <span
                      className="rounded-full bg-secondary px-1.5 py-px text-[10px] text-secondary-foreground"
                      data-testid={`guardrail-action-badge-${phase}-${index}`}
                    >
                      {t(ACTION_TEXT[action][0], ACTION_TEXT[action][1])}
                    </span>
                  </div>
                  {summary && <p className="truncate text-[10px] text-muted-foreground">{summary}</p>}
                </div>
                <div className="flex shrink-0 items-center">
                  <RowButton
                    label={t("llmEditor.grMoveUp", "Move {{name}} up", { name })}
                    onClick={() => move(index, -1)}
                    disabled={readOnly || index === 0}
                    testId={`guardrail-up-${phase}-${index}`}
                  >
                    <ArrowUp className="h-3.5 w-3.5" />
                  </RowButton>
                  <RowButton
                    label={t("llmEditor.grMoveDown", "Move {{name}} down", { name })}
                    onClick={() => move(index, 1)}
                    disabled={readOnly || index === list.length - 1}
                    testId={`guardrail-down-${phase}-${index}`}
                  >
                    <ArrowDown className="h-3.5 w-3.5" />
                  </RowButton>
                  <RowButton
                    label={
                      readOnly
                        ? t("llmEditor.grView", "View {{name}}", { name })
                        : t("llmEditor.grEdit", "Edit {{name}}", { name })
                    }
                    onClick={() => onEdit({ phase, index })}
                    testId={`guardrail-edit-${phase}-${index}`}
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </RowButton>
                  <RowButton
                    label={t("llmEditor.grRemove", "Remove {{name}}", { name })}
                    onClick={() => commit(list.filter((_, i) => i !== index))}
                    disabled={readOnly}
                    testId={`guardrail-remove-${phase}-${index}`}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </RowButton>
                </div>
              </li>
            );
          })}
        </ol>
      )}

      {!readOnly && (
        <div className="flex items-center gap-2">
          <label htmlFor={selectId} className="sr-only">
            {t("llmEditor.grAddType", "Guardrail type to add")}
          </label>
          <select
            id={selectId}
            value={pending}
            onChange={(e) => setPending(e.target.value)}
            className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid={`guardrail-add-type-${phase}`}
          >
            {typesForPhase(phase).map((type) => (
              <option key={type} value={type}>
                {typeName(type)}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => {
              commit([...list, newGuardrail(pending)]);
              onEdit({ phase, index: list.length });
            }}
            className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
            data-testid={`guardrail-add-${phase}`}
          >
            <Plus className="h-3 w-3" />
            {t("llmEditor.grAdd", "Add guardrail")}
          </button>
        </div>
      )}

      {phase === "output" && (
        <p
          className={
            buffers
              ? "text-[10px] leading-relaxed text-amber-700 dark:text-amber-400"
              : "text-[10px] leading-relaxed text-muted-foreground"
          }
          role={buffers ? "status" : undefined}
          data-testid="guardrails-streaming-note"
          data-buffered={buffers ? "true" : "false"}
        >
          {buffers
            ? t(
                "llmEditor.grStreamingBuffered",
                "Streaming is off for this task: an output guardrail that blocks, redacts or reprompts makes it hold the answer until it is screened, and the client receives it once. Use warn to keep streaming.",
              )
            : t(
                "llmEditor.grStreamingNote",
                "Tokens already streamed cannot be taken back, so an output guardrail that blocks, redacts or reprompts makes the task hold its answer until it is screened. Warn-only guardrails keep streaming.",
              )}
        </p>
      )}
    </div>
  );
}

function RowButton({
  label,
  onClick,
  disabled,
  testId,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  testId: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className="rounded p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground disabled:opacity-30 disabled:hover:bg-transparent"
      data-testid={testId}
    >
      {children}
    </button>
  );
}
