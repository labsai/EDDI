import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Plus, Trash2, X } from "lucide-react";
import { ProviderSelect } from "@/components/shared/provider-select";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { NumberInput } from "../number-input";
import { modelParamSpec } from "./model-params";
import {
  DEFAULT_REPROMPT_RETRIES,
  EXPRESSION_FIX_MAX_CHARS,
  EXPRESSION_MAX_CHARS,
  JUDGE_DEFAULT_TIMEOUT_MS,
  JUDGE_MAX_FIX_CHARS,
  JUDGE_MAX_ISSUES,
  MAX_REPROMPT_RETRIES,
  ON_EXHAUSTED,
  PII_KINDS,
  actionsForPhase,
  classifierNeedsSafeLabel,
  effectiveAction,
  enabledPiiKinds,
  expressionProblems,
  isKnownGuardrailType,
  judgeIssuesProblem,
  parseJudgeIssues,
  serializeJudgeIssues,
  typeHasRewrite,
  ACTION_TEXT,
  TYPE_TEXT,
  typesForPhase,
  withAction,
  withMaxRetries,
  withOnExhausted,
  withParameter,
  withPiiKinds,
  withText,
  withType,
  type ExpressionProblem,
  type GuardrailPhaseName,
  type GuardrailTypeName,
  type JudgeIssue,
  type JudgeIssuesProblem,
} from "./guardrails";
import type { GuardrailActionName, GuardrailConfig, GuardrailOnExhausted } from "./types";

const FIELD =
  "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60";
const AREA =
  "w-full rounded-md border border-input bg-background px-2 py-1.5 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60";

function Field({
  label,
  htmlFor,
  hint,
  children,
}: {
  label: string;
  htmlFor?: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div>
      <label
        htmlFor={htmlFor}
        className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground"
      >
        {label}
      </label>
      {children}
      {hint && <p className="mt-0.5 text-[10px] leading-relaxed text-muted-foreground">{hint}</p>}
    </div>
  );
}

function Warning({ children, testId }: { children: ReactNode; testId?: string }) {
  return (
    <p
      className="flex items-start gap-1.5 text-[10px] text-amber-700 dark:text-amber-400"
      role="status"
      data-testid={testId}
    >
      <AlertTriangle className="mt-px h-3 w-3 shrink-0" aria-hidden="true" />
      <span>{children}</span>
    </p>
  );
}

interface GuardrailSheetProps {
  phase: GuardrailPhaseName;
  value: GuardrailConfig;
  onChange: (g: GuardrailConfig) => void;
  onClose: () => void;
  readOnly?: boolean;
}

/**
 * Edits ONE guardrail in a side sheet. Edits go straight to the task (the list
 * behind it shows them live), so there is no draft to apply or discard.
 *
 * Every setter removes what the author blanks: an older server's strict parser
 * refuses a field it does not know, so only what was set is ever written.
 */
