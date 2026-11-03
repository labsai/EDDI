import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, ArrowDown, ArrowUp, Pencil, Plus, ShieldCheck, Trash2 } from "lucide-react";
import { EditorSection } from "../editor-section";
import { GuardrailSheet } from "./guardrail-sheet";
import { ACTION_TEXT, TYPE_TEXT, effectiveAction, isKnownGuardrailType, summarizeGuardrail, typesForPhase } from "./guardrails";
import {
  MAX_TOOL_CALL_GUARDRAILS,
  fieldsToText,
  linesOf,
  newToolCallGuardrail,
  pointerProblem,
  textToFields,
  toolPatternProblem,
  withFields,
  withToolCallGuardrails,
  withTools,
} from "./tool-call-guardrails";
import type { TaskSectionProps } from "./task-section-props";
import type { ToolCallGuardrailConfig } from "./types";

/** Model-backed types: without `fields` they make one model call per string. */
const MODEL_BACKED = new Set(["judge", "classifier"]);

/**
 * Guardrails on the arguments of a task's tool calls (EDDI 6.7+): one ordered
 * list, each entry edited in the guardrail side sheet plus what it applies to —
 * the tools (tool-approval patterns) and the fields (JSON pointers).
 */
export function TaskToolCallGuardrailsSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const selectId = useId();
  const list = task.toolCallGuardrails ?? [];
  const [editing, setEditing] = useState<number | null>(null);
  const [pending, setPending] = useState<string>(typesForPhase("tool_call")[0]!);
  const edited = editing !== null ? list[editing] : undefined;

  const commit = (next: ToolCallGuardrailConfig[]) => onChange(withToolCallGuardrails(task, next));
  const replace = (index: number, entry: ToolCallGuardrailConfig) => {
    const next = [...list];
    next[index] = entry;
    commit(next);
  };
  const move = (index: number, by: -1 | 1) => {
    const next = [...list];
    const [item] = next.splice(index, 1);
    next.splice(index + by, 0, item!);
    commit(next);
  };
  const typeName = (type: string) =>
    isKnownGuardrailType(type) ? t(TYPE_TEXT[type][0], TYPE_TEXT[type][1]) : t("llmEditor.unknownType", "{{type}} (custom)", { type });

  return (
    <EditorSection
      label={t("toolCallGuardrails.sectionTitle", "Tool-call guardrails")}
      icon={ShieldCheck}
      accent="text-rose-500"
      defaultOpen={list.length > 0}
    >
      <div className="space-y-3" data-testid="task-tool-call-guardrails-section">
        <p className="text-[10px] leading-relaxed text-muted-foreground">
          {t(
            "toolCallGuardrails.intro",
            "Screen the arguments of the tool calls the model asks for, before the approval gate: a refused call never reaches an approver, and a redacted one reaches them already redacted. An approved or edited call is checked again before it runs, and then any finding refuses it. Needs {{productName}} 6.7 or later: an older server ignores this list.",
          )}
        </p>

        {list.length === 0 ? (
          <p className="text-[10px] italic text-muted-foreground" data-testid="tool-call-guardrails-empty">
            {t("toolCallGuardrails.empty", "None configured: tool calls run as the model wrote them.")}
          </p>
        ) : (
          <ol className="space-y-1.5">
            {list.map((g, index) => {
              const action = effectiveAction(g);
              const name = typeName(g.type);
              const summary = summarizeGuardrail(g);
              const fields =
                g.fields && g.fields.length > 0
                  ? g.fields.map((f) => (f === "" ? '""' : f)).join(", ")
                  : t("toolCallGuardrails.allStrings", "every string");
              return (
                <li
                  key={index}
                  className="flex items-center gap-2 rounded-lg border border-border bg-background px-2.5 py-1.5"
                  data-testid={`tool-call-guardrail-row-${index}`}
                >
                  <span className="w-4 text-center text-[10px] text-muted-foreground">{index + 1}</span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-medium text-foreground">{name}</span>
                      <span className="rounded-full bg-secondary px-1.5 py-px text-[10px] text-secondary-foreground">
                        {t(ACTION_TEXT[action][0], ACTION_TEXT[action][1])}
                      </span>
                    </div>
                    <p className="truncate text-[10px] text-muted-foreground" data-testid={`tool-call-guardrail-target-${index}`}>
                      {(g.tools ?? []).join(", ") || t("toolCallGuardrails.noTools", "no tools yet")} · {fields}
                      {summary ? ` · ${summary}` : ""}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center">
                    <RowButton
                      label={t("llmEditor.grMoveUp", "Move {{name}} up", { name })}
                      onClick={() => move(index, -1)}
                      disabled={readOnly || index === 0}
                      testId={`tool-call-guardrail-up-${index}`}
                    >
                      <ArrowUp className="h-3.5 w-3.5" />
                    </RowButton>
                    <RowButton
                      label={t("llmEditor.grMoveDown", "Move {{name}} down", { name })}
                      onClick={() => move(index, 1)}
                      disabled={readOnly || index === list.length - 1}
                      testId={`tool-call-guardrail-down-${index}`}
                    >
                      <ArrowDown className="h-3.5 w-3.5" />
                    </RowButton>
                    <RowButton
                      label={
                        readOnly ? t("llmEditor.grView", "View {{name}}", { name }) : t("llmEditor.grEdit", "Edit {{name}}", { name })
                      }
                      onClick={() => setEditing(index)}
                      testId={`tool-call-guardrail-edit-${index}`}
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </RowButton>
                    <RowButton
                      label={t("llmEditor.grRemove", "Remove {{name}}", { name })}
                      onClick={() => commit(list.filter((_, i) => i !== index))}
                      disabled={readOnly}
                      testId={`tool-call-guardrail-remove-${index}`}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </RowButton>
                  </div>
                </li>
              );
            })}
          </ol>
        )}

        {!readOnly && list.length < MAX_TOOL_CALL_GUARDRAILS && (
          <div className="flex items-center gap-2">
            <label htmlFor={selectId} className="sr-only">
              {t("llmEditor.grAddType", "Guardrail type to add")}
            </label>
            <select
              id={selectId}
              value={pending}
              onChange={(e) => setPending(e.target.value)}
              className="h-7 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="tool-call-guardrail-add-type"
            >
              {typesForPhase("tool_call").map((type) => (
                <option key={type} value={type}>
                  {typeName(type)}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => {
                commit([...list, newToolCallGuardrail(pending)]);
                setEditing(list.length);
              }}
              className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
              data-testid="tool-call-guardrail-add"
            >
              <Plus className="h-3 w-3" />
              {t("toolCallGuardrails.add", "Add tool-call guardrail")}
            </button>
          </div>
        )}
      </div>

      {editing !== null && edited && (
        <GuardrailSheet
          phase="tool_call"
          value={edited}
          readOnly={readOnly}
          onClose={() => setEditing(null)}
          // The sheet edits the guardrail part; tools and fields ride along unchanged.
          onChange={(g) => replace(editing, g as ToolCallGuardrailConfig)}
          extra={
            <TargetFields
              key={editing}
              entry={edited}
              readOnly={readOnly}
              onChange={(entry) => replace(editing, entry)}
            />
          }
        />
      )}
    </EditorSection>
  );
}

/**
 * The tools and fields of one entry, one per line. Each textarea keeps its own
 * text so a line being typed is not swallowed by the parse.
 */
function TargetFields({
  entry,
  readOnly,
  onChange,
}: {
  entry: ToolCallGuardrailConfig;
  readOnly?: boolean;
  onChange: (entry: ToolCallGuardrailConfig) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const [toolsText, setToolsText] = useState(() => (entry.tools ?? []).join("\n"));
  const [fieldsText, setFieldsText] = useState(() => fieldsToText(entry.fields));
  const tools = linesOf(toolsText);
  const fields = textToFields(fieldsText);
  const badTool = tools.find(toolPatternProblem);
  const badField = fields.find(pointerProblem);
  const AREA =
    "w-full rounded-md border border-input bg-background px-2 py-1.5 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-3" data-testid="tool-call-guardrail-targets">
      <div>
        <label htmlFor={`${uid}-tools`} className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("toolCallGuardrails.toolsLabel", "Tools")}
        </label>
        <textarea
          id={`${uid}-tools`}
          rows={2}
          value={toolsText}
          onChange={(e) => {
            setToolsText(e.target.value);
            onChange(withTools(entry, linesOf(e.target.value)));
          }}
          readOnly={readOnly}
          aria-invalid={tools.length === 0 || badTool !== undefined}
          className={AREA}
          data-testid="tool-call-guardrail-tools"
        />
        <p className="mt-1 text-[10px] text-muted-foreground">
          {t(
            "toolCallGuardrails.toolsHint",
            "One pattern per line, in the language of tool approvals: mcp:add_issue_comment, http.post:/issues/*, builtin:*.",
          )}
        </p>
        {tools.length === 0 && (
          <Notice testId="tool-call-guardrail-tools-required">
            {t("toolCallGuardrails.toolsRequired", "Name at least one tool; the server refuses an entry without one.")}
          </Notice>
        )}
        {badTool !== undefined && (
          <Notice testId="tool-call-guardrail-tools-invalid">
            {t("toolCallGuardrails.toolsInvalid", "{{pattern}} is not a valid tool pattern.", { pattern: badTool })}
          </Notice>
        )}
      </div>
      <div>
        <label htmlFor={`${uid}-fields`} className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          {t("toolCallGuardrails.fieldsLabel", "Fields")}
        </label>
        <textarea
          id={`${uid}-fields`}
          rows={2}
          value={fieldsText}
          onChange={(e) => {
            setFieldsText(e.target.value);
            onChange(withFields(entry, textToFields(e.target.value)));
          }}
          readOnly={readOnly}
          aria-invalid={badField !== undefined}
          className={AREA}
          data-testid="tool-call-guardrail-fields"
        />
        <p className="mt-1 text-[10px] text-muted-foreground">
          {t(
            "toolCallGuardrails.fieldsHint",
            'One JSON pointer per line, e.g. /body or /items/*/text; "" is the whole arguments. Empty: every string in the arguments.',
          )}
        </p>
        {badField !== undefined && (
          <Notice testId="tool-call-guardrail-fields-invalid">
            {t("toolCallGuardrails.fieldsInvalid", "{{field}} is not a valid field pointer.", { field: badField })}
          </Notice>
        )}
        {fields.length === 0 && MODEL_BACKED.has(entry.type) && (
          <Notice testId="tool-call-guardrail-fields-model">
            {t(
              "toolCallGuardrails.noFieldsModel",
              "Without fields a model-backed check makes one model call per string in the arguments. Name the field it should read.",
            )}
          </Notice>
        )}
      </div>
    </div>
  );
}

function Notice({ testId, children }: { testId: string; children: React.ReactNode }) {
  return (
    <p className="mt-1 flex items-start gap-1 text-[10px] text-warn-700 dark:text-warn-400" role="status" data-testid={testId}>
      <AlertTriangle className="mt-px h-3 w-3 shrink-0" />
      <span>{children}</span>
    </p>
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
