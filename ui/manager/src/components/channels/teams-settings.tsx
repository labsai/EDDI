import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@eddi/app-kit/components/ui/input";

const GUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * The fields the form checks before a save; the backend re-checks every rule
 * (and proves the connection by obtaining a token).
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

interface TeamsPlatformFieldsProps {
  platformConfig: Record<string, string>;
  onChange: (platformConfig: Record<string, string>) => void;
}

/**
 * The platformConfig of a `teams` integration. Ids only: the bot's
 * credential lives in the EDDI connection named here.
 */
export function TeamsPlatformFields({ platformConfig, onChange }: TeamsPlatformFieldsProps) {
  const { t } = useTranslation();
  const uid = useId();
  const set = (key: string, value: string) => {
    const next = { ...platformConfig };
    if (value.trim()) next[key] = value.trim();
    else delete next[key];
    onChange(next);
  };
  const field = (key: string, label: string, hint: string, placeholder: string) => (
    <div className="space-y-1.5" key={key}>
      <label className="text-sm font-medium text-foreground" htmlFor={`${uid}-${key}`}>{label}</label>
      <Input
        id={`${uid}-${key}`}
        data-testid={`teams-${key}`}
        value={platformConfig[key] ?? ""}
        onChange={(e) => set(key, e.target.value)}
        placeholder={placeholder}
      />
      <p className="text-xs text-muted-foreground">{hint}</p>
    </div>
  );
  return (
    <>
      {field("appId", t("teamsChannel.appId", "Microsoft App ID"),
        t("teamsChannel.appIdHint", "Required. The Azure Bot's Microsoft App ID."), "00000000-0000-0000-0000-000000000000")}
      {field("tenantId", t("teamsChannel.tenantId", "Tenant ID"),
        t("teamsChannel.tenantIdHint", "Required. The Entra tenant of the bot's single-tenant app registration."), "00000000-0000-0000-0000-000000000000")}
      {field("connection", t("teamsChannel.connection", "Connection"),
        t("teamsChannel.connectionHint", "Required. A SERVICE OAuth client-credentials connection for the same app ID, with the tenant's token URL and the scope https://api.botframework.com/.default. Saving obtains one token to prove it."), "teams-bot")}
      {field("allowedTenantIds", t("teamsChannel.allowedTenantIds", "Further allowed tenants"),
        t("teamsChannel.allowedTenantIdsHint", "Optional, comma-separated. Empty means the bot's own tenant only."), "")}
      {field("conversationId", t("teamsChannel.conversationId", "Channel or group chat ID"),
        t("teamsChannel.conversationIdHint", "Optional. A 19:… conversation this integration answers in. Leave empty to serve personal chats."), "19:…@thread.tacv2")}
    </>
  );
}