export function GuardrailSheet({ phase, value, onChange, onClose, readOnly }: GuardrailSheetProps) {
  const { t } = useTranslation();
  const uid = useId();
  const id = (name: string) => `${uid}-${name}`;
  const sheetRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  // Focus in on open, back to whatever opened the sheet on close.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    return () => {
      if (opener?.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !e.defaultPrevented) {
        e.stopPropagation();
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Keep Tab inside the sheet: it is modal, so controls behind the backdrop must
  // not take focus.
  const trapTab = (e: React.KeyboardEvent) => {
    if (e.key !== "Tab") return;
    const focusable = sheetRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (!focusable || focusable.length === 0) return;
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const known = isKnownGuardrailType(value.type);
  const params = value.parameters ?? {};
  const action = effectiveAction(value);
  const setParam = (key: string, v: string) => onChange(withParameter(value, key, v));

  const typeOptions = typesForPhase(phase);
  const actionOptions = actionsForPhase(phase);
  if (!actionOptions.includes(action)) actionOptions.push(action);

  const actionDesc = (a: GuardrailActionName): string => {
    switch (a) {
      case "block":
        return phase === "input"
          ? t("llmEditor.grActionBlockInputDesc", "The model is not called; the block message is the turn's answer.")
          : t("llmEditor.grActionBlockOutputDesc", "The answer is replaced by the block message.");
      case "redact":
        return phase === "input"
          ? t(
              "llmEditor.grActionRedactInputDesc",
              "The model sees a rewritten message; conversation memory keeps what the user sent.",
            )
          : t("llmEditor.grActionRedactOutputDesc", "The answer is rewritten before it is stored or delivered.");
      case "reprompt":
        return phase === "input"
          ? t("llmEditor.grActionRepromptInputDesc", "Behaves as block on the input side: there is nothing to re-ask.")
          : t(
              "llmEditor.grActionRepromptOutputDesc",
              "The same model is asked again, up to Max retries; then the answer is blocked.",
            );
      case "warn":
        return t(
          "llmEditor.grActionWarnDesc",
          "Recorded in the audit, metric and event stream; the text is not changed.",
        );
    }
  };

  const typeText = known ? TYPE_TEXT[value.type as GuardrailTypeName] : undefined;
  const title =
    phase === "input"
      ? t("llmEditor.grSheetTitleInput", "Input guardrail")
      : t("llmEditor.grSheetTitleOutput", "Output guardrail");

  const body = (
    <>
      <div
        className="fixed inset-0 z-40 bg-black/50"
        onClick={onClose}
        aria-hidden="true"
        data-testid="guardrail-sheet-backdrop"
      />
      <div
        ref={sheetRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onKeyDown={trapTab}
        className="fixed bottom-0 end-0 top-0 z-50 flex w-[480px] max-w-full flex-col border-s border-border bg-card shadow-2xl"
        data-testid="guardrail-sheet"
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border px-4 py-3">
          <h3 className="text-sm font-semibold text-foreground">{title}</h3>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label={t("common.close", "Close")}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
            data-testid="guardrail-sheet-close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
          {/* Type */}
          <Field
            label={t("llmEditor.grType", "Type")}
            htmlFor={id("type")}
            hint={
              typeText
                ? t(typeText[2], typeText[3])
                : t(
                    "llmEditor.grTypeUnknownDesc",
                    "Not a built-in type: the engine skips it with a warning in the log.",
                  )
            }
          >
            <select
              id={id("type")}
              value={value.type}
              onChange={(e) => onChange(withType(value, e.target.value))}
              disabled={readOnly}
              className={FIELD}
              data-testid="guardrail-type"
            >
              {!known && (
                <option value={value.type}>{t("llmEditor.unknownType", "{{type}} (custom)", { type: value.type })}</option>
              )}
              {known && !typeOptions.includes(value.type as GuardrailTypeName) && (
                <option value={value.type}>{t(typeText![0], typeText![1])}</option>
              )}
              {typeOptions.map((type) => (
                <option key={type} value={type}>
                  {t(TYPE_TEXT[type][0], TYPE_TEXT[type][1])}
                </option>
              ))}
            </select>
            {known && !typesForPhase(phase).includes(value.type as GuardrailTypeName) && (
              <Warning testId="guardrail-wrong-phase">
                {phase === "input"
                  ? t("llmEditor.grWrongPhaseInput", "This type is meant for the output side; on the input side it finds nothing.")
                  : t("llmEditor.grWrongPhaseOutput", "This type is meant for the input side; on the output side it finds nothing.")}
              </Warning>
            )}
          </Field>

          {/* Action */}
          <Field label={t("llmEditor.grAction", "Action")} htmlFor={id("action")} hint={actionDesc(action)}>
            <select
              id={id("action")}
              value={action}
              onChange={(e) => onChange(withAction(value, e.target.value as GuardrailActionName))}
              disabled={readOnly}
              className={FIELD}
              data-testid="guardrail-action"
            >
              {actionOptions.map((a) => (
                <option key={a} value={a}>
                  {t(ACTION_TEXT[a][0], ACTION_TEXT[a][1])}
                </option>
              ))}
            </select>
            {action === "redact" && !typeHasRewrite(value.type) && (
              <Warning testId="guardrail-no-rewrite">
                {t(
                  "llmEditor.grNoRewrite",
                  "This type has nothing to rewrite with, so redact blocks the text instead.",
                )}
              </Warning>
            )}
          </Field>

          {action === "reprompt" && phase === "output" && (
            <Field
              label={t("llmEditor.grMaxRetries", "Max retries")}
              htmlFor={id("retries")}
              hint={t("llmEditor.grMaxRetriesHint", "Extra model calls allowed. Default {{n}}, at most {{max}}.", {
                n: DEFAULT_REPROMPT_RETRIES,
                max: MAX_REPROMPT_RETRIES,
              })}
            >
              <NumberInput
                id={id("retries")}
                integer
                value={value.maxRetries}
                onChange={(v) => onChange(withMaxRetries(value, v))}
                readOnly={readOnly}
                placeholder={String(DEFAULT_REPROMPT_RETRIES)}
                className={`${FIELD} w-24`}
                data-testid="guardrail-max-retries"
              />
            </Field>
          )}

          {action === "reprompt" && phase === "output" && (
            <Field
              label={t("llmEditor.grOnExhausted", "When the retries run out")}
              htmlFor={id("exhausted")}
              hint={t(
                "llmEditor.grOnExhaustedHint",
                "Block replaces the answer with the block message. Deliver best sends the attempt this guardrail rated best, recorded as a warning; every other guardrail still screens it first.",
              )}
            >
              <select
                id={id("exhausted")}
                value={value.onExhausted === "deliver-best" ? "deliver-best" : "block"}
                onChange={(e) => onChange(withOnExhausted(value, e.target.value as GuardrailOnExhausted))}
                disabled={readOnly}
                className={FIELD}
                data-testid="guardrail-on-exhausted"
              >
                {ON_EXHAUSTED.map((o) => (
                  <option key={o} value={o}>
                    {o === "block"
                      ? t("llmEditor.grOnExhaustedBlock", "Block (default)")
                      : t("llmEditor.grOnExhaustedDeliverBest", "Deliver the best attempt")}
                  </option>
                ))}
              </select>
              {value.onExhausted === "deliver-best" && value.type === "classifier" && (
                <Warning testId="guardrail-deliver-best-classifier">
                  {t(
                    "llmEditor.grDeliverBestClassifier",
                    "A safety classifier that delivers what it flagged defeats its purpose. Use deliver best for quality checks such as the judge.",
                  )}
                </Warning>
              )}
            </Field>
          )}

          {/* Block message */}
          <Field
            label={t("llmEditor.grBlockMessage", "Block message")}
            htmlFor={id("block")}
            hint={t(
              "llmEditor.grBlockMessageHint",
              "The answer the user gets when this guardrail blocks. Used exactly as written; never run through the template engine.",
            )}
          >
            <input
              id={id("block")}
              type="text"
              value={value.blockMessage ?? ""}
              onChange={(e) => onChange(withText(value, "blockMessage", e.target.value))}
              readOnly={readOnly}
              placeholder={
                phase === "input"
                  ? t("llmEditor.grBlockMessageInputDefault", "Request blocked by guardrail '{{type}}'.", { type: value.type })
                  : t("llmEditor.grBlockMessageOutputDefault", "Response withheld by guardrail '{{type}}'.", { type: value.type })
              }
              className={FIELD}
              data-testid="guardrail-block-message"
            />
          </Field>

          {/* Type-specific parameters */}
          {value.type === "prompt-injection" && (
            <>
              <Field
                label={t("llmEditor.grExtraPatterns", "Extra patterns")}
                htmlFor={id("patterns")}
                hint={t(
                  "llmEditor.grPatternsHint",
                  "One regular expression per line. Keep them free of nested quantifiers; an invalid pattern is ignored with a warning.",
                )}
              >
                <textarea
                  id={id("patterns")}
                  rows={3}
                  value={params.patterns ?? ""}
                  onChange={(e) => setParam("patterns", e.target.value)}
                  readOnly={readOnly}
                  className={AREA}
                  data-testid="guardrail-patterns"
                />
              </Field>
              <Field label={t("llmEditor.grMode", "Built-in list")} htmlFor={id("mode")}>
                <select
                  id={id("mode")}
                  value={params.mode ?? "append"}
                  onChange={(e) => setParam("mode", e.target.value === "append" ? "" : e.target.value)}
                  disabled={readOnly}
                  className={FIELD}
                  data-testid="guardrail-mode"
                >
                  <option value="append">{t("llmEditor.grModeAppend", "Add the extra patterns to the built-in list")}</option>
                  <option value="replace">{t("llmEditor.grModeReplace", "Use only the extra patterns")}</option>
                </select>
              </Field>
            </>
          )}

          {value.type === "pii-redaction" && (
            <>
              <fieldset>
                <legend className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("llmEditor.grPiiKinds", "Kinds to find")}
                </legend>
                <div className="space-y-1">
                  {PII_KINDS.map((kind) => {
                    const enabled = enabledPiiKinds(value);
                    const checked = enabled.includes(kind);
                    return (
                      <label key={kind} className="flex items-center gap-2 text-xs text-foreground">
                        <input
                          type="checkbox"
                          checked={checked}
                          // The last kind stays on: an empty list is the default, which is every kind.
                          disabled={readOnly || (checked && enabled.length === 1)}
                          onChange={(e) =>
                            onChange(
                              withPiiKinds(value, e.target.checked ? [...enabled, kind] : enabled.filter((k) => k !== kind)),
                            )
                          }
                          className="h-3.5 w-3.5 rounded border-input accent-primary"
                          data-testid={`guardrail-pii-${kind}`}
                        />
                        {kind === "email" && t("llmEditor.grPiiEmail", "E-mail addresses")}
                        {kind === "ssn" && t("llmEditor.grPiiSsn", "US social security numbers")}
                        {kind === "creditcard" && t("llmEditor.grPiiCard", "Payment card numbers (Luhn-checked)")}
                        {kind === "phone" && t("llmEditor.grPiiPhone", "Phone numbers")}
                      </label>
                    );
                  })}
                </div>
              </fieldset>
              <Field
                label={t("llmEditor.grReplacement", "Replacement")}
                htmlFor={id("replacement")}
                hint={t(
                  "llmEditor.grPiiReplacementHint",
                  "Default [REDACTED:<kind>]. A custom value may contain {kind}, which is replaced by the kind found.",
                )}
              >
                <input
                  id={id("replacement")}
                  type="text"
                  value={params.replacement ?? ""}
                  onChange={(e) => setParam("replacement", e.target.value)}
                  readOnly={readOnly}
                  className={FIELD}
                  data-testid="guardrail-replacement"
                />
              </Field>
            </>
          )}

          {value.type === "regex-filter" && (
            <>
              <Field
                label={t("llmEditor.grPatterns", "Patterns")}
                htmlFor={id("patterns")}
                hint={t(
                  "llmEditor.grPatternsHint",
                  "One regular expression per line. Keep them free of nested quantifiers; an invalid pattern is ignored with a warning.",
                )}
              >
                <textarea
                  id={id("patterns")}
                  rows={4}
                  value={params.patterns ?? ""}
                  onChange={(e) => setParam("patterns", e.target.value)}
                  readOnly={readOnly}
                  aria-invalid={!params.patterns?.trim()}
                  className={AREA}
                  data-testid="guardrail-patterns"
                />
              </Field>
              {!params.patterns?.trim() && (
                <Warning testId="guardrail-patterns-required">
                  {t("llmEditor.grPatternsRequired", "At least one pattern is required; without one this guardrail finds nothing.")}
                </Warning>
              )}
              <Field
                label={t("llmEditor.grReplacement", "Replacement")}
                htmlFor={id("replacement")}
                hint={t("llmEditor.grRegexReplacementHint", "Default [REDACTED].")}
              >
                <input
                  id={id("replacement")}
                  type="text"
                  value={params.replacement ?? ""}
                  onChange={(e) => setParam("replacement", e.target.value)}
                  readOnly={readOnly}
                  className={FIELD}
                  data-testid="guardrail-replacement"
                />
              </Field>
            </>
          )}

          {value.type === "max-length" && (
            <Field
              label={t("llmEditor.grMaxChars", "Maximum characters")}
              htmlFor={id("maxchars")}
              hint={t("llmEditor.grMaxCharsHint", "Default 8000. Redact truncates; reprompt asks for a shorter answer.")}
            >
              <input
                id={id("maxchars")}
                type="number"
                min={1}
                value={params.maxChars ?? ""}
                onChange={(e) => setParam("maxChars", e.target.value)}
                readOnly={readOnly}
                placeholder="8000"
                className={`${FIELD} w-32`}
                data-testid="guardrail-max-chars"
              />
            </Field>
          )}

          {value.type === "json-object" && (
            <p className="text-[10px] leading-relaxed text-muted-foreground" data-testid="guardrail-json-note">
              {t(
                "llmEditor.grJsonNote",
                "No settings. For a schema, use Response Validation (responseSchema) instead.",
              )}
            </p>
          )}

          {value.type === "expression" && (
            <ExpressionFields value={value} readOnly={readOnly} idOf={id} setParam={setParam} />
          )}

          {value.type === "classifier" && (
            <ClassifierFields
              value={value}
              phase={phase}
              readOnly={readOnly}
              idOf={id}
              setParam={setParam}
              onReplace={onChange}
            />
          )}

          {value.type === "judge" && (
            <JudgeFields value={value} readOnly={readOnly} idOf={id} setParam={setParam} onReplace={onChange} />
          )}

          {/* Common */}
          {action === "reprompt" && phase === "output" && (
            <Field
              label={t("llmEditor.grRepromptMessage", "Reprompt message")}
              htmlFor={id("repromptmsg")}
              hint={t(
                "llmEditor.grRepromptMessageHint",
                "Replaces the corrective message sent to the model. {reason} is replaced by the guardrail's reason.",
              )}
            >
              <textarea
                id={id("repromptmsg")}
                rows={2}
                value={params.repromptMessage ?? ""}
                onChange={(e) => setParam("repromptMessage", e.target.value)}
                readOnly={readOnly}
                className={AREA}
                data-testid="guardrail-reprompt-message"
              />
            </Field>
          )}

          <Field
            label={t("llmEditor.grFailMode", "If the guardrail cannot decide")}
            htmlFor={id("failmode")}
            hint={t(
              "llmEditor.grFailModeHint",
              "A guardrail that throws or returns no verdict has not checked the text. Closed treats that as a finding; open lets the text through (recorded as a warning). Open weakens a blocking guardrail: use it only when availability matters more than screening.",
            )}
          >
            <select
              id={id("failmode")}
              value={params.failMode ?? ""}
              onChange={(e) => setParam("failMode", e.target.value)}
              disabled={readOnly}
              className={FIELD}
              data-testid="guardrail-fail-mode"
            >
              <option value="">
                {t("llmEditor.grFailDefault", "Default (closed for block and reprompt, open for redact and warn)")}
              </option>
              <option value="closed">{t("llmEditor.grFailClosed", "Closed")}</option>
              <option value="open">{t("llmEditor.grFailOpen", "Open")}</option>
            </select>
            {params.failMode === "open" && action !== "warn" && action !== "redact" && (
              <Warning testId="guardrail-fail-open-warning">
                {t(
                  "llmEditor.grFailOpenWarning",
                  "This guardrail blocks, but a failure will let the text through.",
                )}
              </Warning>
            )}
          </Field>
        </div>
      </div>
    </>
  );

  return createPortal(body, document.body);
}

function ExpressionFields({
  value,
  readOnly,
  idOf,
  setParam,
}: {
  value: GuardrailConfig;
  readOnly?: boolean;
  idOf: (name: string) => string;
  setParam: (key: string, v: string) => void;
}) {
  const { t } = useTranslation();
  const p = value.parameters ?? {};
  const problems = expressionProblems(value);
  const has = (problem: ExpressionProblem) => problems.includes(problem);

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-3" data-testid="guardrail-expression">
      <p className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.grExprNote",
          "The answer is parsed as JSON and the expression evaluated over it; only true passes. An answer that is not JSON, or an expression that cannot be evaluated (a missing value in arithmetic, a number compared with text, division by zero), counts as undecided and follows the fail mode below.",
        )}
      </p>

      <Field
        label={t("llmEditor.grExprExpr", "Expression")}
        htmlFor={idOf("expr")}
        hint={t(
          "llmEditor.grExprExprHint",
          "Paths (total, invoice.total, lines[0].amount, lines[*].amount), decimal arithmetic, == != < <= > >=, && || !, and sum, count, min, max, len, abs, round, exists, approx. A missing path is null; comparing null with < or > is false. The syntax is checked when the agent is deployed.",
        )}
      >
        <textarea
          id={idOf("expr")}
          rows={3}
          value={p.expr ?? ""}
          onChange={(e) => setParam("expr", e.target.value)}
          readOnly={readOnly}
          placeholder="sum(lines[*].amount) == total"
          aria-invalid={has("exprRequired") || has("exprTooLong")}
          className={AREA}
          data-testid="guardrail-expr"
        />
      </Field>
      {has("exprRequired") && (
        <Warning testId="guardrail-expr-required">
          {t("llmEditor.grExprRequired", "An expression is required: the deploy is refused without one.")}
        </Warning>
      )}
      {has("exprTooLong") && (
        <Warning testId="guardrail-expr-too-long">
          {t("llmEditor.grExprTooLong", "The expression is longer than {{max}} characters: the deploy is refused.", {
            max: EXPRESSION_MAX_CHARS,
          })}
        </Warning>
      )}

      <Field
        label={t("llmEditor.grExprIssue", "Issue code")}
        htmlFor={idOf("issue")}
        hint={t(
          "llmEditor.grExprIssueHint",
          "Reported in the audit record as expression_failed:<code>. Upper-case letters, digits and underscores, starting with a letter, at most 40 characters.",
        )}
      >
        <input
          id={idOf("issue")}
          type="text"
          value={p.issue ?? ""}
          onChange={(e) => setParam("issue", e.target.value)}
          readOnly={readOnly}
          placeholder="LINE_SUM"
          aria-invalid={has("issueRequired") || has("issueInvalid")}
          className={`${FIELD} font-mono`}
          data-testid="guardrail-expr-issue"
        />
      </Field>
      {has("issueRequired") && (
        <Warning testId="guardrail-expr-issue-required">
          {t("llmEditor.grExprIssueRequired", "An issue code is required: the deploy is refused without one.")}
        </Warning>
      )}
      {has("issueInvalid") && (
        <Warning testId="guardrail-expr-issue-invalid">
          {t(
            "llmEditor.grExprIssueInvalid",
            "Use upper-case letters, digits and underscores, starting with a letter, at most 40 characters: the deploy is refused otherwise.",
          )}
        </Warning>
      )}

      <Field
        label={t("llmEditor.grExprFix", "Fix text")}
        htmlFor={idOf("fix")}
        hint={t(
          "llmEditor.grExprFixHint",
          "What a reprompt tells the model when the expression is false. It is the only text from this check the model sees: no value from the answer is ever added.",
        )}
      >
        <textarea
          id={idOf("fix")}
          rows={2}
          value={p.fix ?? ""}
          onChange={(e) => setParam("fix", e.target.value)}
          readOnly={readOnly}
          placeholder={t("llmEditor.grExprFixPlaceholder", "Make total equal the sum of the line amounts.")}
          aria-invalid={has("fixRequired") || has("fixTooLong")}
          className={AREA}
          data-testid="guardrail-expr-fix"
        />
      </Field>
      {has("fixRequired") && (
        <Warning testId="guardrail-expr-fix-required">
          {t("llmEditor.grExprFixRequired", "A fix text is required: the deploy is refused without one.")}
        </Warning>
      )}
      {has("fixTooLong") && (
        <Warning testId="guardrail-expr-fix-too-long">
          {t("llmEditor.grExprFixTooLong", "The fix text is longer than {{max}} characters: the deploy is refused.", {
            max: EXPRESSION_FIX_MAX_CHARS,
          })}
        </Warning>
      )}
    </div>
  );
}

