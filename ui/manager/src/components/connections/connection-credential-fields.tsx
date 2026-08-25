import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { HeaderValueField } from "@/components/connections/header-value-field";
import { ValidationMessage } from "@/components/connections/validation-message";
import { isOAuthType, type ConnectionErrors } from "@/lib/connection-validation";
import type { ConnectionConfiguration, StaticAuth, OAuthConfig } from "@/lib/api/connections";

interface ConnectionCredentialFieldsProps {
  draft: ConnectionConfiguration;
  onPatchStatic: (patch: Partial<StaticAuth>) => void;
  onPatchOAuth: (patch: Partial<OAuthConfig>) => void;
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
    return (
      <div className="space-y-4">
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

        {draft.authType === "STATIC" ? (
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
