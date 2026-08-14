import { gateLooksInstalled } from "@/lib/api/operator";
import type { Agent } from "@/lib/api/agents";

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
    | "agentCreatedWithBroadEndpoints"
    | "agentCreatedWithExternalTools"
    | "inlineCredential";
  /** Dotted path of the setting within the body, shown verbatim so the approver
   *  can find it in the JSON below. For `inlineCredential` — a string-level
   *  find, not a setting — this is the matched marker instead. */
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
 * `autoApproveOnTimeout` stays even though `agentCreatedWithoutGate` now
 * subsumes it for a create body: it is the load-bearing check for
 * `POST /groupstore/groups`, where `GroupHitlConfig.timeoutPolicy` is NOT
 * demoted the way an inherited agent-level one is. A create carrying it trips
 * both, which is noisy in the right direction.
 *
 * The `agentCreated*` checks exist only for a **create** body (`setup_agent` /
 * `create_api_agent` — recognised by {@link isAgentCreationBody}, checked
 * before any of them does anything else) and deliberately have no counterpart
 * for an *update*. "This new document has no gate" is answerable by reading the
 * document alone; "this update just removed a gate the document used to have"
 * is a diff question this module cannot answer — it sees one resolved body, never a prior version — which is exactly
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
      // Delegates to the SAME judgement the operator applies to its own agent
      // (`gateLooksInstalled`), rather than the "is requireApproval non-empty"
      // test this used to carry. That test passed three bodies that create a
      // fully ungated agent: `exempt: ["*"]` (the backend tests exempt FIRST and
      // short-circuits, so it beats any requireApproval), a decoy
      // `requireApproval: ["http.get:*"]` that gates only reads, and
      // `toolApprovals.timeoutPolicy: "AUTO_APPROVE"` — the tool-level policy
      // the backend honours verbatim, as opposed to the inherited one it
      // demotes. Holding what we create to a weaker standard than what we run
      // as was the actual defect; there is now one definition of "has a real
      // gate" and both callers use it.
      // Shape-normalised before delegating. `gateLooksInstalled` was written
      // for a typed backend response; this body is arbitrary LLM-composed JSON,
      // and handing it straight over made a malformed shape THROW during render
      // — `requireApproval: "http.post:*"` (a string, so `.some` is not a
      // function), `rules: [{timeoutPolicy}]` with no `match`, and four more.
      // The nearest boundary is app-level, so the whole page was replaced by
      // the error fallback and the admin could neither approve NOR reject,
      // leaving Slack/MCP — where this guard does not run — as the only way to
      // resolve that pause. A check whose job is to shout "this agent has no
      // gate" must never be the thing that takes the surface down, and
      // `rules` without `match` needs no adversary: it is a plausible honest
      // emission.
      return !gateLooksInstalled({ hitlConfig: normaliseHitlConfig(at(body, "hitlConfig")) } as Agent).ok;
    },
  },
  {
    id: "agentCreatedWithExternalTools",
    path: "mcpServerUrls",
    matches: (value, body) => {
      if (!isAgentCreationBody(body)) return false;
      // A sibling of the `endpoints` filter and arguably broader: every tool an
      // external MCP server advertises is attached to the created agent, and
      // unlike `endpoints` there is no per-verb filter at all — the server
      // decides what it offers, and it can change what it offers later. Both
      // create paths accept it.
      return typeof value === "string" && value.trim() !== "";
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

/**
 * Whether a resolved body is shaped like a setup_agent / create_api_agent
 * request — the pair of required fields both share.
 *
 * `name` is accepted alongside `agentName` because the backend record declares
 * {@code @JsonAlias("name")} on that component, so `{"name": …, "systemPrompt":
 * …}` is a fully valid create body — and it is the shape this codebase's own
 * `SetupAgentRequest` TS interface sends. Requiring only the canonical spelling
 * meant an accepted alias silenced every create-shape check below, which is the
 * "alternate JSON shape the backend accepts for the same field" evasion in its
 * most literal form.
 */
/** Keep only the string entries of a value that should be a string array. */
function stringsOnly(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.filter((entry): entry is string => typeof entry === "string");
}

/**
 * Coerce an arbitrary parsed-JSON `hitlConfig` into the shape
 * `gateLooksInstalled` expects, dropping anything of the wrong type.
 *
 * Dropping rather than repairing is the safe direction here: a malformed
 * `requireApproval` becomes an EMPTY list, which reads as "no gate" and raises
 * the warning — the cautious answer for a body nobody can parse confidently.
 * The alternative, passing it through, throws and takes the page down.
 */
function normaliseHitlConfig(raw: unknown): Record<string, unknown> | undefined {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return undefined;
  const source = raw as Record<string, unknown>;
  const rawTool = source.toolApprovals;
  if (typeof rawTool !== "object" || rawTool === null || Array.isArray(rawTool)) {
    return { timeoutPolicy: source.timeoutPolicy };
  }
  const tool = rawTool as Record<string, unknown>;
  const rules = Array.isArray(tool.rules)
    ? tool.rules
        .filter((rule): rule is Record<string, unknown> => typeof rule === "object" && rule !== null && !Array.isArray(rule))
        // `match` is dereferenced with .startsWith, so a rule without one is
        // dropped rather than allowed to throw.
        .filter((rule) => typeof rule.match === "string")
    : undefined;
  return {
    timeoutPolicy: source.timeoutPolicy,
    toolApprovals: {
      requireApproval: stringsOnly(tool.requireApproval) ?? [],
      exempt: stringsOnly(tool.exempt) ?? [],
      timeoutPolicy: tool.timeoutPolicy,
      rules,
    },
  };
}

function isAgentCreationBody(body: unknown): boolean {
  const named = typeof at(body, "agentName") === "string" || typeof at(body, "name") === "string";
  return named && typeof at(body, "systemPrompt") === "string";
}

/**
 * A credential-shaped literal embedded in the request body, where a
 * `${vault:…}` reference belongs.
 *
 * Two signals, in order of confidence:
 *
 * 1. **The backend's own redaction marker.** The body arrives already filtered
 *    through `SecretRedactionFilter`, which replaces a recognised secret with
 *    `<REDACTED>` while leaving a `${vault:…}` reference legible — a pointer is
 *    not a secret, and an approver needs to see WHICH credential is in play. So
 *    a `<REDACTED>` marker means the backend itself concluded a secret LITERAL
 *    was embedded in the request (the operator fabricating an `sk-ant-…` key
 *    into a create-agent call is the observed case). This is evidence, not
 *    heuristics. The `${vault:<REDACTED>}` exclusion below is kept for backends
 *    predating that change, which masked the reference too.
 * 2. **Raw credential shapes** (`sk-…` keys, `Bearer` tokens), for a backend
 *    old enough that its filter missed the literal entirely.
 *
 * Returns the matched marker (for the flag's `path` slot) or null. Runs on the
 * raw STRING, before any JSON parsing — a credential does not become harmless
 * by arriving in a form post.
 */
function findInlineCredential(body: string): string | null {
  if (/(?<!\$\{vault:)<REDACTED>/.test(body)) {
    // The marker itself, verbatim and nothing around it. An earlier version
    // prefixed it with the 8 preceding characters "so the approver can see what
    // kind of secret it was" — those characters come from the body, so on a
    // near-miss redaction they are credential material, and this string is
    // rendered in the warning. A label that cannot leak beats a label that is
    // usually safe.
    return "<REDACTED>";
  }
  // Fixed labels, never a slice of the match: these branches see the credential
  // UNREDACTED (that is the point — an older backend missed it), so copying any
  // of it into a string the UI renders would defeat the check's own purpose.
  if (/sk-[A-Za-z0-9_-]{20,}/.test(body)) {
    return "sk-…";
  }
  if (/Bearer\s+[A-Za-z0-9\-_.+/=]{20,}/.test(body)) {
    return "Bearer …";
  }
  return null;
}

/**
 * Escalating settings in a resolved request body, in display order.
 *
 * The setting CHECKS return `[]` for a body that is absent, not JSON, or not a
 * JSON object — silently, because a non-JSON body is ordinary (a form post,
 * plain text) and not something to warn about. The inline-credential scan runs
 * regardless of shape: it is a string-level find.
 */
export function detectEscalationFlags(body: string | null | undefined): EscalationFlag[] {
  if (!body) return [];

  const flags: EscalationFlag[] = [];
  const credential = findInlineCredential(body);
  if (credential) {
    flags.push({ id: "inlineCredential", path: credential });
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return flags;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return flags;

  return [
    ...flags,
    ...CHECKS.filter((check) => check.matches(at(parsed, check.path), parsed)).map((check) => ({
      id: check.id,
      path: check.path,
    })),
  ];
}