function ClassifierFields({
  value,
  phase,
  readOnly,
  idOf,
  setParam,
  onReplace,
}: {
  value: GuardrailConfig;
  phase: GuardrailPhaseName;
  readOnly?: boolean;
  idOf: (name: string) => string;
  setParam: (key: string, v: string) => void;
  /** Replaces the whole guardrail, for edits that touch several keys at once. */
  onReplace: (g: GuardrailConfig) => void;
}) {
  const { t } = useTranslation();
  const p = value.parameters ?? {};

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-3" data-testid="guardrail-classifier">
      <p className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.grClfNote",
          "Calls a safety-classifier model you run. Each screened text is one extra model call; on the input side it adds its latency to every turn. Any other model parameter your provider takes can be added in the JSON view.",
        )}
      </p>

      <ModelCallFields
        value={value}
        readOnly={readOnly}
        idOf={idOf}
        setParam={setParam}
        onReplace={onReplace}
        prefix="clf"
        providerRequired={t("llmEditor.grClfProviderRequired", "A provider is required: without one the classifier cannot decide.")}
      />

      <div className="grid grid-cols-2 gap-3">
        <Field
          label={t("llmEditor.grClfUnsafeLabel", "Unsafe label")}
          htmlFor={idOf("unsafe")}
          hint={t("llmEditor.grClfUnsafeHint", "The answer that means “flag it”. Default yes.")}
        >
          <input
            id={idOf("unsafe")}
            type="text"
            value={p.unsafeLabel ?? ""}
            onChange={(e) => setParam("unsafeLabel", e.target.value)}
            readOnly={readOnly}
            placeholder="yes"
            className={FIELD}
            data-testid="guardrail-clf-unsafe"
          />
        </Field>
        <Field
          label={t("llmEditor.grClfSafeLabel", "Safe label")}
          htmlFor={idOf("safe")}
          hint={t("llmEditor.grClfSafeHint", "Text passes only when the answer matches this. Default no.")}
        >
          <input
            id={idOf("safe")}
            type="text"
            value={p.safeLabel ?? ""}
            onChange={(e) => setParam("safeLabel", e.target.value)}
            readOnly={readOnly}
            placeholder="no"
            aria-invalid={classifierNeedsSafeLabel(value)}
            className={FIELD}
            data-testid="guardrail-clf-safe"
          />
        </Field>
      </div>
      {classifierNeedsSafeLabel(value) && (
        <Warning testId="guardrail-clf-safe-required">
          {t(
            "llmEditor.grClfSafeRequired",
            "A safe label is required when the unsafe label is not “yes”: the deploy is refused without it.",
          )}
        </Warning>
      )}

      <Field
        label={t("llmEditor.grClfLabelPattern", "Label pattern")}
        htmlFor={idOf("labelpattern")}
        hint={t(
          "llmEditor.grClfLabelPatternHint",
          "Regular expression whose first group is the label, taken from the last match in the answer. Without it the label is the answer's first word.",
        )}
      >
        <input
          id={idOf("labelpattern")}
          type="text"
          value={p.labelPattern ?? ""}
          onChange={(e) => setParam("labelPattern", e.target.value)}
          readOnly={readOnly}
          placeholder="<score>\s*(\w+)\s*</score>"
          className={`${FIELD} font-mono`}
          data-testid="guardrail-clf-label-pattern"
        />
      </Field>

      <Field
        label={t("llmEditor.grClfRisk", "Risk")}
        htmlFor={idOf("risk")}
        hint={t(
          "llmEditor.grClfRiskHint",
          "Free text available to the prompt template as {risk}: a risk name or a whole policy. A short name is also added to the audit reason.",
        )}
      >
        <input
          id={idOf("risk")}
          type="text"
          value={p.risk ?? ""}
          onChange={(e) => setParam("risk", e.target.value)}
          readOnly={readOnly}
          className={FIELD}
          data-testid="guardrail-clf-risk"
        />
      </Field>

      <Field
        label={t("llmEditor.grClfPromptTemplate", "Prompt template")}
        htmlFor={idOf("prompt")}
        hint={t(
          "llmEditor.grClfPromptTemplateHint",
          "Optional. Sent as one user message; {input}, {output}, {context} and {risk} are filled in as data. Without it the model's own chat template builds the prompt, which most safety models expect.",
        )}
      >
        <textarea
          id={idOf("prompt")}
          rows={4}
          value={p.promptTemplate ?? ""}
          onChange={(e) => setParam("promptTemplate", e.target.value)}
          readOnly={readOnly}
          className={AREA}
          data-testid="guardrail-clf-prompt-template"
        />
      </Field>

      <CallLimitFields value={value} readOnly={readOnly} idOf={idOf} setParam={setParam} prefix="clf"
        defaultTimeoutMs={3000}
        timeoutHint={t("llmEditor.grClfTimeoutHint", "Default 3000, at most 60000.")}
      />

      {phase === "output" && (
        <label className="flex items-start gap-2 text-xs text-foreground">
          <input
            type="checkbox"
            checked={p.includeRetrievedContext === "true"}
            onChange={(e) => setParam("includeRetrievedContext", e.target.checked ? "true" : "")}
            disabled={readOnly}
            className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="guardrail-clf-context"
          />
          <span>
            {t(
              "llmEditor.grClfContext",
              "Give the template this turn's retrieved RAG context as {context} (for a groundedness check). Has no effect without a prompt template.",
            )}
          </span>
        </label>
      )}
    </div>
  );

}

