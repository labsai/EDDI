/**
 * Settings inside a gated request body that grant capability beyond the request
 * itself.
 *
 * A gated write is reviewed by reading its resolved body. That works when the
 * consequential part of the body is the part an approver is looking at — a
 * renamed descriptor, a deployed agent id. It works badly for a config document,
 * where one boolean among fifty lines decides whether the thing being created
 * can go on to create further things. An approver scanning JSON will miss it,
 * and "they could have seen it" is the rubber-stamping failure mode the whole
 * per-call approval design exists to avoid.
 *
 * So the known escalating settings are detected and surfaced separately, above
 * the body, in the approver's own words.
 *
 * **This is an attention aid, not a security control.** It is a fixed list of
 * known keys: a setting nobody added here is not flagged, and a body that is not
 * JSON is not inspected at all. Nothing is blocked on the result — what stops an
 * ungated write is the allow-list and the gate, not this. It exists so a human
 * deciding in seconds sees the one line that matters.
 */

/** One escalating setting found in a request body. */
export interface EscalationFlag {
  /** Stable id — also the i18n key suffix under `operator.approval.escalation`. */
  id: "dynamicAgentCreation" | "dynamicAgentRecruitment" | "autoApproveOnTimeout";
  /** Dotted path of the setting within the body, shown verbatim so the approver
   *  can find it in the JSON below. */
  path: string;
}

/** Read a dotted path out of a parsed body, or `undefined`. */
function at(root: unknown, path: string): unknown {
  let node: unknown = root;
  for (const segment of path.split(".")) {
    if (typeof node !== "object" || node === null) return undefined;
    node = (node as Record<string, unknown>)[segment];
  }
  return node;
}

/**
 * Checks, in the order they are shown.
 *
 * `dynamicAgents.enabled` is required alongside the specific permission for the
 * first two: the permission booleans default to set values in the backend model
 * (`allowDelegation` is `true` by default), so flagging one while the feature is
 * switched off would cry wolf on an ordinary group and train approvers to skim
 * past the warning — which costs more than it buys.
 */
const CHECKS: readonly {
  id: EscalationFlag["id"];
  path: string;
  matches: (value: unknown, body: unknown) => boolean;
}[] = [
  {
    id: "dynamicAgentCreation",
    path: "dynamicAgents.allowCreation",
    matches: (value, body) => value === true && at(body, "dynamicAgents.enabled") === true,
  },
  {
    id: "dynamicAgentRecruitment",
    path: "dynamicAgents.allowRecruitment",
    matches: (value, body) => value === true && at(body, "dynamicAgents.enabled") === true,
  },
  {
    id: "autoApproveOnTimeout",
    path: "hitlConfig.timeoutPolicy",
    matches: (value) => value === "AUTO_APPROVE",
  },
];

/**
 * Escalating settings in a resolved request body, in display order.
 *
 * Returns `[]` for a body that is absent, not JSON, or not a JSON object —
 * silently, because a non-JSON body is ordinary (a form post, plain text) and
 * not something to warn about.
 */
export function detectEscalationFlags(body: string | null | undefined): EscalationFlag[] {
  if (!body) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return [];
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return [];

  return CHECKS.filter((check) => check.matches(at(parsed, check.path), parsed)).map((check) => ({
    id: check.id,
    path: check.path,
  }));
}
