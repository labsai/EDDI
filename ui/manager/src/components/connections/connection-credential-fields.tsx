import { useId, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Hand, KeyRound, type LucideIcon } from "lucide-react";
import { Input } from "@/components/ui/input";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { HeaderValueField } from "@/components/connections/header-value-field";
import { ValidationMessage } from "@/components/connections/validation-message";
import {
  isOAuthType,
  legalBindings,
  type ConnectionErrors,
} from "@/lib/connection-validation";
import { bindingLabel } from "@/lib/connection-labels";
import type {
  Binding,
  ConnectionConfiguration,
  StaticAuth,
  OAuthConfig,
} from "@/lib/api/connections";

/** The header an integrating system sends a caller-supplied credential in. */
const CALLER_CREDENTIAL_HEADER = "X-EDDI-Connection-Credential";

interface ConnectionCredentialFieldsProps {
  draft: ConnectionConfiguration;
  onPatchStatic: (patch: Partial<StaticAuth>) => void;
  onPatchOAuth: (patch: Partial<OAuthConfig>) => void;
  /**
   * Where a STATIC connection's key comes from — shared, or handed over by the
   * caller per request. The only auth type with a choice; omitting the handler
   * renders the current binding without offering to change it.
   */
  onBindingChange?: (binding: Binding) => void;
  /** Only the errors this form should show yet — the caller decides when. */
  errors: ConnectionErrors;
  /** Distinguishes the wizard's copy of these fields from the editor's. */
  idPrefix: string;
  /** The wizard uses full-size labels; the editor's sections are denser. */
  dense?: boolean;
  readOnly?: boolean;
}

/**
 * The credential fields every connection needs, whichever surface is asking.
 *
 * The create wizard and the editor were two near-verbatim copies of this markup
 * — same order, same handlers, differing only in label size and id prefix. The
 * cost was not the duplication itself but that a change had to be found twice:
 * the backend adding a required field, or a validation key being renamed, fixes
 * one screen and leaves the other producing documents the backend refuses.
 *
 * What is NOT here is everything only the editor offers — scopes, extra
 * parameters, the client auth method, the discovery URL. Those are refinements
 * of a connection that already exists, and folding them in behind a `full` flag
 * would rebuild the copy-with-slight-variation this replaced.
 */
