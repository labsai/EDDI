import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Loader2, Sparkles, ShieldCheck, ShieldAlert, ShieldQuestion, Lock, Unlock } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { LLM_PROVIDERS, getProviderConfig } from "@/lib/api/agent-setup";
import { MODEL_SUGGESTIONS, isBaseUrlRequired } from "@/lib/model-suggestions";
import { useVaultHealth } from "@/hooks/use-secrets";
import { useAuth } from "@/hooks/use-auth";
import {
  safetyPreambleForScope,
  defaultOperatorPromptBody,
} from "@/lib/operator/system-prompt";
import {
  endpointsForScope,
  isWriteScopeAvailable,
  type OperatorScope,
} from "@/lib/operator/tool-scopes";
import { extractVaultKeyName, toVaultRef } from "@/lib/operator/vault-ref";
import type { OperatorConfig, OperatorAuthMode, GateVerificationResult } from "@/lib/api/operator";
import type { ActivationStage } from "@/hooks/use-operator";
import { cn } from "@/lib/utils";

interface OperatorActivationProps {
  initial: OperatorConfig;
  stage: ActivationStage;
  error: string | null;
  /**
   * The CURRENT operator's verified gate status — `initial.agentId`'s gate, not
   * the one this form is about to provision. `undefined`/`null` for a
   * never-activated operator, where there is nothing yet to have verified.
   */
  gate?: GateVerificationResult | null;
  onActivate: (config: OperatorConfig, apiKey: string, baseUrl?: string) => void;
  onCancel?: () => void;
}

type Step = "model" | "review";

