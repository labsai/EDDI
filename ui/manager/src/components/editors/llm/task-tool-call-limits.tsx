import { useId } from "react";
import { useTranslation } from "react-i18next";
import { NumberInput } from "../number-input";
import type { TaskSectionProps } from "./task-section-props";
import {
  DEFAULT_MAX_TOOL_CALLS_PER_ITERATION,
  DEFAULT_MAX_TOOL_CALLS_PER_TURN,
  isUncappedToolCalls,
} from "./tool-call-limits";

/**
 * The two tool-call caps of the tool loop (EDDI 6.6+): `maxToolCallsPerIteration`
 * bounds the calls executed from ONE model response, `maxToolCallsPerTurn` the
 * calls executed across the whole turn, HITL resumes included.
 *
 * `maxToolIterations` alone bounds rounds, not calls — a model that answered with
 * fifty parallel calls in one response used to have all fifty executed. Past a
 * cap the engine does not fail the turn: every excess call is answered with a
 * `NOT_EXECUTED` result (so the provider still gets one result per call) and the
 * model answers with what it has. Refused calls never reach the approval gate.
 *
 * Both fields stay absent unless the author types a value. An older backend's
 * strict configuration parser refuses a field it does not know, so writing the
 * defaults out would make every save fail there.
 */
export function TaskToolCallLimits({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const perIterationId = useId();
  const perTurnId = useId();
  const hintId = useId();

  const inputCls =
    "h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring aria-[invalid=true]:border-destructive";

  const uncapped =
    isUncappedToolCalls(task.maxToolCallsPerIteration) || isUncappedToolCalls(task.maxToolCallsPerTurn);

  return (
    <div className="space-y-2" data-testid="task-tool-call-limits">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={perIterationId} className="text-xs text-foreground whitespace-nowrap">
          {t("llmEditor.maxToolCallsPerIteration", "Max tool calls per response")}
        </label>
        <NumberInput
          id={perIterationId}
          integer
          value={task.maxToolCallsPerIteration}
          onChange={(v) => onChange({ ...task, maxToolCallsPerIteration: v })}
          readOnly={readOnly}
          placeholder={String(DEFAULT_MAX_TOOL_CALLS_PER_ITERATION)}
          aria-describedby={hintId}
          className={inputCls}
          data-testid="task-max-tool-calls-per-iteration"
        />
        <span className="text-[10px] text-muted-foreground">
          {t("llmEditor.maxToolCallsPerIterationDefault", "(default {{n}})", {
            n: DEFAULT_MAX_TOOL_CALLS_PER_ITERATION,
          })}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={perTurnId} className="text-xs text-foreground whitespace-nowrap">
          {t("llmEditor.maxToolCallsPerTurn", "Max tool calls per turn")}
        </label>
        <NumberInput
          id={perTurnId}
          integer
          value={task.maxToolCallsPerTurn}
          onChange={(v) => onChange({ ...task, maxToolCallsPerTurn: v })}
          readOnly={readOnly}
          placeholder={String(DEFAULT_MAX_TOOL_CALLS_PER_TURN)}
          aria-describedby={hintId}
          className={inputCls}
          data-testid="task-max-tool-calls-per-turn"
        />
        <span className="text-[10px] text-muted-foreground">
          {t("llmEditor.maxToolCallsPerTurnDefault", "(default {{n}})", {
            n: DEFAULT_MAX_TOOL_CALLS_PER_TURN,
          })}
        </span>
      </div>
      <p id={hintId} className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.maxToolCallsHint",
          "Calls past a cap are not run: each gets a NOT_EXECUTED result telling the model the limit was reached, and the model answers with what it has. Refused calls never reach the approval gate. The per-turn count includes calls made before an approval pause. -1 or 0 removes a cap. Needs EDDI 6.6 or later — leave blank on an older server.",
        )}
      </p>
      {uncapped && (
        <p
          className="text-[10px] text-amber-700 dark:text-amber-400"
          role="status"
          data-testid="task-tool-calls-uncapped"
        >
          {t(
            "llmEditor.maxToolCallsUncapped",
            "A cap is switched off: the model can run any number of tool calls there, bounded only by Max Tool Iterations.",
          )}
        </p>
      )}
    </div>
  );
}
