import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueries, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Copy, KeyRound, Loader2, Lock } from "lucide-react";
import { toast } from "sonner";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { useHasRole } from "@/hooks/use-auth";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  useGrantDialogStore,
  type GrantDecision,
  type GrantIssue,
  type GrantRequest,
} from "@/hooks/use-deploy-with-grants";
import {
  ALL_AGENTS,
  findSecret,
  grantAgentToSecret,
  grantsAllAgents,
  updateSecretGrant,
  type AffectedAgent,
} from "@/lib/api/secrets";
import { api, getErrorMessage } from "@/lib/api-client";
import { buildGrantRequestText, grantEndpoints } from "@/lib/grant-request";

/** How many agents to pull in to put names on the ids of a grant. */
const NAME_LOOKUP_LIMIT = 200;

/**
 * The one mounted grant dialog. Every deploy flow asks through
 * `requestGrantDecision`; this renders whatever is pending and hands the
 * answer back. Mounted once beside the toaster (and by the test render helpers).
 */
export function GrantRequiredDialogHost() {
  const pending = useGrantDialogStore((s) => s.pending);
  const answer = useGrantDialogStore((s) => s.answer);
  const registerHost = useGrantDialogStore((s) => s.registerHost);
  useEffect(() => registerHost(), [registerHost]);

  return <GrantRequiredDialog request={pending} onDecision={answer} />;
}

interface GrantRequiredDialogProps {
  /** `null` closes the dialog. */
  request: GrantRequest | null;
  /** Called once with the person's decision; `granted` only after the grant was written. */
  onDecision: (decision: GrantDecision) => void;
}

/**
 * "This agent uses a restricted vault key it is not granted — add it?"
 *
 * Takes its issues from either a preflight or a refused deploy, so the same
 * question is asked before a deploy and after one. Granting is an explicit admin
 * action: **Add and deploy** appends this one agent to each grant (dry run
 * first), and the wider "allow every agent" is a separate, warned choice that is
 * never preselected. A non-admin gets the request to copy for an administrator,
 * and no button that writes.
 */
export function GrantRequiredDialog({ request, onDecision }: GrantRequiredDialogProps) {
  const { t } = useTranslation();
  const title =
    request?.source === "failure"
      ? t("grantRequired.titleRefused", "Deployment refused: vault key not granted")
      : request?.agents
        ? t("grantRequired.titleMany", "These agents are not granted their vault keys")
        : t("grantRequired.title", "This agent is not granted its vault keys");
  return (
    <AccessibleDialog
      open={request !== null}
      onClose={() => onDecision("cancel")}
      title={title}
      maxWidth="max-w-lg"
      testId="grant-required-dialog"
    >
      {request && (
        <GrantRequiredBody
          key={`${request.agents?.map((a) => a.agentId).join(",") ?? request.agentId}/${request.version}/${request.source}`}
          request={request}
          onDecision={onDecision}
        />
      )}
    </AccessibleDialog>
  );
}

