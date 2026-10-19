import { useId, type Dispatch, type SetStateAction } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Globe, Server } from "lucide-react";
import { ChipInput } from "@/components/shared/chip-input";
import {
  allowedHostsMissing,
  validateAllowedHost,
  type AllowedHostProblem,
  type AllowedHostsDraft,
} from "@/lib/api/secrets";

interface AllowedHostsFieldProps {
  draft: AllowedHostsDraft;
  /**
   * A state setter, not a value callback: `ChipInput` reports a commit as three
   * calls in a row (values, pending, problem), and each must build on the one
   * before rather than on the draft this render was given.
   */
  onChange: Dispatch<SetStateAction<AllowedHostsDraft>>;
  /** Prefix for every `data-testid` inside, e.g. `new-secret-hosts`. */
  testId: string;
}

/**
 * Where a NEW secret may be sent — the `allowedHosts` a create must carry.
 *
 * Under `eddi.vault.grant-enforcement=enforce` the backend refuses a create
 * that leaves the destination to a default, exactly as it does for the agent
 * grant: either a list of hosts, or an explicit "any host" (`["*"]`). So this is
 * a choice between the two, not an optional list, and an empty list is
 * reported here rather than by the save. The host chips, the per-entry check
 * and the wording are the grant dialog's (`EditGrantDialog`), so the two places
 * that edit this list read alike.
 */
export function AllowedHostsField({ draft, onChange, testId }: AllowedHostsFieldProps) {
  const { t } = useTranslation();
  const errorId = useId();
  const missingId = useId();
  const missing = allowedHostsMissing(draft);

  const setAnyHost = (anyHost: boolean) => onChange((d) => ({ ...d, anyHost, problem: null }));

  return (
    <div className="space-y-1.5" data-testid={testId}>
      <span className="block text-xs font-medium text-muted-foreground">
        {t("secrets.allowedHosts", "Allowed destinations")}
      </span>
      {/* Two buttons rather than a checkbox: "any host" is not a modifier of the
          list, it replaces it — the same shape as the agent grant. */}
      <div
        role="radiogroup"
        aria-label={t("secrets.allowedHosts", "Allowed destinations")}
        className="grid grid-cols-2 gap-2"
        onKeyDown={(e) => {
          const target =
            e.key === "Home"
              ? false
              : e.key === "End"
                ? true
                : ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)
                  ? !draft.anyHost
                  : null;
          if (target !== null) {
            e.preventDefault();
            setAnyHost(target);
            e.currentTarget
              .querySelector<HTMLElement>(`[data-testid="${testId}-mode-${target ? "any" : "specific"}"]`)
              ?.focus();
          }
        }}
      >
        <button
          type="button"
          role="radio"
          aria-checked={!draft.anyHost}
          tabIndex={draft.anyHost ? -1 : 0}
          onClick={() => setAnyHost(false)}
          data-testid={`${testId}-mode-specific`}
          className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-start transition-colors ${
            !draft.anyHost ? "border-primary bg-primary/10" : "border-input hover:bg-secondary/50"
          }`}
        >
          <Server className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span className="text-xs font-medium">
            {t("secrets.allowedHostsOnlyThese", "Only these hosts")}
          </span>
        </button>
        <button
          type="button"
          role="radio"
          aria-checked={draft.anyHost}
          tabIndex={draft.anyHost ? 0 : -1}
          onClick={() => setAnyHost(true)}
          data-testid={`${testId}-mode-any`}
          className={`flex items-start gap-2 rounded-lg border px-3 py-2 text-start transition-colors ${
            draft.anyHost ? "border-primary bg-primary/10" : "border-input hover:bg-secondary/50"
          }`}
        >
          <Globe className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span className="text-xs font-medium">
            {t("secrets.allowedHostsAnyHost", "Any host")}
          </span>
        </button>
      </div>

      {draft.anyHost ? (
        <p className="text-xs text-muted-foreground" data-testid={`${testId}-any-hint`}>
          {t(
            "secrets.allowedHostsAny",
            "No restriction: an agent allowed to use this secret may send it to any host. Add hosts to refuse every other destination.",
          )}
        </p>
      ) : (
        <>
          <ChipInput
            values={draft.hosts}
            onChange={(next) => onChange((d) => ({ ...d, hosts: next.map((host) => host.toLowerCase()) }))}
            pending={draft.pending}
            onPendingChange={(pending) => onChange((d) => ({ ...d, pending, problem: null }))}
            validate={validateAllowedHost}
            onInvalid={(code) => onChange((d) => ({ ...d, problem: code as AllowedHostProblem | null }))}
            placeholder="api.openai.com"
            inputLabel={t("secrets.allowedHostsAdd", "Add an allowed host")}
            itemIcon={Server}
            invalid={draft.problem !== null || missing}
            errorId={draft.problem ? errorId : missing ? missingId : undefined}
            testId={`${testId}-input`}
            ltr
          />
          {draft.problem && (
            <p id={errorId} role="alert" data-testid={`${testId}-error`} className="text-[11px] text-destructive">
              {draft.problem === "scheme"
                ? t("secrets.allowedHostsScheme", "Enter the host only, without https:// or a path — e.g. api.openai.com or api.openai.com:443.")
                : t("secrets.allowedHostsShape", "Not a host or host:port. Wildcards are not supported.")}
            </p>
          )}
          {missing ? (
            <p id={missingId} data-testid={`${testId}-missing`} className="flex items-start gap-1 text-[11px] text-destructive">
              <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
              <span>
                {t(
                  "secrets.allowedHostsRequired",
                  "Add at least one host, or choose “Any host”. A new secret needs one or the other.",
                )}
              </span>
            </p>
          ) : (
            <p className="text-xs text-muted-foreground" data-testid={`${testId}-hint`}>
              {t(
                "secrets.allowedHostsHint",
                "A request, model, knowledge base or MCP/A2A server that would send this secret to any other host is refused. A host without a port matches every port.",
              )}
            </p>
          )}
        </>
      )}
    </div>
  );
}