export function ConnectionCredentialFields({
  draft,
  onPatchStatic,
  onPatchOAuth,
  onBindingChange,
  errors,
  idPrefix,
  dense,
  readOnly,
}: ConnectionCredentialFieldsProps) {
  const { t } = useTranslation();
  // Suffixed so two instances on one page (never today, but the wizard and the
  // editor are one route change from meeting) cannot collide on ids.
  const unique = useId();
  const fieldId = (name: string) => `${idPrefix}-${name}-${unique}`;
  const labelClass = dense ? "text-xs font-medium" : "text-sm font-medium text-foreground";

  if (!isOAuthType(draft.authType)) {
    const callerSupplied =
      draft.authType === "STATIC" && draft.binding === "CALLER_SUPPLIED";
    return (
      <div className="space-y-4">
        {draft.authType === "STATIC" && (
          <StaticBindingChooser
            value={draft.binding}
            onChange={onBindingChange}
            readOnly={readOnly}
            error={errors.binding}
            idPrefix={idPrefix}
            labelClass={labelClass}
          />
        )}

        <div className="space-y-1.5">
          <label className={labelClass} htmlFor={fieldId("header-name")}>
            {t("connections.headerName", "Header name")}
          </label>
          <Input
            id={fieldId("header-name")}
            data-testid={`${idPrefix}-header-name`}
            className="font-mono text-xs"
            dir="ltr"
            value={draft.staticAuth?.headerName ?? ""}
            onChange={(e) => onPatchStatic({ headerName: e.target.value })}
            placeholder="Authorization"
            readOnly={readOnly}
            aria-invalid={errors["staticAuth.headerName"] !== undefined || undefined}
            aria-describedby={fieldId("header-name-error")}
          />
          <ValidationMessage
            code={errors["staticAuth.headerName"]}
            id={fieldId("header-name-error")}
            testId={`${idPrefix}-header-name-error`}
          />
        </div>

        {callerSupplied ? (
          <CallerSuppliedNotice
            name={draft.name}
            headerName={draft.staticAuth?.headerName ?? ""}
            testId={`${idPrefix}-caller-supplied-header`}
          />
        ) : draft.authType === "STATIC" ? (
          <div className="space-y-1.5">
            {/* A span, not a label: the control it names is a group of two
                inputs, so it is wired with aria-labelledby. A <label> with no
                htmlFor would name nothing. */}
            <span id={fieldId("header-value-label")} className={`block ${labelClass}`}>
              {t("connections.headerValue", "Header value")}
            </span>
            <HeaderValueField
              labelledBy={fieldId("header-value-label")}
              id={fieldId("header-value")}
              value={draft.staticAuth?.valueTemplate ?? ""}
              onChange={(valueTemplate) => onPatchStatic({ valueTemplate })}
              error={errors["staticAuth.valueTemplate"]}
              readOnly={readOnly}
              testIdPrefix={`${idPrefix}-header-value`}
            />
          </div>
        ) : (
          <>
            <div className="space-y-1.5">
              <label className={labelClass} htmlFor={fieldId("username")}>
                {t("connections.username", "Username")}
              </label>
              <Input
                id={fieldId("username")}
                data-testid={`${idPrefix}-username`}
                value={draft.staticAuth?.username ?? ""}
                onChange={(e) => onPatchStatic({ username: e.target.value })}
                autoComplete="off"
                readOnly={readOnly}
                aria-invalid={errors["staticAuth.username"] !== undefined || undefined}
                aria-describedby={fieldId("username-error")}
              />
              <ValidationMessage
                code={errors["staticAuth.username"]}
                id={fieldId("username-error")}
                testId={`${idPrefix}-username-error`}
              />
            </div>
            <div className="space-y-1.5">
              <label className={labelClass} htmlFor={fieldId("password")}>
                {t("connections.password", "Password")}
              </label>
              <SecretKeyPicker
                id={fieldId("password")}
                value={draft.staticAuth?.passwordRef ?? ""}
                onChange={(passwordRef) => onPatchStatic({ passwordRef })}
                referenceOnly
                readOnly={readOnly}
                aria-describedby={fieldId("password-error")}
                testId={`${idPrefix}-password`}
              />
              <ValidationMessage
                code={errors["staticAuth.passwordRef"]}
                id={fieldId("password-error")}
                testId={`${idPrefix}-password-error`}
              />
            </div>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {draft.authType === "OAUTH2_AUTHORIZATION_CODE" && (
        <div className="space-y-1.5">
          <label className={labelClass} htmlFor={fieldId("authorization-url")}>
            {t("connections.authorizationUrl", "Authorization URL")}
          </label>
          <Input
            id={fieldId("authorization-url")}
            data-testid={`${idPrefix}-authorization-url`}
            className="font-mono text-xs"
            dir="ltr"
            value={draft.oauth?.authorizationUrl ?? ""}
            onChange={(e) => onPatchOAuth({ authorizationUrl: e.target.value })}
            placeholder="https://auth.example.com/authorize"
            readOnly={readOnly}
            aria-invalid={errors["oauth.authorizationUrl"] !== undefined || undefined}
            aria-describedby={fieldId("authorization-url-error")}
          />
          <p className="text-xs text-muted-foreground">
            {t(
              "connections.authorizationUrlHint",
              "Where people are sent to approve access.",
            )}
          </p>
          <ValidationMessage
            code={errors["oauth.authorizationUrl"]}
            id={fieldId("authorization-url-error")}
            testId={`${idPrefix}-authorization-url-error`}
          />
        </div>
      )}

      <div className="space-y-1.5">
        <label className={labelClass} htmlFor={fieldId("token-url")}>
          {t("connections.tokenUrl", "Token URL")}
        </label>
        <Input
          id={fieldId("token-url")}
          data-testid={`${idPrefix}-token-url`}
          className="font-mono text-xs"
          dir="ltr"
          value={draft.oauth?.tokenUrl ?? ""}
          onChange={(e) => onPatchOAuth({ tokenUrl: e.target.value })}
          placeholder="https://auth.example.com/oauth/token"
          readOnly={readOnly}
          aria-invalid={errors["oauth.tokenUrl"] !== undefined || undefined}
          aria-describedby={fieldId("token-url-error")}
        />
        <p className="text-xs text-muted-foreground">
          {t(
            "connections.credentialEndpointHint",
            "The client secret is sent here, so it must be https and its origin must be one the operator has allowlisted for credential endpoints.",
          )}
        </p>
        <ValidationMessage
          code={errors["oauth.tokenUrl"]}
          id={fieldId("token-url-error")}
          testId={`${idPrefix}-token-url-error`}
        />
      </div>

      <div className={dense ? "grid gap-4 sm:grid-cols-2" : "space-y-4"}>
        <div className="space-y-1.5">
          <label className={labelClass} htmlFor={fieldId("client-id")}>
            {t("connections.clientId", "Client ID")}
          </label>
          <Input
            id={fieldId("client-id")}
            data-testid={`${idPrefix}-client-id`}
            className="font-mono text-xs"
            dir="ltr"
            value={draft.oauth?.clientId ?? ""}
            onChange={(e) => onPatchOAuth({ clientId: e.target.value })}
            autoComplete="off"
            readOnly={readOnly}
            aria-invalid={errors["oauth.clientId"] !== undefined || undefined}
            aria-describedby={fieldId("client-id-error")}
          />
          <ValidationMessage
            code={errors["oauth.clientId"]}
            id={fieldId("client-id-error")}
            testId={`${idPrefix}-client-id-error`}
          />
        </div>
        <div className="space-y-1.5">
          <label className={labelClass} htmlFor={fieldId("client-secret")}>
            {t("connections.clientSecret", "Client secret")}
          </label>
          <SecretKeyPicker
            id={fieldId("client-secret")}
            value={draft.oauth?.clientSecret ?? ""}
            onChange={(clientSecret) => onPatchOAuth({ clientSecret })}
            referenceOnly
            readOnly={readOnly}
            aria-describedby={fieldId("client-secret-error")}
            testId={`${idPrefix}-client-secret`}
          />
          <ValidationMessage
            code={errors["oauth.clientSecret"]}
            id={fieldId("client-secret-error")}
            testId={`${idPrefix}-client-secret-error`}
          />
        </div>
      </div>
    </div>
  );
}

/* ─── Where a STATIC key comes from ─────────────────────────────── */

const BINDING_ICONS: Record<"SERVICE" | "CALLER_SUPPLIED", LucideIcon> = {
  SERVICE: KeyRound,
  CALLER_SUPPLIED: Hand,
};

/**
 * `SERVICE` or `CALLER_SUPPLIED` — the one binding decision an author makes.
 *
 * Offered only for STATIC, where both are legal; every other type has exactly
 * one binding and the editor sets it silently. The choice is about authority,
 * not convenience: an agent holding one shared key can reach everything that
 * key can, and only its own reasoning stands between a user and data they
 * should not see. A caller-supplied credential makes the target platform's
 * permissions the boundary instead.
 *
 * A real radio group, like the auth-type chooser it sits beside.
 */
function StaticBindingChooser({
  value,
  onChange,
  readOnly,
  error,
  idPrefix,
  labelClass,
}: {
  value: Binding;
  onChange?: (binding: Binding) => void;
  readOnly?: boolean;
  error?: ConnectionErrors["binding"];
  idPrefix: string;
  labelClass: string;
}) {
  const { t } = useTranslation();
  const groupRef = useRef<HTMLDivElement>(null);
  const options = legalBindings("STATIC");
  const disabled = readOnly || !onChange;

  const move = (delta: number) => {
    if (disabled) return;
    const index = options.indexOf(value);
    const next = options[(index + delta + options.length) % options.length]!;
    groupRef.current
      ?.querySelector<HTMLElement>(`[data-testid="${idPrefix}-binding-choice-${next}"]`)
      ?.focus();
    onChange(next);
  };

  return (
    <fieldset className="space-y-1.5">
      <legend className={labelClass}>
        {t("connections.bindingChooser", "Where the key comes from")}
      </legend>
      <div
        ref={groupRef}
        role="radiogroup"
        aria-label={t("connections.bindingChooser", "Where the key comes from")}
        aria-invalid={error !== undefined || undefined}
        className="grid gap-2 sm:grid-cols-2"
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || e.key === "ArrowRight") {
            e.preventDefault();
            move(1);
          } else if (e.key === "ArrowUp" || e.key === "ArrowLeft") {
            e.preventDefault();
            move(-1);
          }
        }}
      >
        {options.map((binding) => {
          const Icon = BINDING_ICONS[binding as "SERVICE" | "CALLER_SUPPLIED"];
          const selected = value === binding;
          return (
            <button
              key={binding}
              type="button"
              role="radio"
              aria-checked={selected}
              tabIndex={selected ? 0 : -1}
              disabled={disabled}
              onClick={() => onChange?.(binding)}
              data-testid={`${idPrefix}-binding-choice-${binding}`}
              className={`flex items-start gap-2.5 rounded-lg border p-3 text-start transition-colors disabled:cursor-default ${
                selected
                  ? "border-primary bg-primary/5"
                  : "border-border hover:border-primary/40 hover:bg-secondary/50"
              }`}
            >
              <Icon
                className={`mt-0.5 h-4 w-4 shrink-0 ${selected ? "text-primary" : "text-muted-foreground"}`}
                aria-hidden="true"
              />
              <span className="min-w-0">
                <span className="block text-xs font-medium text-foreground">
                  {bindingLabel(t, binding)}
                </span>
                <span className="block text-[11px] text-muted-foreground">
                  {binding === "CALLER_SUPPLIED"
                    ? t(
                        "connections.bindingChoice.callerSuppliedBody",
                        "EDDI stores nothing. The system calling EDDI sends each user's own credential with every request, so the agent can do only what that user can do — the target's own permissions are the boundary.",
                      )
                    : t(
                        "connections.bindingChoice.serviceBody",
                        "One key in the vault, sent for everyone. Simple — but the agent can reach everything that key can.",
                      )}
                </span>
              </span>
            </button>
          );
        })}
      </div>
      <ValidationMessage code={error} testId={`${idPrefix}-binding-error`} />
    </fieldset>
  );
}

