import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { AlertTriangle, X } from "lucide-react";
import { ProviderSelect } from "@/components/shared/provider-select";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { NumberInput } from "../number-input";
import { modelParamSpec } from "./model-params";
import {
  DEFAULT_REPROMPT_RETRIES,
  MAX_REPROMPT_RETRIES,
  PII_KINDS,
  actionsForPhase,
  classifierNeedsSafeLabel,
  effectiveAction,
  enabledPiiKinds,
  isKnownGuardrailType,
  typeHasRewrite,
  ACTION_TEXT,
  TYPE_TEXT,
  typesForPhase,
  withAction,
  withMaxRetries,
  withParameter,
  withPiiKinds,
  withText,
  withType,
  type GuardrailPhaseName,
  type GuardrailTypeName,
} from "./guardrails";
import type { GuardrailActionName, GuardrailConfig } from "./types";

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
      className="flex items-start gap-1.5 text-[10px] text-warn-700 dark:text-warn-400"
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
  const provider = p.provider ?? "";
  const spec = modelParamSpec(provider);
  const credentialKey = spec.credentialKey;

  return (
    <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-3" data-testid="guardrail-classifier">
      <p className="text-[10px] leading-relaxed text-muted-foreground">
        {t(
          "llmEditor.grClfNote",
          "Calls a safety-classifier model you run. Each screened text is one extra model call; on the input side it adds its latency to every turn. Any other model parameter your provider takes can be added in the JSON view.",
        )}
      </p>

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
          testId="guardrail-clf-provider"
        />
      </Field>
      {!provider && (
        <Warning testId="guardrail-clf-provider-required">
          {t("llmEditor.grClfProviderRequired", "A provider is required: without one the classifier cannot decide.")}
        </Warning>
      )}

      <Field label={t("llmEditor.grClfBaseUrl", "Base URL")} htmlFor={idOf("baseurl")}>
        <input
          id={idOf("baseurl")}
          type="text"
          value={p.baseUrl ?? ""}
          onChange={(e) => setParam("baseUrl", e.target.value)}
          readOnly={readOnly}
          placeholder="http://vllm.guardrails.svc:8000/v1"
          className={FIELD}
          data-testid="guardrail-clf-base-url"
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
          data-testid="guardrail-clf-model"
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
            testId="guardrail-clf-api-key"
          />
        </Field>
      )}

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

      <div className="grid grid-cols-3 gap-3">
        <Field
          label={t("llmEditor.grClfTimeout", "Timeout (ms)")}
          htmlFor={idOf("timeout")}
          hint={t("llmEditor.grClfTimeoutHint", "Default 3000, at most 60000.")}
        >
          <input
            id={idOf("timeout")}
            type="number"
            min={1}
            max={60000}
            value={p.timeoutMs ?? ""}
            onChange={(e) => setParam("timeoutMs", e.target.value)}
            readOnly={readOnly}
            placeholder="3000"
            className={FIELD}
            data-testid="guardrail-clf-timeout"
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
            data-testid="guardrail-clf-input-price"
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
            data-testid="guardrail-clf-output-price"
          />
        </Field>
      </div>

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