export function OperatorActivation({
  initial,
  stage,
  error,
  gate,
  onActivate,
  onCancel,
}: OperatorActivationProps) {
  const { t } = useTranslation();
  const { method } = useAuth();
  const { data: vaultHealth } = useVaultHealth();

  const [step, setStep] = useState<Step>("model");
  const [provider, setProvider] = useState(initial.provider);
  const [model, setModel] = useState(initial.model);
  // Seeded from the stored vault key *name* so reconfiguring (e.g. switching
  // model) does not demand a credential the vault already holds. Plain-text
  // keys are not stored, so those still have to be re-entered.
  const [apiKey, setApiKey] = useState(
    initial.credentialKey ? toVaultRef(initial.credentialKey) : "",
  );
  const [baseUrl, setBaseUrl] = useState("");
  const [environment, setEnvironment] = useState(initial.environment);
  const [authMode, setAuthMode] = useState<OperatorAuthMode>(initial.authMode);

  const providerConfig = getProviderConfig(provider);
  const needsKey = providerConfig?.needsKey ?? true;
  const baseUrlRequired = isBaseUrlRequired(provider);
  const suggestions = useMemo(() => MODEL_SUGGESTIONS[provider] ?? [], [provider]);
  const vaultDown = vaultHealth != null && vaultHealth.available === false;
  const oidcEnabled = method === "keycloak";
  const busy = stage !== "idle" && stage !== "done";

  /**
   * Whether `read_write` can be offered at all — the same seam
   * `isWriteScopeAvailable` guards everywhere else, evaluated here against
   * what this form can actually know before submitting.
   *
   * `backendAcceptsHitlConfig` and `gateVerifiedOnEveryVersion` collapse to the
   * SAME fact here: `gate.verified` (from re-reading every version of the
   * CURRENT operator's document) cannot be true unless the backend both
   * accepted `hitlConfig` and round-tripped it soundly, since that is exactly
   * what verifying it checked. For a never-activated operator there is no
   * `gate` yet — nothing has been provisioned to verify — so this is false
   * until a first, read-only activation has proven the pipeline once.
   *
   * `approvalSurfaceMounted` is hardcoded true: this codebase's operator chat
   * unconditionally renders `ApprovalBanner` whenever a conversation pauses,
   * for every active operator — there is no configuration under which it
   * would not be mounted.
   */
  const writeScopeFacts = {
    backendAcceptsHitlConfig: gate?.verified ?? false,
    gateVerifiedOnEveryVersion: gate?.verified ?? false,
    authMode,
    approvalSurfaceMounted: true,
  };
  const writeScopeAvailable = isWriteScopeAvailable(writeScopeFacts);

  // `scope` remembers the admin's last EXPLICIT choice; `effectiveScope` is
  // what everything below actually uses. They diverge exactly when the admin
  // picked read_write and then something they also control (authMode) made it
  // unavailable again — e.g. flipping back to "none" mid-form. Without this
  // split the radio could stay visually "checked" on read_write while
  // grantedEndpoints/safetyPreamble/handleActivate had silently reverted to
  // read_only, or worse, handleActivate could submit a combination
  // isWriteScopeAvailable itself would refuse.
  const [scope, setScope] = useState<OperatorScope>(
    initial.scope === "read_write" ? "read_write" : "read_only",
  );
  const effectiveScope: OperatorScope = scope === "read_write" && writeScopeAvailable ? "read_write" : "read_only";

  const grantedEndpoints = useMemo(() => endpointsForScope(effectiveScope), [effectiveScope]);
  const safetyPreamble = useMemo(() => safetyPreambleForScope(effectiveScope), [effectiveScope]);

  const [promptBody, setPromptBody] = useState(
    initial.promptBody || defaultOperatorPromptBody(effectiveScope),
  );

  /**
   * Swaps the editable body to the new scope's default when the admin flips
   * scope — but ONLY while it still exactly equals the CURRENT scope's own
   * default. An admin who has customized the text gets to keep their
   * customization; silently overwriting it because they toggled a radio
   * button would be the kind of surprise this screen exists to avoid. An
   * untouched body always equals its own scope's default and never the other
   * scope's (the two are distinct strings — read_write's is read_only's plus
   * one more section — and nothing else in this component writes `promptBody`
   * outside the textarea's own `onChange`), so this single comparison is
   * sufficient; it does not need to check both defaults.
   */
  function handleScopeChange(next: OperatorScope) {
    if (promptBody === defaultOperatorPromptBody(effectiveScope)) {
      setPromptBody(defaultOperatorPromptBody(next));
    }
    setScope(next);
  }

  /**
   * The same swap, for when `effectiveScope` moves WITHOUT the admin touching the
   * scope radio.
   *
   * `handleScopeChange` only fires on an explicit pick, but `effectiveScope` also
   * changes on its own when `writeScopeAvailable` flips — most realistically when
   * the admin selects read_write and then changes `authMode` away from
   * caller-identity further up the form. Scope silently reverts to read_only while
   * the body still describes write capability, and `handleActivate` submits that
   * pair: an agent granted read-only endpoints but told it can change things.
   *
   * Compared against the PREVIOUS effective scope's default, because by the time
   * this runs the current one has already changed — testing against the new
   * default would never match and the body would never re-sync. A customized body
   * equals neither default and is left alone, same contract as above.
   */
  const previousEffectiveScope = useRef(effectiveScope);
  useEffect(() => {
    const previous = previousEffectiveScope.current;
    if (previous === effectiveScope) return;
    previousEffectiveScope.current = effectiveScope;
    setPromptBody((current) =>
      current === defaultOperatorPromptBody(previous) ? defaultOperatorPromptBody(effectiveScope) : current,
    );
  }, [effectiveScope]);

  /**
   * With OIDC on, `none` produces tool calls with no Authorization header, which
   * EDDI rejects — the operator would deploy READY and then fail every lookup.
   * Blocking here is the difference between an honest error and a silent dud.
   */
  const authModeUnusable = oidcEnabled && authMode === "none";

  const modelStepValid =
    Boolean(model.trim()) &&
    (!needsKey || Boolean(apiKey.trim())) &&
    (!baseUrlRequired || Boolean(baseUrl.trim()));

  const canActivate = modelStepValid && !authModeUnusable && !busy;

  function handleProviderChange(next: string) {
    setProvider(next);
    const cfg = getProviderConfig(next);
    if (cfg) setModel(cfg.defaultModel);
    // A key is provider-specific, so carrying it across a provider switch would
    // silently send the wrong credential.
    setApiKey(next === initial.provider && initial.credentialKey ? toVaultRef(initial.credentialKey) : "");
  }

  function handleActivate() {
    onActivate(
      {
        ...initial,
        provider,
        model,
        environment,
        promptBody,
        authMode,
        credentialKey: extractVaultKeyName(apiKey),
        scope: effectiveScope,
      },
      apiKey,
      baseUrl || undefined,
    );
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <header className="space-y-2">
        <div className="flex items-center gap-3">
          <Sparkles className="h-6 w-6 text-primary" />
          <h1 className="text-2xl font-semibold">{t("operator.activation.title", "Activate the Platform Operator")}</h1>
        </div>
        <p className="text-sm text-muted-foreground">
          {t("operator.activation.subtitle", "Choose the model that will run the operator, review its instructions, and deploy.")}
        </p>
        {effectiveScope === "read_write" ? (
          <Badge variant="warning" className="gap-1" data-testid="operator-scope-chip">
            <Unlock className="h-3 w-3" />
            {t("operator.readWriteChip", "Read & write")}
          </Badge>
        ) : (
          <Badge variant="success" className="gap-1" data-testid="operator-scope-chip">
            <Lock className="h-3 w-3" />
            {t("operator.readOnlyChip", "Read-only")}
          </Badge>
        )}
      </header>

      {step === "model" ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("operator.activation.modelStep", "Model")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <Field label={t("operator.activation.provider", "Provider")} htmlFor="operator-provider">
              <select
                value={provider}
                onChange={(e) => handleProviderChange(e.target.value)}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                id="operator-provider"
                data-testid="operator-provider"
              >
                {LLM_PROVIDERS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </Field>

            <Field label={t("operator.activation.model", "Model")} htmlFor="operator-model">
              <input
                value={model}
                onChange={(e) => setModel(e.target.value)}
                list="operator-model-suggestions"
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                id="operator-model"
                data-testid="operator-model"
              />
              <datalist id="operator-model-suggestions">
                {suggestions.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </Field>

            {baseUrlRequired && (
              <Field label={t("operator.activation.baseUrl", "Base URL")} htmlFor="operator-base-url">
                <input
                  value={baseUrl}
                  onChange={(e) => setBaseUrl(e.target.value)}
                  placeholder="http://localhost:11434"
                  className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  id="operator-base-url"
                  data-testid="operator-base-url"
                />
              </Field>
            )}

            {needsKey && (
              <Field
                label={t("operator.activation.apiKey", "Model API key")}
                hint={t("operator.activation.apiKeyHint", "The key for your LLM provider. This is not an EDDI credential.")}
                asGroup
              >
                {vaultDown && (
                  <Notice tone="warning" icon={AlertTriangle}>
                    {t("operator.activation.vaultDown", "The secrets vault is unavailable, so keys cannot be stored in it right now. A key entered here is passed through to the backend, which vaults it if it can.")}
                  </Notice>
                )}
                <SecretKeyPicker
                  value={apiKey}
                  onChange={setApiKey}
                  placeholder={t("operator.activation.apiKeyPlaceholder", "Paste your API key, or pick a vault key")}
                  testId="operator-api-key"
                />
              </Field>
            )}

            <Field
              label={t("operator.activation.environment", "Environment")}
              hint={t("operator.activation.environmentHint", "Where the operator agent itself runs. It can still read any environment.")}
              htmlFor="operator-environment"
            >
              <select
                value={environment}
                onChange={(e) => setEnvironment(e.target.value)}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                id="operator-environment"
                data-testid="operator-environment"
              >
                <option value="production">production</option>
                <option value="test">test</option>
                <option value="unrestricted">unrestricted</option>
              </select>
            </Field>

            <AuthModeField authMode={authMode} onChange={setAuthMode} oidcEnabled={oidcEnabled} />

            <div className="flex justify-end gap-2 pt-2">
              {onCancel && (
                <Button variant="ghost" onClick={onCancel}>
                  {t("common.cancel", "Cancel")}
                </Button>
              )}
              <Button
                onClick={() => setStep("review")}
                disabled={!modelStepValid}
                data-testid="operator-next"
              >
                {t("common.next", "Next")}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{t("operator.activation.reviewStep", "Prompt & review")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <Summary label={t("operator.activation.provider", "Provider")} value={providerConfig?.name ?? provider} />
              <Summary label={t("operator.activation.model", "Model")} value={model} />
              <Summary label={t("operator.activation.environment", "Environment")} value={environment} />
              <Summary
                label={t("operator.activation.authMode", "How the operator authenticates")}
                value={t(`operator.authMode.${authMode}.label`)}
              />
            </dl>

            <ScopeField
              effectiveScope={effectiveScope}
              writeScopeAvailable={writeScopeAvailable}
              hasExistingOperator={Boolean(initial.agentId)}
              onChange={handleScopeChange}
            />

            <Field
              label={t("operator.activation.safetyPreamble", "Safety rules (not editable)")}
              hint={t("operator.activation.safetyPreambleHint", "Always prepended. It tells the operator to treat everything its tools return as untrusted data.")}
            >
              <pre className="max-h-40 overflow-auto rounded-md border border-border bg-muted/40 p-3 text-xs whitespace-pre-wrap text-muted-foreground">
                {safetyPreamble}
              </pre>
            </Field>

            <Field
              label={t("operator.activation.promptBody", "Operator instructions")}
              hint={t("operator.activation.promptBodyHint", "Edit how the operator introduces itself and how it works.")}
              htmlFor="operator-prompt-body"
            >
              <textarea
                value={promptBody}
                onChange={(e) => setPromptBody(e.target.value)}
                rows={10}
                className="w-full rounded-md border border-input bg-background p-3 font-mono text-xs"
                id="operator-prompt-body"
                data-testid="operator-prompt-body"
              />
            </Field>

            <Field
              label={t("operator.activation.tools", "Tools it will be given ({{toolCount}})", {
                toolCount: grantedEndpoints.length,
              })}
            >
              <ul className="max-h-32 space-y-1 overflow-auto rounded-md border border-border bg-muted/40 p-3 font-mono text-xs text-muted-foreground">
                {grantedEndpoints.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            </Field>

            {initial.agentId && (
              <Notice tone="warning" icon={AlertTriangle} testId="operator-rebuild-warning">
                {t("operator.activation.rebuildWarning", "Saving builds a new operator agent and removes the current one. Your existing operator conversation will not carry over.")}
              </Notice>
            )}

            {error && (
              <Notice tone="error" icon={AlertTriangle} testId="operator-activation-error">
                {error}
              </Notice>
            )}

            {busy && (
              <div
                className="flex items-center gap-2 rounded-md border border-border bg-muted/40 p-3 text-sm"
                data-testid="operator-activation-stage"
                role="status"
                aria-live="polite"
              >
                <Loader2 className="h-4 w-4 animate-spin text-primary" />
                {t(`operator.stage.${stage}`)}
              </div>
            )}

            <div className="flex justify-between gap-2 pt-2">
              <Button variant="ghost" onClick={() => setStep("model")} disabled={busy}>
                {t("common.back", "Back")}
              </Button>
              <Button
                onClick={handleActivate}
                disabled={!canActivate}
                data-testid="operator-activate"
              >
                {t("operator.activation.activate", "Activate & deploy")}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/* ─── Scope ─── */

interface ScopeFieldProps {
  /**
   * What is actually granted right now — never the admin's raw last click.
   * The parent derives this from its own `scope` state plus
   * `writeScopeAvailable`, so it already reflects a precondition lost after
   * the fact (e.g. auth mode flipped away from caller-identity). Both radios'
   * `checked` state and the write-warning notice below key off this alone —
   * keying either off the raw selection instead would let the UI show a
   * choice as active that will not actually be what gets submitted.
   */
  effectiveScope: OperatorScope;
  writeScopeAvailable: boolean;
  /** Whether this is a reconfigure of an already-verified operator, vs a
   *  first-time activation with nothing yet to have verified. */
  hasExistingOperator: boolean;
  onChange: (scope: OperatorScope) => void;
}

/**
 * Selects between read-only and read-write.
 *
 * Read-write is only ever offered once `writeScopeAvailable` holds — see
 * `isWriteScopeAvailable`'s own doc comment: this is the one control in the
 * whole app that turns it on, so the seam has to be enforced exactly here.
 * The control is still SHOWN, disabled, when unavailable — an admin who never
 * sees the option has no way to learn that reconfiguring later could offer
 * it once a gate is verified.
 */
function ScopeField({ effectiveScope, writeScopeAvailable, hasExistingOperator, onChange }: ScopeFieldProps) {
  const { t } = useTranslation();

  const unavailableReason = writeScopeAvailable
    ? null
    : hasExistingOperator
      ? t(
          "operator.activation.scope.unavailableNotVerified",
          "Not available yet — this operator's approval gate has not been verified as sound. Check connection on the status panel, or choose \"Your identity\" below, then reconfigure.",
        )
      : t(
          "operator.activation.scope.unavailableFirstActivation",
          "Not available on first activation. Activate read-only first to prove the approval gate is sound, then reconfigure to grant write access.",
        );

  return (
    <Field
      label={t("operator.activation.scope.label", "Capability")}
      hint={t("operator.activation.scope.hint", "What the operator is allowed to do. Every write still pauses for your approval — see the safety rules below.")}
      asGroup
      groupRole="radiogroup"
    >
      <div className="space-y-2">
        <label
          className={cn(
            "flex cursor-pointer gap-3 rounded-md border p-3 text-sm",
            effectiveScope === "read_only" ? "border-primary bg-primary/5" : "border-border",
          )}
        >
          <input
            type="radio"
            name="operator-scope"
            value="read_only"
            checked={effectiveScope === "read_only"}
            onChange={() => onChange("read_only")}
            className="mt-1"
            data-testid="operator-scope-read_only"
          />
          <span>
            <span className="font-medium">{t("operator.activation.scope.readOnly.label", "Read-only")}</span>
            <span className="block text-xs text-muted-foreground">
              {t("operator.activation.scope.readOnly.description", "Can inspect and explain this deployment. Cannot change anything.")}
            </span>
          </span>
        </label>

        <label
          className={cn(
            "flex gap-3 rounded-md border p-3 text-sm",
            !writeScopeAvailable
              ? "cursor-not-allowed opacity-60"
              : effectiveScope === "read_write"
                ? "cursor-pointer border-primary bg-primary/5"
                : "cursor-pointer border-border",
          )}
        >
          <input
            type="radio"
            name="operator-scope"
            value="read_write"
            checked={effectiveScope === "read_write"}
            disabled={!writeScopeAvailable}
            onChange={() => onChange("read_write")}
            className="mt-1"
            data-testid="operator-scope-read_write"
          />
          <span>
            <span className="font-medium">{t("operator.activation.scope.readWrite.label", "Read & write")}</span>
            <span className="block text-xs text-muted-foreground">
              {t(
                "operator.activation.scope.readWrite.description",
                "Also lets it create and modify agents and agent groups, deploy, undeploy, disable a runaway schedule, and edit an agent's descriptor — each one paused for your approval first.",
              )}
            </span>
          </span>
        </label>
      </div>

      {unavailableReason && (
        <Notice tone="info" icon={ShieldQuestion} testId="operator-scope-unavailable">
          {unavailableReason}
        </Notice>
      )}

      {effectiveScope === "read_write" && (
        <Notice tone="warning" icon={ShieldAlert} testId="operator-scope-write-warning">
          {t(
            "operator.activation.scope.writeWarning",
            "Activating will run a write canary: a real, harmless test write that must pause for approval before this deployment finishes. If it does not pause, activation is refused and the operator is removed rather than left deployed with an unverified write gate.",
          )}
        </Notice>
      )}
    </Field>
  );
}

/* ─── Auth mode ─── */

interface AuthModeFieldProps {
  authMode: OperatorAuthMode;
  onChange: (mode: OperatorAuthMode) => void;
  oidcEnabled: boolean;
}

function AuthModeField({ authMode, onChange, oidcEnabled }: AuthModeFieldProps) {
  const { t } = useTranslation();
  const modes: OperatorAuthMode[] = ["none", "caller-identity"];

  return (
    <Field
      label={t("operator.activation.authMode", "How the operator authenticates")}
      hint={t("operator.activation.authModeHint", "The operator calls your EDDI admin API. This decides what credentials those calls carry.")}
      asGroup
      groupRole="radiogroup"
    >
      <div className="space-y-2">
        {modes.map((mode) => (
          <label
            key={mode}
            className={cn(
              "flex cursor-pointer gap-3 rounded-md border p-3 text-sm",
              authMode === mode ? "border-primary bg-primary/5" : "border-border",
            )}
          >
            <input
              type="radio"
              name="operator-auth-mode"
              value={mode}
              checked={authMode === mode}
              onChange={() => onChange(mode)}
              className="mt-1"
              data-testid={`operator-auth-${mode}`}
            />
            <span>
              <span className="font-medium">{t(`operator.authMode.${mode}.label`)}</span>
              <span className="block text-xs text-muted-foreground">
                {t(`operator.authMode.${mode}.description`)}
              </span>
            </span>
          </label>
        ))}
      </div>

      {oidcEnabled && authMode === "none" && (
        <Notice tone="error" icon={AlertTriangle} testId="operator-auth-blocked">
          {t("operator.activation.authNoneBlocked", "This deployment has authentication enabled, so unauthenticated tool calls would be rejected. The operator would deploy successfully and then fail every lookup. Choose \"Your identity\" instead.")}
        </Notice>
      )}

      {authMode === "caller-identity" && (
        <Notice tone="info" icon={ShieldCheck}>
          {t("operator.activation.callerIdentityNote", "EDDI substitutes your token when it makes the call. It is never stored in the conversation, and is only ever sent back to this deployment.")}
        </Notice>
      )}
    </Field>
  );
}

/* ─── Small presentational helpers ─── */

/**
 * A labelled form row.
 *
 * `htmlFor` binds the label to a single native control, which is what gives
 * that control its accessible name. Composite children (the secret picker, the
 * radio group) have no single element to point at, so they pass `asGroup` and
 * are wrapped in a named group instead — either way the control is never left
 * anonymous to a screen reader.
 */
function Field({
  label,
  hint,
  htmlFor,
  asGroup,
  groupRole = "group",
  children,
}: {
  label: string;
  hint?: string;
  htmlFor?: string;
  asGroup?: boolean;
  groupRole?: "group" | "radiogroup";
  children: React.ReactNode;
}) {
  // Only meaningful when it can actually be referenced by a control.
  const hintId = hint ? `${htmlFor ?? label.replace(/\s+/g, "-").toLowerCase()}-hint` : undefined;
  return (
    <div className="space-y-2">
      <div>
        <label className="text-sm font-medium" htmlFor={htmlFor} id={`${htmlFor ?? label}-label`}>
          {label}
        </label>
        {hint && (
          <p className="text-xs text-muted-foreground" id={hintId}>
            {hint}
          </p>
        )}
      </div>
      {asGroup ? (
        <div role={groupRole} aria-label={label} aria-describedby={hintId} className="space-y-2">
          {children}
        </div>
      ) : (
        children
      )}
    </div>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </div>
  );
}

function Notice({
  tone,
  icon: Icon,
  children,
  testId,
}: {
  tone: "warning" | "error" | "info";
  icon: typeof AlertTriangle;
  children: React.ReactNode;
  testId?: string;
}) {
  return (
    <div
      data-testid={testId}
      role={tone === "error" ? "alert" : "status"}
      className={cn(
        "flex items-start gap-2 rounded-md border p-3 text-sm",
        tone === "error"
          ? "border-destructive/40 bg-destructive/10 text-destructive"
          : tone === "info"
            ? "border-primary/40 bg-primary/5 text-foreground"
            : "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
      )}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  );
}