function GrantRequiredBody({ request, onDecision }: { request: GrantRequest; onDecision: (d: GrantDecision) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const isAdmin = useHasRole("eddi-admin");
  const [allowAll, setAllowAll] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Set when a dry run says agents would lose access; the next click applies anyway. */
  const [losingAccess, setLosingAccess] = useState<AffectedAgent[] | null>(null);

  const agentLabel = request.agentName || request.agentId;
  /** A batch request spans several agents; each key lists the ones that need it. */
  const batchAgents = request.agents;
  const agentIdsFor = (issue: GrantIssue): string[] => issue.agentIds ?? [request.agentId];
  const grantable = request.issues.filter((issue) => issue.tenantId && issue.keyName);
  const unfixable = request.issues.filter((issue) => !issue.tenantId || !issue.keyName);

  /* A refused deploy names the secrets but not their grants. An admin may read
   * them, so the dialog can still say who holds each key today. */
  const needsLookup = isAdmin && request.source === "failure";
  const lookups = useQueries({
    queries: grantable.map((issue) => ({
      queryKey: ["secrets", "grant-dialog", issue.tenantId, issue.keyName],
      queryFn: () => findSecret(issue.tenantId!, issue.keyName!),
      enabled: needsLookup && !issue.allowedAgents,
      staleTime: 10_000,
      retry: false,
    })),
  });

  const { data: rawAgents } = useAgentDescriptors(NAME_LOOKUP_LIMIT, 0, "");
  const nameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const agent of groupAgentsByName(Array.isArray(rawAgents) ? rawAgents : [])) map.set(agent.id, agent.name);
    return map;
  }, [rawAgents]);

  const allowedFor = (issue: GrantIssue, index: number): string[] | undefined => {
    if (issue.allowedAgents) return issue.allowedAgents;
    const looked = lookups[index]?.data;
    return looked && !grantsAllAgents(looked.allowedAgents) ? looked.allowedAgents : undefined;
  };

  const endpoints = [
    ...new Set(grantable.flatMap((issue) => agentIdsFor(issue).flatMap((id) => grantEndpoints([issue], id)))),
  ];
  const canDeployAnyway = request.enforcement === "WARN";

  const finish = (decision: GrantDecision) => {
    void queryClient.invalidateQueries({ queryKey: ["secrets"] });
    onDecision(decision);
  };

  const handleAdd = async () => {
    setBusy(true);
    setError(null);
    try {
      if (allowAll) {
        // Widening to every agent cannot take access away from anyone, so there
        // is no dry run to show — the warning sentence is the confirmation.
        for (const issue of grantable) {
          await updateSecretGrant({ tenantId: issue.tenantId!, keyName: issue.keyName!, allowedAgents: [ALL_AGENTS] });
        }
        finish("granted");
        return;
      }
      if (losingAccess === null) {
        const losing: AffectedAgent[] = [];
        for (const issue of grantable) {
          for (const id of agentIdsFor(issue)) {
            const dry = await grantAgentToSecret(issue.tenantId!, issue.keyName!, id, { dryRun: true });
            losing.push(...(dry.agentsLosingAccess ?? []));
          }
        }
        // An append never removes anyone, so this is empty by construction. If
        // the backend ever says otherwise, the admin sees it before anything is
        // written rather than after.
        if (losing.length > 0) {
          setLosingAccess(losing);
          return;
        }
      }
      // One confirmation, then an append per (key, agent): the backend's atomic
      // append is per agent, and replacing a key's whole list from here would
      // drop an agent someone else added a moment ago.
      for (const issue of grantable) {
        for (const id of agentIdsFor(issue)) {
          await grantAgentToSecret(issue.tenantId!, issue.keyName!, id);
        }
      }
      finish("granted");
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const handleCopy = async () => {
    const text = buildGrantRequestText(endpoints, api.getBaseUrl() || window.location.origin);
    try {
      await navigator.clipboard.writeText(text);
      toast.success(t("grantRequired.copied", "Request copied — send it to an administrator"));
    } catch {
      toast.error(t("common.copyFailed", "Failed to copy to clipboard"));
    }
  };

  return (
    <div className="space-y-4" data-testid="grant-required-body">
      {request.source === "failure" && request.failure?.message && (
        <p
          className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive"
          data-testid="grant-required-failure"
        >
          {request.failure.message}
        </p>
      )}

      <ul className="space-y-2">
        {grantable.map((issue, index) => {
          const allowed = allowedFor(issue, index);
          const count = allowed?.length ?? issue.allowedAgentCount;
          return (
            <li
              key={`${issue.tenantId}/${issue.keyName}`}
              className="flex items-start gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-sm"
              data-testid={`grant-issue-${issue.keyName}`}
            >
              <Lock className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
              <span>
                {batchAgents
                  ? t("grantRequired.issueMany", {
                      key: issue.keyName,
                      agents: agentIdsFor(issue)
                        .map((id) => batchAgents.find((a) => a.agentId === id)?.agentName || nameById.get(id) || id)
                        .join(", "),
                      defaultValue: "Agents using the restricted key \"{{key}}\", which is granted only to other agents: {{agents}}.",
                    })
                  : isAdmin && allowed && allowed.length > 0
                  ? t("grantRequired.issueWithAgents", {
                      agent: agentLabel,
                      key: issue.keyName,
                      agents: allowed.map((id) => nameById.get(id) ?? id).join(", "),
                      defaultValue: "\"{{agent}}\" uses the restricted key \"{{key}}\", currently allowed for: {{agents}}.",
                    })
                  : count != null
                    ? t("grantRequired.issueWithCount", {
                        agent: agentLabel,
                        key: issue.keyName,
                        count,
                        defaultValue: "\"{{agent}}\" uses the restricted key \"{{key}}\", currently allowed for {{count}} agents.",
                      })
                    : t("grantRequired.issue", {
                        agent: agentLabel,
                        key: issue.keyName,
                        defaultValue: "\"{{agent}}\" uses the restricted key \"{{key}}\", which is granted only to other agents.",
                      })}
              </span>
            </li>
          );
        })}
        {unfixable.map((issue) => (
          <li
            key={issue.reference}
            className="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm"
            data-testid="grant-issue-unfixable"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
            <span>
              {t("grantRequired.unfixable", {
                reference: issue.reference,
                defaultValue: "\"{{reference}}\" is not a plain vault reference, so it cannot be granted from here. Change the agent's configuration to use a vault key it may use.",
              })}
            </span>
          </li>
        ))}
      </ul>

      {isAdmin && grantable.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm font-medium text-foreground">
            {allowAll
              ? t("grantRequired.questionAll", "Allow every agent to use these keys?")
              : batchAgents
                ? t("grantRequired.questionMany", "Add all of these agents to the grant?")
                : t("grantRequired.question", {
                  agent: agentLabel,
                  defaultValue: "Add \"{{agent}}\" to the grant?",
                })}
          </p>
          <label className="flex items-start gap-2 text-xs text-muted-foreground">
            <input
              type="checkbox"
              checked={allowAll}
              onChange={(e) => {
                setAllowAll(e.target.checked);
                setLosingAccess(null);
              }}
              className="mt-0.5"
              data-testid="grant-required-allow-all"
            />
            <span>
              {t("grantRequired.allowAll", "Allow every agent to use this key instead")}
              {allowAll && (
                <span className="mt-1 block text-warning" data-testid="grant-required-allow-all-warning">
                  {t(
                    "grantRequired.allowAllWarning",
                    "Every agent on this deployment — including ones created later — will be able to use it. That removes the only limit on this key's reach.",
                  )}
                </span>
              )}
            </span>
          </label>
        </div>
      )}

      {!isAdmin && grantable.length > 0 && (
        <div className="space-y-2 rounded-lg border border-border px-3 py-2" data-testid="grant-required-non-admin">
          <p className="text-xs text-muted-foreground">
            {batchAgents
              ? t(
                  "grantRequired.nonAdminMany",
                  "Only an administrator can change who may use a vault key. Ask one to add these agents to the grant, then deploy them again.",
                )
              : t(
                  "grantRequired.nonAdmin",
                  "Only an administrator can change who may use a vault key. Ask one to add this agent to the grant, then deploy again.",
                )}
          </p>
          <Button variant="outline" size="sm" onClick={handleCopy} data-testid="grant-required-copy">
            <Copy className="me-2 h-3.5 w-3.5" />
            {t("grantRequired.copyRequest", "Copy request for an admin")}
          </Button>
        </div>
      )}

      {losingAccess && losingAccess.length > 0 && (
        <div
          className="rounded-md border border-warning/40 bg-warning/10 px-3 py-2 text-xs text-warning"
          role="alert"
          data-testid="grant-required-losing-access"
        >
          <p className="font-medium">
            {t("grantRequired.losingAccess", "These deployed agents would lose access to the key:")}
          </p>
          <ul className="mt-1 list-disc ps-4">
            {losingAccess.map((a) => (
              <li key={`${a.environment}/${a.agentId}/${a.agentVersion}`}>
                {nameById.get(a.agentId) ?? a.agentId} ({a.environment})
              </li>
            ))}
          </ul>
        </div>
      )}

      {error && (
        <p className="text-xs text-destructive" role="alert" data-testid="grant-required-error">
          {error}
        </p>
      )}

      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="ghost" onClick={() => onDecision("cancel")} disabled={busy} data-testid="grant-required-cancel">
          {t("common.cancel", "Cancel")}
        </Button>
        {canDeployAnyway && (
          <Button
            variant="outline"
            onClick={() => onDecision("deploy-anyway")}
            disabled={busy}
            data-testid="grant-required-deploy-anyway"
          >
            {t("grantRequired.deployAnyway", "Deploy anyway")}
          </Button>
        )}
        {isAdmin && grantable.length > 0 && (
          <Button onClick={handleAdd} disabled={busy} data-testid="grant-required-confirm">
            {busy ? <Loader2 className="me-2 h-4 w-4 animate-spin" /> : <KeyRound className="me-2 h-4 w-4" />}
            {losingAccess
              ? t("grantRequired.applyAnyway", "Apply anyway and deploy")
              : allowAll
                ? t("grantRequired.allowAllAndDeploy", "Allow all and deploy")
                : t("grantRequired.addAndDeploy", "Add and deploy")}
          </Button>
        )}
      </div>
    </div>
  );
}
