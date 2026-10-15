/**
 * The "ask an administrator" half of the grant dialog: the exact calls an admin
 * runs to add one agent to the grants it is missing.
 *
 * Kept free of React and i18n so it can be tested on its own. The copied text is
 * a set of shell commands, so it is not translated.
 */

/** The fields of a grant issue this needs. */
export interface GrantTarget {
  tenantId?: string;
  keyName?: string;
}

/**
 * `POST /secretstore/secrets/{tenant}/{key}/grant/agents/{agentId}` for every
 * issue that names a plain vault secret, segments encoded.
 *
 * The backend's `failure.fix.endpoints` already carries these for a refused
 * deploy; they are rebuilt here so the preflight path (which has no `fix`) and
 * the failure path produce the same request.
 */
export function grantEndpoints(targets: readonly GrantTarget[], agentId: string): string[] {
  const endpoints: string[] = [];
  for (const target of targets) {
    if (!target.tenantId || !target.keyName) continue;
    const endpoint =
      `POST /secretstore/secrets/${encodeURIComponent(target.tenantId)}/` +
      `${encodeURIComponent(target.keyName)}/grant/agents/${encodeURIComponent(agentId)}`;
    if (!endpoints.includes(endpoint)) endpoints.push(endpoint);
  }
  return endpoints;
}

/**
 * The request a non-admin copies for an administrator: a dry run of every
 * append first, then the appends themselves.
 *
 * The token is a placeholder on purpose — the signed-in user's own bearer must
 * never end up on a clipboard, let alone in a chat message to someone else.
 */
export function buildGrantRequestText(endpoints: readonly string[], baseUrl: string): string {
  const base = baseUrl.replace(/\/+$/, "");
  const line = (endpoint: string, dryRun: boolean) => {
    const [method = "POST", path = ""] = endpoint.split(" ", 2);
    const url = `${base}${path}${dryRun ? "?dryRun=true" : ""}`;
    return `curl -X ${method} "${url}" -H "Authorization: Bearer $EDDI_ADMIN_TOKEN"`;
  };
  return [
    "# 1. Dry run — writes nothing, shows the grant each call would produce",
    ...endpoints.map((endpoint) => line(endpoint, true)),
    "# 2. Add the agent to each grant",
    ...endpoints.map((endpoint) => line(endpoint, false)),
  ].join("\n");
}
