import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Loader2, ShieldAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { OperatorTrust } from "@/lib/operator/operator-trust";

interface OperatorUntrustedProps {
  trust: OperatorTrust;
  agentId: string;
  /** Run the checks again. */
  onRecheck: () => void;
  /**
   * Where to send the admin to deal with it. The operator page offers its own
   * Reconfigure button in the status panel beside this notice, so it passes none.
   */
  manageHref?: string;
  /** Smaller layout for the header drawer. */
  compact?: boolean;
}

/**
 * Shown INSTEAD of the operator chat when the agent `platform.operator` names
 * fails the Manager's checks (the descriptor marker, the verified approval gate).
 *
 * `platform.operator` can be written by an editor, so the id in it is a
 * suggestion until these checks agree. Chat, history and the conversation
 * restore all stay off while this is up: opening any of them would put a
 * conversation with an agent the Manager did not set up in front of an admin who
 * will approve what it proposes.
 */
export function OperatorUntrusted({ trust, agentId, onRecheck, manageHref, compact }: OperatorUntrustedProps) {
  const { t } = useTranslation();

  if (trust.state === "checking") {
    return (
      <div
        className="flex flex-1 items-center justify-center gap-2 p-6 text-sm text-muted-foreground"
        role="status"
        data-testid="operator-trust-checking"
      >
        <Loader2 className="h-4 w-4 animate-spin" />
        {t("operator.trust.checking", "Verifying the operator before opening chat…")}
      </div>
    );
  }

  return (
    <div
      className={
        compact
          ? "flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center"
          : "flex flex-1 flex-col items-center justify-center gap-3 rounded-md border border-destructive/40 bg-destructive/5 p-8 text-center"
      }
      role="alert"
      data-testid="operator-untrusted"
    >
      <ShieldAlert className="h-8 w-8 text-destructive" aria-hidden="true" />
      <p className="text-sm font-medium text-foreground">
        {t("operator.trust.title", "The Platform Operator could not be verified")}
      </p>
      <p className="max-w-md text-sm text-muted-foreground">
        {t(
          "operator.trust.body",
          "Chat is switched off because the agent registered as the operator ({{agentId}}) is not provably the one this Manager set up. Anyone who can edit platform settings can change which agent that is.",
          { agentId },
        )}
      </p>
      <ul className="max-w-md space-y-1 text-start text-xs text-muted-foreground" data-testid="operator-trust-reasons">
        {trust.reasons.includes("marker") && (
          <li data-testid="operator-trust-reason-marker">
            {t(
              "operator.trust.reasonMarker",
              "It does not carry the Platform Operator marker, so it may not be the agent the Manager provisioned.",
            )}
          </li>
        )}
        {trust.reasons.includes("gate") && (
          <li data-testid="operator-trust-reason-gate">
            {t("operator.trust.reasonGate", "Its approval gate could not be verified: {{detail}}", {
              detail: trust.gateDetail ?? "—",
            })}
          </li>
        )}
        {trust.reasons.includes("unreadable") && (
          <li data-testid="operator-trust-reason-unreadable">
            {t("operator.trust.reasonUnreadable", "The checks could not be completed: {{detail}}", {
              detail: trust.errorDetail ?? "—",
            })}
          </li>
        )}
      </ul>
      <div className="flex flex-wrap justify-center gap-2 pt-1">
        <Button size="sm" variant="outline" onClick={onRecheck} data-testid="operator-trust-recheck">
          {t("operator.trust.recheck", "Check again")}
        </Button>
        {manageHref && (
          <Button asChild size="sm">
            <Link to={manageHref} data-testid="operator-trust-manage">
              {t("operator.trust.manage", "Open the operator screen")}
            </Link>
          </Button>
        )}
      </div>
    </div>
  );
}