/**
 * Provider, base URL, model and API key of a model-backed guardrail
 * (`classifier`, `judge`): the same provider vocabulary as an LLM task, and the
 * same backend machinery behind both types.
 */
function ModelCallFields({
  value,
  readOnly,
  idOf,
  setParam,
  onReplace,
  prefix,
  providerRequired,
  baseUrlPlaceholder = "http://vllm.guardrails.svc:8000/v1",
}: {
  value: GuardrailConfig;
  readOnly?: boolean;
  idOf: (name: string) => string;
  setParam: (key: string, v: string) => void;
  onReplace: (g: GuardrailConfig) => void;
  /** The test-id prefix: `guardrail-<prefix>-provider`, … */
  prefix: string;
  providerRequired: string;
  baseUrlPlaceholder?: string;
}) {
  const { t } = useTranslation();
  const p = value.parameters ?? {};
  const provider = p.provider ?? "";
  const spec = modelParamSpec(provider);
  const credentialKey = spec.credentialKey;

  return (
    <>
      <Field label={t("llmEditor.grClfProvider", "Provider")} htmlFor={idOf("provider")}>
        <ProviderSelect
          id={idOf("provider")}
          value={provider}
          // Switching provider changes which key names the model and the credential.
          onChange={(next) => {
            const from = modelParamSpec(provider);
            const to = modelParamSpec(next);
            let updated = withParameter(value, "provider", next);
            const model = p[from.modelKey];
            if (from.modelKey !== to.modelKey && model) {
              updated = withParameter(withParameter(updated, from.modelKey, ""), to.modelKey, model);
            }
            if (from.credentialKey !== to.credentialKey && from.credentialKey && p[from.credentialKey]) {
              const cred = p[from.credentialKey]!;
              updated = withParameter(updated, from.credentialKey, "");
              if (to.credentialKey) updated = withParameter(updated, to.credentialKey, cred);
            }
            onReplace(updated);
          }}
          disabled={readOnly}
          className={FIELD}
          testId={`guardrail-${prefix}-provider`}
        />
      </Field>
      {!provider && <Warning testId={`guardrail-${prefix}-provider-required`}>{providerRequired}</Warning>}

      <Field label={t("llmEditor.grClfBaseUrl", "Base URL")} htmlFor={idOf("baseurl")}>
        <input
          id={idOf("baseurl")}
          type="text"
          value={p.baseUrl ?? ""}
          onChange={(e) => setParam("baseUrl", e.target.value)}
          readOnly={readOnly}
          placeholder={baseUrlPlaceholder}
          className={FIELD}
          data-testid={`guardrail-${prefix}-base-url`}
        />
      </Field>

      <Field label={t("llmEditor.grClfModel", "Model")} htmlFor={idOf("model")}>
        <input
          id={idOf("model")}
          type="text"
          value={p[spec.modelKey] ?? ""}
          onChange={(e) => setParam(spec.modelKey, e.target.value)}
          readOnly={readOnly}
          className={FIELD}
          data-testid={`guardrail-${prefix}-model`}
        />
      </Field>

      {credentialKey && (
        <Field
          label={t("llmEditor.grClfApiKey", "API key")}
          htmlFor={idOf("apikey")}
          hint={t("llmEditor.grClfApiKeyHint", "Store the key in the vault and reference it as ${vault:name}.")}
        >
          <SecretKeyPicker
            id={idOf("apikey")}
            value={p[credentialKey] ?? ""}
            onChange={(v) => setParam(credentialKey, v)}
            readOnly={readOnly}
            testId={`guardrail-${prefix}-api-key`}
          />
        </Field>
      )}
    </>
  );
}

