import type { TFunction } from "i18next";
import type { AuthType, GrantStatus } from "./api/connections";

/**
 * The words the UI uses for a connection's enums, in one place.
 *
 * These were duplicated between the badge and the editor's type select, which
 * is the drift the badge file's own header says it exists to prevent: rename
 * the wording in one and the chip on the list disagrees with the option an
 * admin picks in the editor about what the same enum value is called.
 *
 * A plain module taking `t` rather than a hook, so component files can import
 * it without tripping `react-refresh/only-export-components` — the constraint
 * that produced the copy in the first place. The keys and English defaults are
 * literals here so `check-i18n.mjs` can still find them by scanning.
 */

/**
 * The auth *shape*, in the words an administrator would use.
 *
 * Never the enum constant: `OAUTH2_CLIENT_CREDENTIALS` tells somebody who
 * already knows OAuth what they already knew, and everybody else nothing.
 */
export function authTypeLabel(t: TFunction, authType: AuthType): string {
  switch (authType) {
    case "STATIC":
      return t("connections.authType.static", "API key");
    case "BASIC":
      return t("connections.authType.basic", "Username & password");
    case "OAUTH2_CLIENT_CREDENTIALS":
      return t("connections.authType.clientCredentials", "OAuth service account");
    case "OAUTH2_AUTHORIZATION_CODE":
      return t("connections.authType.authorizationCode", "OAuth user login");
  }
}

/** One line describing what an auth type means, for the create wizard's choices. */
export function authTypeDescription(t: TFunction, authType: AuthType): string {
  switch (authType) {
    case "STATIC":
      return t(
        "connections.choice.staticBody",
        "One fixed header, the same for everyone — X-Api-Key, or Authorization: Bearer …",
      );
    case "BASIC":
      return t(
        "connections.choice.basicBody",
        "HTTP Basic. EDDI does the encoding, so the password stays a separate vault entry you can rotate on its own.",
      );
    case "OAUTH2_CLIENT_CREDENTIALS":
      return t(
        "connections.choice.clientCredentialsBody",
        "The agent signs in as itself. One access token shared by everyone, refreshed automatically.",
      );
    case "OAUTH2_AUTHORIZATION_CODE":
      return t(
        "connections.choice.authorizationCodeBody",
        "Each person connects their own account and the agent acts as them. Needs sign-in to be switched on, and a vault.",
      );
  }
}

/**
 * A grant's state, phrased by what it means for the person reading it.
 *
 * `EXPIRED` must not look like a failure: an expired access token is refreshed
 * on the next call, so it is a normal resting state and telling somebody to
 * reconnect over it costs them a consent screen for nothing.
 */
export function grantStatusLabel(t: TFunction, status: GrantStatus): string {
  switch (status) {
    case "ACTIVE":
      return t("connections.status.active", "Connected");
    case "EXPIRED":
      return t("connections.status.expired", "Renewing");
    case "REVOKED":
      return t("connections.status.revoked", "Revoked");
    case "REFRESH_FAILED":
      return t("connections.status.refreshFailed", "Reconnect needed");
  }
}
