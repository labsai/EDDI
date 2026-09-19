import { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle, Bot, Globe, Info, Loader2, X } from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { AgentPicker } from "@/components/shared/agent-picker";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  useSecretGrantImpact,
  useUpdateSecretGrant,
} from "@/hooks/use-secrets";
import {
  ALL_AGENTS,
  grantsAllAgents,
  type SecretMetadata,
} from "@/lib/api/secrets";
import { getErrorMessage } from "@/lib/api-client";

interface EditGrantDialogProps {
  /** The secret being edited; `null` closes the dialog. */
  secret: SecretMetadata | null;
  onClose: () => void;
}

/** How many agents to pull in just to put names on the IDs in the list. */
const NAME_LOOKUP_LIMIT = 200;

/**
 * Edit which agents may use a vault secret — the `allowedAgents` grant.
 *
 * ## Why this is not the Add-Secret dialog with a different title
 *
 * Storing a secret needs its plaintext. Editing the grant must *not*: by the time
 * an operator needs to widen a grant the plaintext is gone, which is the entire
 * point of having vaulted it. Before the `/grant` endpoint existed the only way to
 * add one agent to a list was to re-`PUT` the whole secret, so an operator either
 * dug the value out of a backup, rotated the key, or — the path of least
 * resistance, and what actually happened — granted `["*"]` to everything and threw
 * away the only control limiting a secret's blast radius.
 *
 * ## Narrowing is the dangerous direction
 *
 * The grant is checked when an agent is *deployed*, not when a secret is resolved.
 * So removing an agent breaks nothing today and refuses its next deployment,
 * possibly weeks later, on a restart nobody connects to this dialog. The backend's
 * dry run names those agents and this dialog refuses to save until the operator
 * has ticked that they have seen them.
 */
export function EditGrantDialog({ secret, onClose }: EditGrantDialogProps) {
  const { t } = useTranslation();

  /* The fields live in a child so they mount only while the dialog is open. The
   * agent-name lookup and the impact dry run are queries; at this level they
   * would run on every visit to the secrets page, for a dialog nobody opened.
   * Keyed on the secret, so opening a different row starts from that row's grant
   * rather than from the previous one's — which is also why the child needs no
   * re-initialising effect. */
  return (
    <AccessibleDialog
      open={secret !== null}
      onClose={onClose}
      maxWidth="max-w-lg"
      testId="edit-grant-dialog"
      title={t("secrets.grantTitle", {
        key: secret?.keyName ?? "",
        defaultValue: `Who may use "${secret?.keyName ?? ""}"`,
      })}
    >
      {secret && (
        <GrantEditor
          key={`${secret.tenantId}/${secret.keyName}`}
          secret={secret}
          onClose={onClose}
        />
      )}
    </AccessibleDialog>
  );
}

