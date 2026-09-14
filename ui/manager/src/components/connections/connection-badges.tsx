import { useTranslation } from "react-i18next";
import { KeyRound, Lock, Server, UserCheck, HelpCircle, Hand } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { authTypeLabel, bindingLabel, grantStatusLabel } from "@/lib/connection-labels";
import type { AuthType, Binding, GrantStatus } from "@/lib/api/connections";

/**
 * The three chips that describe a connection, in one file so the vocabulary
 * cannot drift between the list, the card and the editor.
 *
 * All three exports are components, which is what
 * `react-refresh/only-export-components` requires — the labels live inside them
 * rather than in an exported map for that reason as much as any other.
 */

const AUTH_TYPE_ICONS = {
  STATIC: KeyRound,
  BASIC: Lock,
  OAUTH2_CLIENT_CREDENTIALS: Server,
  OAUTH2_AUTHORIZATION_CODE: UserCheck,
} as const;

interface AuthTypeBadgeProps {
  /** `"unknown"` when the config could not be read — say so rather than guess. */
  authType: AuthType | "unknown";
  className?: string;
}

/**
 * The auth *shape*, in the words an administrator would use.
 *
 * Never the enum constant: `OAUTH2_CLIENT_CREDENTIALS` tells somebody who
 * already knows OAuth what they already knew, and tells everybody else nothing.
 */
export function AuthTypeBadge({ authType, className }: AuthTypeBadgeProps) {
  const { t } = useTranslation();

  if (authType === "unknown") {
    return (
      <Badge variant="outline" className={className}>
        <HelpCircle className="me-1 h-3 w-3" aria-hidden="true" />
        {t("connections.authType.unknown", "Unknown")}
      </Badge>
    );
  }

  const Icon = AUTH_TYPE_ICONS[authType];
  return (
    <Badge variant="outline" className={className} data-testid={`auth-type-${authType}`}>
      <Icon className="me-1 h-3 w-3" aria-hidden="true" />
      {authTypeLabel(t, authType)}
    </Badge>
  );
}

interface BindingBadgeProps {
  binding: Binding | "unknown";
  className?: string;
}

/**
 * Whose credential this resolves — the field that makes "an org-wide API key"
 * and "each person's own Google Drive" the same feature.
 *
 * Three values, three looks. `CALLER_SUPPLIED` gets its own chip rather than
 * folding into "Shared": nothing is stored for it and nothing is shared by it,
 * so the one word that would have described it was the one word that was wrong.
 */
export function BindingBadge({ binding, className }: BindingBadgeProps) {
  const { t } = useTranslation();
  if (binding === "unknown") return null;
  const variant =
    binding === "PER_USER"
      ? "warning"
      : binding === "CALLER_SUPPLIED"
        ? "outline"
        : "secondary";
  return (
    <Badge variant={variant} className={className} data-testid={`binding-${binding}`}>
      {binding === "CALLER_SUPPLIED" && (
        <Hand className="me-1 h-3 w-3" aria-hidden="true" />
      )}
      {bindingLabel(t, binding)}
    </Badge>
  );
}

interface GrantStatusBadgeProps {
  status: GrantStatus;
  className?: string;
}

/**
 * A grant's state, phrased by what it means for the person reading it.
 *
 * `EXPIRED` is the one that must not look like a failure: an expired access
 * token is refreshed on the next call, so it is a normal resting state and
 * telling somebody to reconnect over it costs them a consent screen for
 * nothing. `REFRESH_FAILED` is the terminal one — the refresh token itself was
 * rejected — and `REVOKED` cannot produce a token either; both are fixed by
 * connecting again.
 */
export function GrantStatusBadge({ status, className }: GrantStatusBadgeProps) {
  const { t } = useTranslation();
  const variant =
    status === "ACTIVE"
      ? "success"
      : status === "EXPIRED"
        ? "secondary"
        : status === "REVOKED"
          ? "warning"
          : "destructive";

  return (
    <Badge variant={variant} className={className} data-testid={`grant-status-${status}`}>
      {grantStatusLabel(t, status)}
    </Badge>
  );
}
