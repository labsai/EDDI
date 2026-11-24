import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle } from "lucide-react";
import { Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { getErrorMessage, isApiError } from "@eddi/app-kit/lib/api-client";
import { useEmailRoles, useUpdateEmailRoles } from "@/hooks/use-channels";
import { toastChannelWarnings } from "@/lib/email-channel-messages";
import type { EmailRolePolicy } from "@/lib/api/channels";
import { parseRolesDocument, type RolesDocument } from "@/lib/email-roles";

function toText(doc: RolesDocument): string {
  return JSON.stringify(doc, null, 2);
}

/** Whether a policy gives a role something that rests on sender authentication. */
function isPowerful(policy: EmailRolePolicy | undefined): boolean {
  return !policy || policy.tools === undefined || policy.tools.length > 0;
}

/**
 * The sender roles of a saved email integration (email plan v3 §9): who a
 * sender is — bound to an authenticated sender only — and what that role may
 * reach. Applied on their own, as a new version, audited with the reason.
 * Disabled while the page holds unsaved changes, as the sender lists are.
 */
export function EmailSenderRolesEditor({
  integrationId,
  canEdit = true,
  disabled = false,
  onChanged,
}: {
  integrationId: string;
  /** The caller may edit the integration; without it only the summary is shown. */
  canEdit?: boolean;
  disabled?: boolean;
  onChanged: (version: number) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const { data, isLoading, isError, error, refetch } = useEmailRoles(integrationId);
  const mutation = useUpdateEmailRoles();
  const [text, setText] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  if (isError && !data) {
    return (
      <div className="space-y-2" role="alert" data-testid="email-roles-error">
        <p className="text-xs text-destructive">{getErrorMessage(error)}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
          {t("common.retry", "Retry")}
        </Button>
      </div>
    );
  }
  if (isLoading || !data) {
    return <p className="text-xs text-muted-foreground">{t("emailRoles.loading", "Loading the sender roles…")}</p>;
  }

  const stored: RolesDocument = { senderRoles: data.senderRoles, rolePolicies: data.rolePolicies };
  const current = text ?? toText(stored);
  const parsed = parseRolesDocument(current);
  const editing = text !== null && text !== toText(stored);
  const powerful = data.senderRoles.filter((r) => isPowerful(data.rolePolicies[r.role]));
  // Roles apply as soon as either list is non-empty: a lone default/anonymous
  // policy is a restriction too (the backend's EmailControl.isActive).
  const policyNames = Object.keys(data.rolePolicies ?? {});
  const active = data.senderRoles.length > 0 || policyNames.length > 0;
  const builtInPolicies = policyNames.filter((name) => !data.senderRoles.some((r) => r.role === name));

  const apply = async () => {
    if (!parsed) {
      toast.error(t("emailRoles.invalidJson", "The roles must be a JSON object with senderRoles and rolePolicies."));
      return;
    }
    try {
      const result = await mutation.mutateAsync({
        id: integrationId,
        change: { expectedVersion: data.version, ...parsed, reason: reason.trim() || undefined },
      });
      setText(null);
      setReason("");
      if (result.changed) {
        toast.success(t("emailRoles.saved", { version: result.version, defaultValue: "Sender roles saved as version {{version}}." }));
        toastChannelWarnings(result.warnings ?? [], t);
        onChanged(result.version);
      } else {
        toast.info(t("emailRoles.nothingChanged", "Nothing changed."));
      }
    } catch (err) {
      if (isApiError(err) && err.status === 409) {
        toast.error(t("emailRoles.conflict", "Someone changed this integration meanwhile. The roles were reloaded; check them and apply again."));
        setText(null);
        await refetch();
        return;
      }
      toast.error(getErrorMessage(err));
    }
  };

  return (
    <div className="space-y-3 sm:col-span-2" data-testid="email-sender-roles">
      <span className="text-xs font-medium">{t("emailRoles.title", "Sender roles")}</span>
      {!active ? (
        <p className="text-[10px] text-muted-foreground" data-testid="email-roles-none">
          {t("emailRoles.none", "No roles: every allowed sender reaches every target with the agent's own tools.")}
        </p>
      ) : (
        <ul className="space-y-1 text-xs" data-testid="email-roles-summary">
          {data.senderRoles.map((r) => {
            const policy = data.rolePolicies[r.role];
            return (
              <li key={r.role}>
                <span className="font-medium">{r.role}</span>{" "}
                <span className="text-muted-foreground">
                  {t("emailRoles.summary", {
                    senders: r.senders.join(", "),
                    targets: policy?.targets?.join(", ") ?? "*",
                    tools: policy?.tools === undefined ? t("emailRoles.agentTools", "the agent's tools") : policy.tools.join(", ") || "—",
                    defaultValue: "{{senders}} → targets: {{targets}}; tools: {{tools}}",
                  })}
                </span>
              </li>
            );
          })}
          {builtInPolicies.map((name) => {
            const policy = data.rolePolicies[name];
            return (
              <li key={name}>
                <span className="font-medium">{name}</span>{" "}
                <span className="text-muted-foreground">
                  {t("emailRoles.summary", {
                    senders: name === "anonymous" ? t("emailRoles.unauthenticated", "unauthenticated mail") : t("emailRoles.otherSenders", "other authenticated senders"),
                    targets: policy?.targets?.join(", ") ?? "*",
                    tools: name === "anonymous" ? "—" : policy?.tools === undefined ? t("emailRoles.agentTools", "the agent's tools") : policy.tools.join(", ") || "—",
                    defaultValue: "{{senders}} → targets: {{targets}}; tools: {{tools}}",
                  })}
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {powerful.length > 0 && (
        <p className="flex items-start gap-1 text-[10px] text-muted-foreground" data-testid="email-roles-trust">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-warning" />
          {t(
            "emailRoles.trust",
            "A role trusts the sender's domain signature (DKIM): it rests on your mail provider preventing one person from sending as another (aliases, shared mailboxes, \"send as\"). Run the self-test to check the domain.",
          )}
        </p>
      )}
      {canEdit && (<>
      <label htmlFor={`${uid}-json`} className="text-xs font-medium">
        {t("emailRoles.editLabel", "Roles and policies (JSON)")}
      </label>
      <textarea
        id={`${uid}-json`}
        data-testid="email-roles-json"
        className="h-48 w-full rounded-md border border-border bg-background p-2 font-mono text-xs"
        value={current}
        disabled={disabled}
        spellCheck={false}
        onChange={(e) => setText(e.target.value)}
      />
      <p className="text-[10px] text-muted-foreground">
        {t(
          "emailRoles.hint",
          "First matching role wins. A role binds to an authenticated sender only; unauthenticated mail is 'anonymous' and gets no tools. A role can only narrow: targets, tools and a lower maxTurnsPerSenderPerDay. A conversation that an email started can never change these roles.",
        )}
      </p>
      {disabled && (
        <p className="text-[10px] text-muted-foreground" data-testid="email-roles-disabled">
          {t("emailRoles.saveFirst", "Save or discard your other changes first; the sender roles are applied on their own.")}
        </p>
      )}
      {editing && (
        <div className="space-y-2 rounded-lg border border-border/60 p-3">
          {!parsed && (
            <p className="text-xs text-destructive" data-testid="email-roles-invalid">
              {t("emailRoles.invalidJson", "The roles must be a JSON object with senderRoles and rolePolicies.")}
            </p>
          )}
          <label htmlFor={`${uid}-reason`} className="text-xs font-medium">{t("emailRoles.reason", "Why (for the audit log)")}</label>
          <Input
            id={`${uid}-reason`}
            data-testid="email-roles-reason"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
          />
          <div className="flex gap-2">
            <Button type="button" size="sm" data-testid="email-roles-apply" disabled={disabled || mutation.isPending || !parsed} onClick={apply}>
              {t("emailRoles.apply", "Apply role changes")}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setText(null)} data-testid="email-roles-discard">
              {t("emailRoles.discard", "Discard")}
            </Button>
          </div>
        </div>
      )}
      </>)}
    </div>
  );
}