/** Timeout and token prices of a model-backed guardrail. */
function CallLimitFields({
  value,
  readOnly,
  idOf,
  setParam,
  prefix,
  defaultTimeoutMs,
  timeoutHint,
}: {
  value: GuardrailConfig;
  readOnly?: boolean;
  idOf: (name: string) => string;
  setParam: (key: string, v: string) => void;
  prefix: string;
  defaultTimeoutMs: number;
  timeoutHint: string;
}) {
  const { t } = useTranslation();
  const p = value.parameters ?? {};
  return (
    <div className="grid grid-cols-3 gap-3">
      <Field
        label={t("llmEditor.grClfTimeout", "Timeout (ms)")}
        htmlFor={idOf("timeout")}
        hint={timeoutHint}
      >
        <input
          id={idOf("timeout")}
          type="number"
          min={1}
          max={60000}
          value={p.timeoutMs ?? ""}
          onChange={(e) => setParam("timeoutMs", e.target.value)}
          readOnly={readOnly}
          placeholder={String(defaultTimeoutMs)}
          className={FIELD}
          data-testid={`guardrail-${prefix}-timeout`}
        />
      </Field>
      <Field label={t("llmEditor.grClfInputPrice", "Input $/1M tokens")} htmlFor={idOf("inprice")}>
        <input
          id={idOf("inprice")}
          type="number"
          min={0}
          step="any"
          value={p.inputPricePer1M ?? ""}
          onChange={(e) => setParam("inputPricePer1M", e.target.value)}
          readOnly={readOnly}
          className={FIELD}
          data-testid={`guardrail-${prefix}-input-price`}
        />
      </Field>
      <Field label={t("llmEditor.grClfOutputPrice", "Output $/1M tokens")} htmlFor={idOf("outprice")}>
        <input
          id={idOf("outprice")}
          type="number"
          min={0}
          step="any"
          value={p.outputPricePer1M ?? ""}
          onChange={(e) => setParam("outputPricePer1M", e.target.value)}
          readOnly={readOnly}
          className={FIELD}
          data-testid={`guardrail-${prefix}-output-price`}
        />
      </Field>
    </div>
  );
}

