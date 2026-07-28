import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Loader2, Sparkles, KeyRound, Lock } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { LLM_PROVIDERS, getProviderConfig } from "@/lib/api/agent-setup";
import { MODEL_SUGGESTIONS, isBaseUrlRequired } from "@/lib/model-suggestions";
import { useVaultHealth } from "@/hooks/use-secrets";
import { useAuth } from "@/hooks/use-auth";
import {
  OPERATOR_SAFETY_PREAMBLE,
  OPERATOR_PROMPT_BODY,
} from "@/lib/operator/system-prompt";
import { READ_ENDPOINTS } from "@/lib/operator/tool-scopes";
import { extractVaultKeyName } from "@/lib/operator/vault-ref";
import type { OperatorConfig, OperatorAuthMode } from "@/lib/api/operator";
import type { ActivationStage } from "@/hooks/use-operator";
import { cn } from "@/lib/utils";

interface OperatorActivationProps {
  initial: OperatorConfig;
  stage: ActivationStage;
  error: string | null;
  onActivate: (config: OperatorConfig, apiKey: string, baseUrl?: string) => void;
  onCancel?: () => void;
}

type Step = "model" | "review";

export function OperatorActivation({
  initial,
  stage,
  error,
  onActivate,
  onCancel,
}: OperatorActivationProps) {
  const { t } = useTranslation();
  const { method } = useAuth();
  const { data: vaultHealth } = useVaultHealth();

  const [step, setStep] = useState<Step>("model");
  const [provider, setProvider] = useState(initial.provider);
  const [model, setModel] = useState(initial.model);
  const [apiKey, setApiKey] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [environment, setEnvironment] = useState(initial.environment);
  const [promptBody, setPromptBody] = useState(initial.promptBody || OPERATOR_PROMPT_BODY);
  const [authMode, setAuthMode] = useState<OperatorAuthMode>(initial.authMode);
  const [tokenRiskAccepted, setTokenRiskAccepted] = useState(false);

  const providerConfig = getProviderConfig(provider);
  const needsKey = providerConfig?.needsKey ?? true;
  const baseUrlRequired = isBaseUrlRequired(provider);
  const suggestions = useMemo(() => MODEL_SUGGESTIONS[provider] ?? [], [provider]);
  const vaultDown = vaultHealth != null && vaultHealth.available === false;
  const oidcEnabled = method === "keycloak";
  const busy = stage !== "idle" && stage !== "done";

  /**
   * With OIDC on, `none` produces tool calls with no Authorization header, which
   * EDDI rejects — the operator would deploy READY and then fail every lookup.
   * Blocking here is the difference between an honest error and a silent dud.
   */
  const authModeUnusable = oidcEnabled && authMode === "none";
  const needsTokenAck = authMode === "caller-context" && !tokenRiskAccepted;

  const modelStepValid =
    Boolean(model.trim()) &&
    (!needsKey || Boolean(apiKey.trim())) &&
    (!baseUrlRequired || Boolean(baseUrl.trim()));

  const canActivate =
    modelStepValid && !authModeUnusable && !needsTokenAck && !busy;

  function handleProviderChange(next: string) {
    setProvider(next);
    const cfg = getProviderConfig(next);
    if (cfg) setModel(cfg.defaultModel);
    setApiKey("");
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
        scope: "read_only",
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
          <h1 className="text-2xl font-semibold">{t("operator.activation.title")}</h1>
        </div>
        <p className="text-sm text-muted-foreground">
          {t("operator.activation.subtitle")}
        </p>
        <Badge variant="success" className="gap-1">
          <Lock className="h-3 w-3" />
          {t("operator.readOnlyChip")}
        </Badge>
      </header>

      {step === "model" ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("operator.activation.modelStep")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <Field label={t("operator.activation.provider")} htmlFor="operator-provider">
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

            <Field label={t("operator.activation.model")} htmlFor="operator-model">
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
              <Field label={t("operator.activation.baseUrl")} htmlFor="operator-base-url">
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
                label={t("operator.activation.apiKey")}
                hint={t("operator.activation.apiKeyHint")}
                asGroup
              >
                {vaultDown && (
                  <Notice tone="warning" icon={AlertTriangle}>
                    {t("operator.activation.vaultDown")}
                  </Notice>
                )}
                <SecretKeyPicker
                  value={apiKey}
                  onChange={setApiKey}
                  placeholder={t("operator.activation.apiKeyPlaceholder")}
                  testId="operator-api-key"
                />
              </Field>
            )}

            <Field label={t("operator.activation.environment")} htmlFor="operator-environment">
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

            <AuthModeField
              authMode={authMode}
              onChange={(next) => {
                setAuthMode(next);
                setTokenRiskAccepted(false);
              }}
              oidcEnabled={oidcEnabled}
              tokenRiskAccepted={tokenRiskAccepted}
              onAcceptTokenRisk={setTokenRiskAccepted}
            />

            <div className="flex justify-end gap-2 pt-2">
              {onCancel && (
                <Button variant="ghost" onClick={onCancel}>
                  {t("common.cancel")}
                </Button>
              )}
              <Button
                onClick={() => setStep("review")}
                disabled={!modelStepValid}
                data-testid="operator-next"
              >
                {t("common.next")}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{t("operator.activation.reviewStep")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <Summary label={t("operator.activation.provider")} value={providerConfig?.name ?? provider} />
              <Summary label={t("operator.activation.model")} value={model} />
              <Summary label={t("operator.activation.environment")} value={environment} />
              <Summary
                label={t("operator.activation.authMode")}
                value={t(`operator.authMode.${authMode}.label`)}
              />
            </dl>

            <Field
              label={t("operator.activation.safetyPreamble")}
              hint={t("operator.activation.safetyPreambleHint")}
            >
              <pre className="max-h-40 overflow-auto rounded-md border border-border bg-muted/40 p-3 text-xs whitespace-pre-wrap text-muted-foreground">
                {OPERATOR_SAFETY_PREAMBLE}
              </pre>
            </Field>

            <Field
              label={t("operator.activation.promptBody")}
              hint={t("operator.activation.promptBodyHint")}
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

            <Field label={t("operator.activation.tools", { toolCount: READ_ENDPOINTS.length })}>
              <ul className="max-h-32 space-y-1 overflow-auto rounded-md border border-border bg-muted/40 p-3 font-mono text-xs text-muted-foreground">
                {READ_ENDPOINTS.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            </Field>

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
                {t("common.back")}
              </Button>
              <Button
                onClick={handleActivate}
                disabled={!canActivate}
                data-testid="operator-activate"
              >
                {t("operator.activation.activate")}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/* ─── Auth mode ─── */

interface AuthModeFieldProps {
  authMode: OperatorAuthMode;
  onChange: (mode: OperatorAuthMode) => void;
  oidcEnabled: boolean;
  tokenRiskAccepted: boolean;
  onAcceptTokenRisk: (accepted: boolean) => void;
}

function AuthModeField({
  authMode,
  onChange,
  oidcEnabled,
  tokenRiskAccepted,
  onAcceptTokenRisk,
}: AuthModeFieldProps) {
  const { t } = useTranslation();
  const modes: OperatorAuthMode[] = ["none", "caller-context"];

  return (
    <Field
      label={t("operator.activation.authMode")}
      hint={t("operator.activation.authModeHint")}
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
          {t("operator.activation.authNoneBlocked")}
        </Notice>
      )}

      {authMode === "caller-context" && (
        <div className="space-y-2">
          <Notice tone="warning" icon={KeyRound}>
            {t("operator.activation.tokenAtRestWarning")}
          </Notice>
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={tokenRiskAccepted}
              onChange={(e) => onAcceptTokenRisk(e.target.checked)}
              className="mt-1"
              data-testid="operator-accept-token-risk"
            />
            {t("operator.activation.tokenAtRestAck")}
          </label>
        </div>
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
  const hintId = hint && htmlFor ? `${htmlFor}-hint` : undefined;
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
        <div role={groupRole} aria-label={label} className="space-y-2">
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
  tone: "warning" | "error";
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
          : "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
      )}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  );
}
