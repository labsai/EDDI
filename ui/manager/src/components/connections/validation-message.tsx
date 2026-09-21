import { useTranslation } from "react-i18next";
import { AlertCircle } from "lucide-react";
import type { ValidationCode } from "@/lib/connection-validation";

interface ValidationMessageProps {
  /** Nothing renders when this is absent, so a caller can pass a lookup result. */
  code?: ValidationCode;
  /** Wired to the field's `aria-describedby` so the error is announced with it. */
  id?: string;
  testId?: string;
}

/**
 * One broken rule, as a sentence.
 *
 * The validator is pure and returns codes; the translation happens here, in one
 * place, with literal keys so `check-i18n.mjs` can see every one of them. A
 * `t(\`connections.validation.${code}\`)` would be invisible to that scanner —
 * which is exactly how 349 keys once shipped as English in eleven locales.
 *
 * The copy is written to be actionable rather than accurate-and-useless: the
 * backend's own message for a literal secret is a paragraph about export
 * scrubbing, which is the right explanation for a log and the wrong one for
 * somebody looking at a field.
 */
export function ValidationMessage({ code, id, testId }: ValidationMessageProps) {
  const { t } = useTranslation();
  if (!code) return null;

  return (
    <p
      id={id}
      role="alert"
      data-testid={testId ?? "connection-validation-message"}
      className="mt-1 flex items-start gap-1 text-[11px] text-destructive"
    >
      <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
      <span>{messageFor(t, code)}</span>
    </p>
  );
}

function messageFor(
  t: ReturnType<typeof useTranslation>["t"],
  code: ValidationCode,
): string {
  switch (code) {
    case "nameRequired":
      return t(
        "connections.validation.nameRequired",
        "A name is required — it is what ${connection:name} refers to.",
      );
    case "nameFormat":
      return t(
        "connections.validation.nameFormat",
        "Use letters, digits, dots, dashes or underscores. Other characters make ${connection:…} silently fail to resolve.",
      );
    case "nameTooLong":
      return t(
        "connections.validation.nameTooLong",
        "A name is at most 64 characters.",
      );
    case "timeoutRange":
      return t(
        "connections.validation.timeoutRange",
        "The timeout must be a whole number between 1 and 60000 milliseconds. Leave it empty for the default.",
      );
    case "templateLiteralCredential":
      return t(
        "connections.validation.templateLiteralCredential",
        "The text around the reference looks like a credential itself. Only a short scheme prefix such as “Bearer ” belongs outside ${vault:…} — store the rest in the vault.",
      );
    case "paramReserved":
      return t(
        "connections.validation.paramReserved",
        "One of these parameters is set by EDDI itself (client_id, redirect_uri, response_type, state, code_challenge…). Overriding it would break the flow.",
      );
    case "paramValueReference":
      return t(
        "connections.validation.paramValueReference",
        "Parameter values are sent exactly as written — a ${…} reference here is not resolved, and would put a vault pointer in a URL. Use plain protocol values only.",
      );
    case "paramValueTooLong":
      return t(
        "connections.validation.paramValueTooLong",
        "A parameter value is at most 512 characters.",
      );
    case "paramValueCredentialShaped":
      return t(
        "connections.validation.paramValueCredentialShaped",
        "One of these values looks like a key or a token. This map is stored in plain text and sent in a URL — only non-secret protocol parameters belong here.",
      );
    case "bindingMismatch":
      return t(
        "connections.validation.bindingMismatch",
        "This binding does not go with this authentication type: caller-supplied needs an API key, per user needs an OAuth user login, and everything else is shared.",
      );
    case "callerSuppliedRefused":
      return t(
        "connections.validation.callerSuppliedRefused",
        "A caller-supplied connection stores no credential — the calling system sends it with every request. Clear this field.",
      );
    case "allowlistRequired":
      return t(
        "connections.validation.allowlistRequired",
        "Add at least one origin. A connection must name where its credential may be sent.",
      );
    case "originNotBare":
      return t(
        "connections.validation.originNotBare",
        "Origins are scheme://host[:port] only — no path, query, fragment or credentials.",
      );
    case "originScheme":
      return t(
        "connections.validation.originScheme",
        "An origin needs an http:// or https:// scheme.",
      );
    case "originHost":
      return t("connections.validation.originHost", "An origin needs a host name.");
    case "headerNameRequired":
      return t(
        "connections.validation.headerNameRequired",
        "Name the header the credential is sent in, e.g. Authorization.",
      );
    case "templateRequired":
      return t(
        "connections.validation.templateRequired",
        "A header value is required, e.g. Bearer ${vault:jira-token}.",
      );
    case "templateNoReference":
      return t(
        "connections.validation.templateNoReference",
        "This value has no ${vault:…} reference in it, so it is a plaintext credential. Store it in the vault and reference it here.",
      );
    case "templateBadSegment":
      return t(
        "connections.validation.templateBadSegment",
        "Only ${vault:…} and ${vars:…} may be interpolated into a header value.",
      );
    case "usernameRequired":
      return t(
        "connections.validation.usernameRequired",
        "Basic authentication needs a username.",
      );
    case "secretMustBeReference":
      return t(
        "connections.validation.secretMustBeReference",
        "This must be a ${vault:…} reference, not the secret itself. Store the value in the vault and point at it here.",
      );
    case "clientIdRequired":
      return t(
        "connections.validation.clientIdRequired",
        "The provider's client ID is required. It is public, not a secret.",
      );
    case "endpointRequired":
      return t("connections.validation.endpointRequired", "This URL is required.");
    case "endpointNotHttps":
      return t(
        "connections.validation.endpointNotHttps",
        "Must be https — the client secret is sent to this URL.",
      );
    case "endpointUserInfo":
      return t(
        "connections.validation.endpointUserInfo",
        "Remove the credentials from the URL; they change where the request actually goes.",
      );
    case "endpointNotAbsolute":
      return t(
        "connections.validation.endpointNotAbsolute",
        "Enter a full URL including the host, e.g. https://auth.example.com/oauth/token.",
      );
    case "paramCredentialShaped":
      return t(
        "connections.validation.paramCredentialShaped",
        "One of these parameters is named like a credential. Only non-secret protocol parameters (prompt, audience, …) belong here.",
      );
  }
}