function JudgeFields({
  value,
  readOnly,
  idOf,
  setParam,
  onReplace,
}: {
  value: GuardrailConfig;
  readOnly?: boolean;
  idOf: (name: string) => string;
  setParam: (key: string, v: string) => void;
  onReplace: (g: GuardrailConfig) => void;
}) {
  const { t } = useTranslation();
  const p = value.parameters ?? {};
  const priced = !!(p.inputPricePer1M?.trim() || p.outputPricePer1M?.trim());

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-3" data-testid="guardrail-judge">
      <p className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.grJudgeNote",
          "Asks a model to grade the answer after it is written: one extra model call per answer, and one more per reprompt. EDDI adds the reply format itself; the judge may only name the issue codes below, and a reprompt tells the model only your fix texts. Any other model parameter your provider takes can be added in the JSON view.",
        )}
      </p>

      <ModelCallFields
        value={value}
        readOnly={readOnly}
        idOf={idOf}
        setParam={setParam}
        onReplace={onReplace}
        prefix="judge"
        baseUrlPlaceholder=""
        providerRequired={t("llmEditor.grJudgeProviderRequired", "A provider is required: the deploy is refused without one.")}
      />

      <Field
        label={t("llmEditor.grClfPromptTemplate", "Prompt template")}
        htmlFor={idOf("prompt")}
        hint={t(
          "llmEditor.grJudgePromptTemplateHint",
          "Required. What the judge should check. {input} (the question), {output} (the answer) and {context} (with the option below) are filled in as data.",
        )}
      >
        <textarea
          id={idOf("prompt")}
          rows={4}
          value={p.promptTemplate ?? ""}
          onChange={(e) => setParam("promptTemplate", e.target.value)}
          readOnly={readOnly}
          aria-invalid={!p.promptTemplate?.trim()}
          className={AREA}
          data-testid="guardrail-judge-prompt-template"
        />
      </Field>
      {!p.promptTemplate?.trim() && (
        <Warning testId="guardrail-judge-prompt-required">
          {t("llmEditor.grJudgePromptRequired", "A prompt template is required: the deploy is refused without one.")}
        </Warning>
      )}

      <JudgeIssuesEditor raw={p.issues} readOnly={readOnly} onChange={(raw) => setParam("issues", raw)} />

      <CallLimitFields
        value={value}
        readOnly={readOnly}
        idOf={idOf}
        setParam={setParam}
        prefix="judge"
        defaultTimeoutMs={JUDGE_DEFAULT_TIMEOUT_MS}
        timeoutHint={t("llmEditor.grJudgeTimeoutHint", "Default 5000, at most 60000. Keep it short: the judge runs after every answer.")}
      />
      {!priced && (
        <Warning testId="guardrail-judge-unpriced">
          {t(
            "llmEditor.grJudgeUnpriced",
            "Without prices the judge's calls are not counted in the turn's cost or its cost ceilings.",
          )}
        </Warning>
      )}

      <label className="flex items-start gap-2 text-xs text-foreground">
        <input
          type="checkbox"
          checked={p.includeRetrievedContext === "true"}
          onChange={(e) => setParam("includeRetrievedContext", e.target.checked ? "true" : "")}
          disabled={readOnly}
          className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
          data-testid="guardrail-judge-context"
        />
        <span>
          {t(
            "llmEditor.grJudgeContext",
            "Give the template this turn's retrieved RAG context as {context}, for a groundedness check.",
          )}
        </span>
      </label>
    </div>
  );
}