/* ─── What the caller has to send ───────────────────────────────── */

/**
 * The exact header an integrator attaches, in place of the value field a
 * shared key would have.
 *
 * Shown rather than described: the format — connection name, one space, then
 * the whole header value — is the one thing the person wiring the calling
 * system needs to copy, and a sentence about it is easier to get subtly wrong
 * than the line itself.
 */
function CallerSuppliedNotice({
  name,
  headerName,
  testId,
}: {
  name: string;
  headerName: string;
  testId: string;
}) {
  const { t } = useTranslation();
  const connectionName = name.trim() || "<name>";
  return (
    <div
      className="space-y-2 rounded-lg border border-border bg-muted/30 p-3 text-xs"
      data-testid={testId}
    >
      <p className="font-medium text-foreground">
        {t("connections.callerSuppliedHeaderTitle", "What the calling system sends")}
      </p>
      <code
        className="block overflow-x-auto rounded bg-muted/60 px-2 py-1.5 font-mono text-[11px] text-foreground"
        dir="ltr"
      >
        {CALLER_CREDENTIAL_HEADER}: {connectionName} {"<value>"}
      </code>
      <p className="text-muted-foreground">
        {t("connections.callerSuppliedHeaderBody", {
          header: headerName.trim() || "Authorization",
          defaultValue:
            "One header per request: the connection name, a space, then the whole value to send as {{header}} — scheme included, so “Bearer abc” needs no escaping. The credential lives for that request only and is never stored.",
        })}
      </p>
      <p className="text-muted-foreground">
        {t(
          "connections.callerSuppliedNeedsAuth",
          "The caller must be signed in to EDDI. A request without the credential fails rather than going out unauthenticated — including when a paused tool call is resumed — and this connection is withheld from MCP and A2A discovery.",
        )}
      </p>
    </div>
  );
}