/** The dialog's fields. Mounted only while the dialog is open. */
function GrantEditor({
  secret,
  onClose,
}: {
  secret: SecretMetadata;
  onClose: () => void;
}) {
  const { t } = useTranslation();

  /* The two halves of one decision: the wildcard, or a specific list. Kept as
   * separate state rather than as `["*"]` inside the list so that switching to
   * "all agents" and back does not discard the list the operator just built. */
  const [allAgents, setAllAgents] = useState(() =>
    grantsAllAgents(secret.allowedAgents),
  );
  const [agents, setAgents] = useState<string[]>(() =>
    grantsAllAgents(secret.allowedAgents)
      ? []
      : secret.allowedAgents.filter((agent) => agent !== ALL_AGENTS),
  );
  const [description, setDescription] = useState(secret.description ?? "");
  /* What the operator acknowledged, not merely that they did: the impact it was
   * given for. A different answer — another agent turns up, or the check fails —
   * is a different decision and needs its own tick. */
  const [acknowledgedFor, setAcknowledgedFor] = useState<string | null>(null);

  const updateMut = useUpdateSecretGrant();

  /* Names for the IDs, so a warning reads "Support Bot" rather than
   * 0123456789abcdef01234567. Same query the picker uses, one page deeper. */
  const { data: rawAgents } = useAgentDescriptors(NAME_LOOKUP_LIMIT, 0, "");
  const nameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const agent of groupAgentsByName(rawAgents ?? [])) {
      map.set(agent.id, agent.name);
    }
    return map;
  }, [rawAgents]);

  /**
   * The list that will actually be sent. Memoised so it is stable across renders
   * — it is a dependency of the save handler, and a fresh array on every
   * keystroke in the description field would rebuild that handler each time.
   */
  const proposed = useMemo(
    () => (allAgents ? [ALL_AGENTS] : agents),
    [allAgents, agents],
  );

  /* An empty specific list would be stored as the wildcard — the backend reads
   * "no entries" as "unrestricted" and always has. Refusing to send it is
   * clearer than silently turning "I removed everyone" into "I allowed
   * everyone". */
  const emptyList = !allAgents && agents.length === 0;

  const impactEnabled = !allAgents && !emptyList;
  const impact = useSecretGrantImpact({
    tenantId: secret.tenantId,
    keyName: secret.keyName,
    allowedAgents: proposed,
    enabled: impactEnabled,
  });
  /* "Not answered yet" and "could not answer" are not "nothing breaks". Treating
   * them as such let Save through while the dry run was still in flight, and
   * whenever it failed — the one moment the warning exists for. */
  const impactPending =
    impactEnabled && (impact.isPending || impact.isFetching);
  // A scan the backend could not finish counts as failed, not as "nothing
  // breaks": it answers 200 with a list that may be short.
  const impactIncomplete = impact.data?.agentsLosingAccessComplete === false;
  const impactFailed =
    impactEnabled && !impactPending && (impact.isError || impactIncomplete);
  const losingAccess = impactEnabled
    ? (impact.data?.agentsLosingAccess ?? [])
    : [];
  const needsAcknowledgement = losingAccess.length > 0 || impactFailed;
  const impactKey = impactFailed
    ? "check-failed"
    : losingAccess
        .map((a) => `${a.environment}/${a.agentId}/${a.agentVersion}`)
        .join(",");
  const acknowledged = acknowledgedFor === impactKey;

  const addAgent = useCallback((agentId: string) => {
    const trimmed = agentId.trim();
    if (!trimmed || trimmed === ALL_AGENTS) return;
    setAgents((current) =>
      current.includes(trimmed) ? current : [...current, trimmed],
    );
  }, []);

  const removeAgent = useCallback((agentId: string) => {
    setAgents((current) => current.filter((existing) => existing !== agentId));
  }, []);

  const label = (agentId: string) => nameById.get(agentId) ?? agentId;

  const canSave =
    !emptyList &&
    !updateMut.isPending &&
    !impactPending &&
    (!needsAcknowledgement || acknowledged);

  const handleSave = useCallback(() => {
    if (emptyList) return;
    updateMut.mutate(
      {
        tenantId: secret.tenantId,
        keyName: secret.keyName,
        allowedAgents: proposed,
        // Only when the operator changed it. Always sending it rewrote a missing
        // description as "" — a write nobody asked for. An edit to "" still
        // clears it, which is why this compares rather than tests truthiness.
        description:
          description !== (secret.description ?? "") ? description : undefined,
      },
      {
        onSuccess: () => {
          toast.success(
            t("secrets.grantSaved", {
              key: secret.keyName,
              defaultValue: `Access updated for "${secret.keyName}"`,
            }),
          );
          onClose();
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  }, [secret, emptyList, proposed, description, updateMut, onClose, t]);

  return (
    <div className="space-y-4">
      {/* The reassurance that makes this dialog safe to use: no value is
            involved, so nothing here can break a working secret. */}
      <div className="flex items-start gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2">
        <Info
          className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground"
          aria-hidden="true"
        />
        <p className="text-xs text-muted-foreground">
          {t(
            "secrets.grantValueUntouched",
            "The secret value is not changed and does not need to be re-entered. Only who may use it changes.",
          )}
        </p>
      </div>

      {/* Wildcard or a list. Two buttons rather than a checkbox because
            "all agents" is not a modifier of the list, it replaces it. */}
      <div
        role="radiogroup"
        aria-label={t("secrets.allowedAgents", "Allowed Agents")}
        className="grid grid-cols-2 gap-2"
        onKeyDown={(e) => {
          // Arrow keys move the selection, as in a native radio group; Home and
          // End go to the first and last option.
          const target =
            e.key === "Home"
              ? true
              : e.key === "End"
                ? false
                : ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(
                      e.key,
                    )
                  ? !allAgents
                  : null;
          if (target !== null) {
            e.preventDefault();
            const next = target;
            setAllAgents(next);
            e.currentTarget
              .querySelector<HTMLElement>(
                next
                  ? '[data-testid="grant-mode-all"]'
                  : '[data-testid="grant-mode-specific"]',
              )
              ?.focus();
          }
        }}
      >
        <button
          type="button"
          role="radio"
          aria-checked={allAgents}
          tabIndex={allAgents ? 0 : -1}
          onClick={() => setAllAgents(true)}
          data-testid="grant-mode-all"
          className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-start transition-colors ${
            allAgents
              ? "border-primary bg-primary/10"
              : "border-input hover:bg-secondary/50"
          }`}
        >
          <Globe className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span className="text-xs font-medium">
            {t("secrets.allAgents", "All agents")}
          </span>
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={!allAgents}
          tabIndex={allAgents ? -1 : 0}
          onClick={() => setAllAgents(false)}
          data-testid="grant-mode-specific"
          className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-start transition-colors ${
            !allAgents
              ? "border-primary bg-primary/10"
              : "border-input hover:bg-secondary/50"
          }`}
        >
          <Bot className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span className="text-xs font-medium">
            {t("secrets.grantOnlyTheseAgents", "Only these agents")}
          </span>
        </button>
      </div>

      {allAgents ? (
        <p className="text-xs text-muted-foreground">
          {t(
            "secrets.grantAllHint",
            "Every agent in this tenant may reference this secret. Narrow it to limit what a single leaked configuration can reach.",
          )}
        </p>
      ) : (
        <div className="space-y-2">
          {/* Chosen from a searchable list of existing agents rather than typed from
                memory — an ID typo here produces an agent that cannot deploy
                and a grant list nobody can read. */}
          <div className="flex items-stretch gap-2">
            <AgentPicker
              value=""
              onChange={addAgent}
              placeholder={t("secrets.grantAddAgent", "Add an agent")}
            />
          </div>

          {agents.length > 0 && (
            <div
              className="flex flex-wrap gap-1.5"
              data-testid="grant-agent-list"
            >
              {agents.map((agentId) => (
                <Badge
                  key={agentId}
                  variant="secondary"
                  className="gap-1 text-[11px]"
                  data-testid={`grant-agent-${agentId}`}
                >
                  <Bot className="h-3 w-3" aria-hidden="true" />
                  <span>{label(agentId)}</span>
                  <button
                    type="button"
                    onClick={() => removeAgent(agentId)}
                    className="rounded hover:text-destructive"
                    aria-label={t("common.removeItem", {
                      item: label(agentId),
                      defaultValue: "Remove {{item}}",
                    })}
                    data-testid={`grant-agent-remove-${agentId}`}
                  >
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}
            </div>
          )}

          {emptyList && (
            <p
              role="alert"
              data-testid="grant-empty-error"
              className="flex items-start gap-1 text-[11px] text-destructive"
            >
              <AlertTriangle
                className="mt-0.5 h-3 w-3 shrink-0"
                aria-hidden="true"
              />
              <span>
                {t(
                  "secrets.grantEmptyError",
                  "Add at least one agent, or choose “All agents”. An empty list is not allowed.",
                )}
              </span>
            </p>
          )}
        </div>
      )}

      {/* The description rides along because it is the only other field on the
            secret that can be edited without its value. */}
      <div className="space-y-1.5">
        <label
          htmlFor="grant-description"
          className="text-xs font-medium text-foreground"
        >
          {t("secrets.descriptionLabel", "Description (optional)")}
        </label>
        <Input
          id="grant-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t(
            "secrets.descriptionPlaceholder",
            "e.g. OpenAI API key for production",
          )}
          data-testid="grant-description-input"
        />
      </div>

      {/* Always mounted: a live region that appears together with its text is
          not reliably announced. Only the content comes and goes. */}
      <p
        role="status"
        aria-live="polite"
        className="flex items-center gap-2 text-xs text-muted-foreground empty:hidden"
      >
        {impactPending && (
          <span
            data-testid="grant-checking-impact"
            className="flex items-center gap-2"
          >
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
            {t("secrets.grantCheckingImpact", "Checking deployed agents…")}
          </span>
        )}
      </p>

      {needsAcknowledgement && (
        <div
          data-testid="grant-losing-access-warning"
          className="space-y-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2"
        >
          <p
            role="alert"
            className="flex items-start gap-2 text-xs font-medium text-warning"
          >
            <AlertTriangle
              className="mt-0.5 h-4 w-4 shrink-0"
              aria-hidden="true"
            />
            <span>
              {/* Deliberately not an i18next plural: a count in parentheses
                    reads correctly in every locale without a per-language
                    plural-category matrix for one warning line. */}
              {impactFailed
                ? t(
                    "secrets.grantImpactFailed",
                    "Could not check which deployed agents use this secret, so any of them may lose access.",
                  )
                : t("secrets.grantLosingAccessTitle", {
                    count: losingAccess.length,
                    defaultValue:
                      "Deployed agents that use this secret and would lose access ({{count}})",
                  })}
            </span>
          </p>
          <ul className="space-y-1 ps-6 text-xs text-warning">
            {losingAccess.map((agent) => (
              <li
                key={`${agent.environment}-${agent.agentId}-${agent.agentVersion}`}
                data-testid={`grant-losing-${agent.agentId}`}
              >
                {label(agent.agentId)}
                <span className="ms-1 font-mono text-[10px] opacity-80">
                  {agent.environment}
                  {agent.agentVersion !== null
                    ? ` · v${agent.agentVersion}`
                    : ""}
                </span>
              </li>
            ))}
          </ul>
          <p className="ps-6 text-xs text-warning">
            {t(
              "secrets.grantLosingAccessBody",
              "They keep running for now — the grant is checked when an agent is deployed, not when a secret is used — but their next deployment will be refused.",
            )}
          </p>
          <label className="flex items-start gap-2 ps-6 text-xs text-warning">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) =>
                setAcknowledgedFor(e.target.checked ? impactKey : null)
              }
              data-testid="grant-acknowledge"
              className="mt-0.5"
            />
            <span>
              {impactFailed
                ? t(
                    "secrets.grantAcknowledgeUnchecked",
                    "Save anyway without knowing which deployed agents this affects.",
                  )
                : t(
                    "secrets.grantAcknowledge",
                    "I understand these agents will not deploy again until they are added back or their configuration stops using this secret.",
                  )}
            </span>
          </label>
        </div>
      )}

      <div className="flex justify-end gap-2 pt-1">
        <Button variant="ghost" onClick={onClose} data-testid="grant-cancel">
          {t("common.cancel", "Cancel")}
        </Button>
        <Button
          variant="primary"
          onClick={handleSave}
          disabled={!canSave}
          data-testid="grant-save"
        >
          {updateMut.isPending && (
            <Loader2 className="me-2 h-4 w-4 animate-spin" />
          )}
          {updateMut.isPending
            ? t("common.saving", "Saving...")
            : t("common.save", "Save")}
        </Button>
      </div>
    </div>
  );
}
