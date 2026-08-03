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
  id:
    | "dynamicAgentCreation"
    | "dynamicAgentRecruitment"
    | "autoApproveOnTimeout"
    | "agentCreatedWithoutGate"
    | "agentCreatedWithBroadEndpoints";
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
 *
 * The last two exist only for a **create** body (`setup_agent` /
 * `create_api_agent` — distinguished by the `agentName` + `systemPrompt` pair
 * every such body carries, checked before either does anything else) and
 * deliberately have no counterpart for an *update*. "This new document has no
 * gate" is answerable by reading the document alone; "this update just removed
 * a gate the document used to have" is a diff question this module cannot
 * answer — it sees one resolved body, never a prior version — which is exactly
 * why `PUT /agentstore/agents/{id}` and `PUT /groupstore/groups/{id}` stay out
 * of `WRITE_ENDPOINTS` rather than being flagged here instead. See that file's
 * doc comment.
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
  {
    id: "agentCreatedWithoutGate",
    path: "hitlConfig",
    matches: (_value, body) => {
      if (!isAgentCreationBody(body)) return false;
      const requireApproval = at(body, "hitlConfig.toolApprovals.requireApproval");
      return !Array.isArray(requireApproval) || requireApproval.length === 0;
    },
  },
  {
    id: "agentCreatedWithBroadEndpoints",
    path: "endpoints",
    matches: (value, body) => {
      // Only create_api_agent bodies carry endpoints at all — openApiSpec is
      // the field that shape adds on top of the common agentName+systemPrompt
      // pair. A setup_agent body has neither this field nor this risk.
      if (typeof at(body, "openApiSpec") !== "string") return false;
      // Omitted means "every non-deprecated endpoint" per the tool's own
      // description — broader than any explicit list, so it is flagged too.
      if (typeof value !== "string" || value.trim() === "") return true;
      return value.split(",").some((entry) => !entry.trim().startsWith("GET "));
    },
  },
];

/** Whether a resolved body is shaped like a setup_agent / create_api_agent
 *  request — the pair of required fields both share. */
function isAgentCreationBody(body: unknown): boolean {
  return typeof at(body, "agentName") === "string" && typeof at(body, "systemPrompt") === "string";
}

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
