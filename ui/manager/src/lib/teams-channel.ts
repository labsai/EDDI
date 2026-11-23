const GUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * The `teams` platformConfig fields the form checks before a save, by key; the
 * backend re-checks every rule (and proves the connection by obtaining a token).
 */
export function teamsConfigProblems(platformConfig: Record<string, string>): string[] {
  const problems: string[] = [];
  if (!GUID.test(platformConfig.appId ?? "")) problems.push("appId");
  if (!GUID.test(platformConfig.tenantId ?? "")) problems.push("tenantId");
  if (!(platformConfig.connection ?? "").trim()) problems.push("connection");
  const allowed = (platformConfig.allowedTenantIds ?? "").trim();
  if (allowed && !allowed.split(",").every((v) => GUID.test(v.trim()))) problems.push("allowedTenantIds");
  const conversation = (platformConfig.conversationId ?? "").trim();
  if (conversation && !/^19:[A-Za-z0-9._@-]+$/.test(conversation)) problems.push("conversationId");
  return problems;
}
