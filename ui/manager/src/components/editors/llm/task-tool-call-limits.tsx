import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NumberInput } from '../number-input';
import type { TaskSectionProps } from './task-section-props';
import type { LlmTask } from './types';
import {
  DEFAULT_MAX_TOOL_CALLS_PER_ITERATION,
  DEFAULT_MAX_TOOL_CALLS_PER_TURN,
  isUncappedToolCalls,
} from './tool-call-limits';

type RepeatGuard = NonNullable<LlmTask['toolRepeatGuard']>;

/** Server defaults of the repeat guard, shown as placeholders only. */
const DEFAULT_MAX_IDENTICAL_FAILURES = 2;
const DEFAULT_MAX_REFUSALS_PER_TURN = 3;

/**
 * The guard object with one field changed. A cleared field is dropped, and an
 * object left with no field at all is dropped too: the field is only ever
 * written when the author changed something, because an older backend's strict
 * parser refuses a field it does not know.
 */
function withGuard(task: LlmTask, patch: Partial<RepeatGuard>): LlmTask {
  const next: Record<string, unknown> = { ...task.toolRepeatGuard, ...patch };
  for (const key of Object.keys(next)) {
    const value = next[key];
    if (value === undefined || (Array.isArray(value) && value.length === 0))
      delete next[key];
  }
  return {
    ...task,
    toolRepeatGuard:
      Object.keys(next).length > 0 ? (next as RepeatGuard) : undefined,
  };
}

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
export function TaskToolCallLimits({
  task,
  onChange,
  readOnly,
}: TaskSectionProps) {
  const { t } = useTranslation();
  const perIterationId = useId();
  const perTurnId = useId();
  const hintId = useId();
  const enabledId = useId();
  const failuresId = useId();
  const refusalsId = useId();
  const identicalId = useId();
  const exemptId = useId();
  const guardHintId = useId();
  const [exemptText, setExemptText] = useState(
    (task.toolRepeatGuard?.exemptTools ?? []).join(', '),
  );
  const guard = task.toolRepeatGuard;

  const inputCls =
    'h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring aria-[invalid=true]:border-destructive';

  const uncapped =
    isUncappedToolCalls(task.maxToolCallsPerIteration) ||
    isUncappedToolCalls(task.maxToolCallsPerTurn);

  return (
    <div className="space-y-2" data-testid="task-tool-call-limits">
      <div className="flex flex-wrap items-center gap-2">
        <label
          htmlFor={perIterationId}
          className="text-xs text-foreground whitespace-nowrap"
        >
          {t(
            'llmEditor.maxToolCallsPerIteration',
            'Max tool calls per response',
          )}
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
          {t('llmEditor.maxToolCallsPerIterationDefault', '(default {{n}})', {
            n: DEFAULT_MAX_TOOL_CALLS_PER_ITERATION,
          })}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <label
          htmlFor={perTurnId}
          className="text-xs text-foreground whitespace-nowrap"
        >
          {t('llmEditor.maxToolCallsPerTurn', 'Max tool calls per turn')}
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
          {t('llmEditor.maxToolCallsPerTurnDefault', '(default {{n}})', {
            n: DEFAULT_MAX_TOOL_CALLS_PER_TURN,
          })}
        </span>
      </div>
      <p
        id={hintId}
        className="text-[10px] leading-relaxed text-muted-foreground"
      >
        {t(
          'llmEditor.maxToolCallsHint',
          'Calls past a cap are not run: each gets a NOT_EXECUTED result telling the model the limit was reached, and the model answers with what it has. Refused calls never reach the approval gate. The per-turn count includes calls made before an approval pause. -1 or 0 removes a cap. Needs EDDI 6.6 or later — leave blank on an older server.',
        )}
      </p>
      {uncapped && (
        <p
          className="text-[10px] text-amber-700 dark:text-amber-400"
          role="status"
          data-testid="task-tool-calls-uncapped"
        >
          {t(
            'llmEditor.maxToolCallsUncapped',
            'A cap is switched off: the model can run any number of tool calls there, bounded only by Max Tool Iterations.',
          )}
        </p>
      )}
      <div
        className="space-y-2 border-t border-border pt-2"
        data-testid="task-repeat-guard"
      >
        <p className="text-xs font-medium text-foreground">
          {t('llmEditor.repeatGuardTitle', 'Repeated failing tool calls')}
        </p>
        <label
          htmlFor={enabledId}
          className="flex items-center gap-2 text-xs text-foreground"
        >
          <input
            id={enabledId}
            type="checkbox"
            checked={guard?.enabled !== false}
            disabled={readOnly}
            aria-describedby={guardHintId}
            onChange={(e) =>
              onChange(
                withGuard(task, {
                  enabled: e.target.checked ? undefined : false,
                }),
              )
            }
            data-testid="task-repeat-guard-enabled"
          />
          {t('llmEditor.repeatGuardEnabled', 'Block repeated failing calls')}
        </label>
        <div className="flex flex-wrap items-center gap-2">
          <label
            htmlFor={failuresId}
            className="text-xs text-foreground whitespace-nowrap"
          >
            {t('llmEditor.maxIdenticalFailures', 'Identical failures allowed')}
          </label>
          <NumberInput
            id={failuresId}
            integer
            value={guard?.maxIdenticalFailures}
            onChange={(v) =>
              onChange(withGuard(task, { maxIdenticalFailures: v }))
            }
            readOnly={readOnly}
            placeholder={String(DEFAULT_MAX_IDENTICAL_FAILURES)}
            aria-describedby={guardHintId}
            className={inputCls}
            data-testid="task-repeat-guard-max-failures"
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label
            htmlFor={refusalsId}
            className="text-xs text-foreground whitespace-nowrap"
          >
            {t('llmEditor.maxRefusalsPerTurn', 'Refusals before stopping')}
          </label>
          <NumberInput
            id={refusalsId}
            integer
            value={guard?.maxRefusalsPerTurn}
            onChange={(v) =>
              onChange(withGuard(task, { maxRefusalsPerTurn: v }))
            }
            readOnly={readOnly}
            placeholder={String(DEFAULT_MAX_REFUSALS_PER_TURN)}
            aria-describedby={guardHintId}
            className={inputCls}
            data-testid="task-repeat-guard-max-refusals"
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label
            htmlFor={identicalId}
            className="text-xs text-foreground whitespace-nowrap"
          >
            {t('llmEditor.maxIdenticalCalls', 'Max identical successful calls')}
          </label>
          <NumberInput
            id={identicalId}
            integer
            value={guard?.maxIdenticalCalls}
            onChange={(v) =>
              onChange(withGuard(task, { maxIdenticalCalls: v }))
            }
            readOnly={readOnly}
            placeholder="-1"
            aria-describedby={guardHintId}
            className={inputCls}
            data-testid="task-repeat-guard-max-identical"
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label
            htmlFor={exemptId}
            className="text-xs text-foreground whitespace-nowrap"
          >
            {t('llmEditor.exemptTools', 'Exempt tools (comma-separated)')}
          </label>
          <input
            id={exemptId}
            type="text"
            value={exemptText}
            readOnly={readOnly}
            aria-describedby={guardHintId}
            onChange={(e) => {
              setExemptText(e.target.value);
              const tools = e.target.value
                .split(',')
                .map((x) => x.trim())
                .filter(Boolean);
              onChange(withGuard(task, { exemptTools: tools }));
            }}
            className="h-7 w-64 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="task-repeat-guard-exempt"
          />
        </div>
        <p
          id={guardHintId}
          className="text-[10px] leading-relaxed text-muted-foreground"
        >
          {t(
            'llmEditor.repeatGuardHint',
            'A tool call that already failed identically, with nothing succeeding since, is answered NOT_EXECUTED instead of being run again; after the refusal limit the turn ends with a fixed message. On by default; leave fields blank to keep the defaults. A terminal failure (for example HTTP 403/404) is refused on the very next identical call, a retryable one after the failures allowed. Max identical successful calls is off unless set (-1 or 0 = unlimited). Needs EDDI 6.6 or later - leave untouched on an older server.',
          )}
        </p>
      </div>
    </div>
  );
}