/**
 * The judge's issue codes as rows of code + fix text. Rows live in local state
 * so a half-typed row (a duplicate code, a blank fix) is not collapsed by a
 * JSON round-trip; every edit writes the serialized object back.
 */
function JudgeIssuesEditor({
  raw,
  readOnly,
  onChange,
}: {
  raw: string | undefined;
  readOnly?: boolean;
  onChange: (raw: string) => void;
}) {
  const { t } = useTranslation();
  const parsed = parseJudgeIssues(raw);
  const [rows, setRows] = useState<JudgeIssue[]>(() => parsed ?? []);
  const uid = useId();

  if (parsed === null) {
    // Not an object of strings: keep the author's text visible and editable.
    return (
      <Field label={t("llmEditor.grJudgeIssues", "Issue codes")} htmlFor={`${uid}-raw`}>
        <textarea
          id={`${uid}-raw`}
          rows={4}
          value={raw ?? ""}
          onChange={(e) => {
            // Once the text parses, the row editor takes over — seed its rows from it,
            // or they stay [] and the first "Add" overwrites the author's fixed JSON.
            const next = e.target.value;
            const nextRows = parseJudgeIssues(next);
            if (nextRows) setRows(nextRows);
            onChange(next);
          }}
          readOnly={readOnly}
          aria-invalid
          className={AREA}
          data-testid="guardrail-judge-issues-raw"
        />
        <Warning testId="guardrail-judge-issues-invalid">
          {t(
            "llmEditor.grJudgeIssuesInvalid",
            "Not a JSON object of issue code to fix text; the deploy is refused until it is.",
          )}
        </Warning>
      </Field>
    );
  }

  const update = (next: JudgeIssue[]) => {
    setRows(next);
    onChange(serializeJudgeIssues(next));
  };
  const problem = judgeIssuesProblem(rows);
  const problemText = (kind: JudgeIssuesProblem): string => {
    switch (kind) {
      case "none":
        return t("llmEditor.grJudgeIssuesNone", "Declare at least one issue code: the deploy is refused without one.");
      case "tooMany":
        return t("llmEditor.grJudgeIssuesTooMany", "At most {{max}} issue codes.", { max: JUDGE_MAX_ISSUES });
      case "badCode":
        return t(
          "llmEditor.grJudgeIssuesBadCode",
          "A code is upper-case letters, digits and underscores, starts with a letter and has at most 40 characters.",
        );
      case "duplicate":
        return t("llmEditor.grJudgeIssuesDuplicate", "Each issue code may be declared only once.");
      case "blankFix":
        return t("llmEditor.grJudgeIssuesBlankFix", "Every issue code needs a fix text.");
      case "longFix":
        return t("llmEditor.grJudgeIssuesLongFix", "A fix text has at most {{max}} characters.", { max: JUDGE_MAX_FIX_CHARS });
    }
  };

  return (
    <fieldset data-testid="guardrail-judge-issues">
      <legend className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
        {t("llmEditor.grJudgeIssues", "Issue codes")}
      </legend>
      <p className="mb-2 text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.grJudgeIssuesHint",
          "Each code is a finding the judge may report, with the fix text a reprompt tells the model. The audit reason lists the codes found.",
        )}
      </p>
      <div className="space-y-2">
        {rows.map((row, i) => (
          <div key={i} className="flex items-start gap-2">
            <input
              type="text"
              value={row.code}
              onChange={(e) => update(rows.map((r, j) => (j === i ? { ...r, code: e.target.value.toUpperCase() } : r)))}
              readOnly={readOnly}
              placeholder="UNSUPPORTED_CLAIM"
              aria-label={t("llmEditor.grJudgeIssueCode", "Issue code")}
              className={`${FIELD} w-40 shrink-0 font-mono`}
              data-testid={`guardrail-judge-issue-code-${i}`}
            />
            <input
              type="text"
              value={row.fix}
              onChange={(e) => update(rows.map((r, j) => (j === i ? { ...r, fix: e.target.value } : r)))}
              readOnly={readOnly}
              placeholder={t("llmEditor.grJudgeIssueFixPlaceholder", "Remove every claim the sources do not state.")}
              aria-label={t("llmEditor.grJudgeIssueFix", "Fix text")}
              className={FIELD}
              data-testid={`guardrail-judge-issue-fix-${i}`}
            />
            {!readOnly && (
              <button
                type="button"
                onClick={() => update(rows.filter((_, j) => j !== i))}
                aria-label={t("llmEditor.grJudgeIssueRemove", "Remove issue code")}
                className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground"
                data-testid={`guardrail-judge-issue-remove-${i}`}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        ))}
      </div>
      {!readOnly && rows.length < JUDGE_MAX_ISSUES && (
        <button
          type="button"
          onClick={() => update([...rows, { code: "", fix: "" }])}
          className="mt-2 inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-primary hover:bg-secondary"
          data-testid="guardrail-judge-issue-add"
        >
          <Plus className="h-3.5 w-3.5" />
          {t("llmEditor.grJudgeIssueAdd", "Add issue code")}
        </button>
      )}
      {problem && (
        <div className="mt-2">
          <Warning testId="guardrail-judge-issues-problem">{problemText(problem)}</Warning>
        </div>
      )}
    </fieldset>
  );
}
