/**
 * May the Manager open a chat with the agent `platform.operator` names?
 *
 * `platform.operator` is a global variable that `eddi-editor` can write, and the
 * Manager takes the operator's agent id from it. Without a check, an editor can
 * repoint it at an agent they authored, and the admin then types questions about
 * the deployment into a prompt the editor controls, and approves the tool calls
 * that agent proposes under the admin's identity.
 *
 * The check is two facts the Manager itself established when it provisioned the
 * operator, both read back from the agent store rather than from the variable:
 *
 * - **The marker.** Provisioning stamps `[eddi-platform-operator]` on the agent's
 *   descriptor (see `operator-marker.ts`). An agent without it was not set up by
 *   this screen.
 * - **The gate.** `verifyGateInstalled` reads every version of the agent document
 *   and refuses unless the per-tool-call approval gate is installed and sane.
 *
 * Neither is a signature: an editor can write a descriptor description too, and
 * the backend should reserve `platform.*` for admins (the server-side half of
 * this finding). What this does is stop the Manager from walking an admin into
 * an agent that fails the checks the Manager's own provisioning always passes,
 * and it fails closed: a check that could not be completed is not a pass.
 */

export type OperatorTrustReason = "marker" | "gate" | "unreadable";

export interface OperatorTrust {
  /**
   * - `idle`: no live operator is configured, so there is nothing to check.
   * - `checking`: at least one check has not answered yet.
   * - `trusted`: both checks passed.
   * - `untrusted`: a check failed or could not be completed; chat stays off.
   */
  state: "idle" | "checking" | "trusted" | "untrusted";
  reasons: OperatorTrustReason[];
  /** The gate verifier's own explanation, when the gate is why it is untrusted. */
  gateDetail?: string;
  /** What went wrong when a check could not be completed. */
  errorDetail?: string;
}

/** One check's outcome, flattened from a query so this stays a pure function. */
export type CheckOutcome<T> =
  | { status: "pending" }
  | { status: "error"; error: unknown }
  | { status: "success"; value: T };

export interface TrustInputs {
  /** Whether there is an enabled operator with an agent id to check at all. */
  live: boolean;
  marker: CheckOutcome<boolean>;
  gate: CheckOutcome<{ verified: boolean; reason?: string }>;
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function assessOperatorTrust({ live, marker, gate }: TrustInputs): OperatorTrust {
  if (!live) return { state: "idle", reasons: [] };

  const reasons: OperatorTrustReason[] = [];
  let gateDetail: string | undefined;
  let errorDetail: string | undefined;
  let pending = false;

  if (marker.status === "pending") pending = true;
  else if (marker.status === "error") {
    reasons.push("unreadable");
    errorDetail = describeError(marker.error);
  } else if (!marker.value) reasons.push("marker");

  if (gate.status === "pending") pending = true;
  else if (gate.status === "error") {
    if (!reasons.includes("unreadable")) reasons.push("unreadable");
    errorDetail ??= describeError(gate.error);
  } else if (!gate.value.verified) {
    reasons.push("gate");
    gateDetail = gate.value.reason;
  }

  // A failure is final the moment one check reports it; waiting for the other
  // would only delay saying so.
  if (reasons.length > 0) return { state: "untrusted", reasons, gateDetail, errorDetail };
  if (pending) return { state: "checking", reasons: [] };
  return { state: "trusted", reasons: [] };
}
