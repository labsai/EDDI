import { describe, it, expect } from "vitest";
import { detectEscalationFlags } from "../escalation-flags";

/** A minimal group config body, with dynamic agents off. */
function groupBody(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    name: "Billing review board",
    members: [{ agentId: "a1" }, { agentId: "a2" }],
    maxRounds: 2,
    ...overrides,
  });
}

/** A minimal setup_agent body, gated by default. */
function setupAgentBody(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    agentName: "Refund helper",
    systemPrompt: "You help customers request refunds.",
    hitlConfig: { toolApprovals: { requireApproval: ["http.post:*"], exempt: ["http.get:*"] } },
    ...overrides,
  });
}

/** A minimal create_api_agent body, gated and endpoint-scoped by default. */
function createApiAgentBody(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    agentName: "Ticketing bridge",
    systemPrompt: "You file and look up support tickets.",
    openApiSpec: "https://tickets.example.com/openapi.json",
    endpoints: "GET /tickets,GET /tickets/{id}",
    hitlConfig: { toolApprovals: { requireApproval: ["http.post:*"], exempt: ["http.get:*"] } },
    ...overrides,
  });
}

describe("detectEscalationFlags", () => {
  it("finds nothing in an ordinary group create", () => {
    expect(detectEscalationFlags(groupBody())).toEqual([]);
  });

  it("flags a group that may create agents at runtime", () => {
    // The one this exists for: an approved group create that can go on to
    // create agents is an escape from the endpoint allow-list, and it is one
    // boolean deep in a config document nobody reads to the bottom of.
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowCreation: true } }),
    );
    expect(flags).toEqual([{ id: "dynamicAgentCreation", path: "dynamicAgents.allowCreation" }]);
  });

  it("flags a group that may recruit other agents", () => {
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowRecruitment: true } }),
    );
    expect(flags).toEqual([
      { id: "dynamicAgentRecruitment", path: "dynamicAgents.allowRecruitment" },
    ]);
  });

  it("reports both permissions when both are set", () => {
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowCreation: true, allowRecruitment: true } }),
    );
    expect(flags.map((f) => f.id)).toEqual(["dynamicAgentCreation", "dynamicAgentRecruitment"]);
  });

  it("does not cry wolf when the feature is switched off", () => {
    // The permission booleans carry non-false defaults in the backend model, so
    // flagging one while `enabled` is false would fire on ordinary groups and
    // train approvers to skim past the warning.
    expect(
      detectEscalationFlags(groupBody({ dynamicAgents: { enabled: false, allowCreation: true } })),
    ).toEqual([]);
  });

  it("flags a config that approves its own requests on timeout", () => {
    expect(
      detectEscalationFlags(groupBody({ hitlConfig: { timeoutPolicy: "AUTO_APPROVE" } })),
    ).toEqual([{ id: "autoApproveOnTimeout", path: "hitlConfig.timeoutPolicy" }]);
  });

  it("leaves a non-auto-approve timeout policy alone", () => {
    expect(
      detectEscalationFlags(groupBody({ hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } })),
    ).toEqual([]);
  });

  it("returns nothing for an absent or empty body", () => {
    expect(detectEscalationFlags(null)).toEqual([]);
    expect(detectEscalationFlags(undefined)).toEqual([]);
    expect(detectEscalationFlags("")).toEqual([]);
  });

  it("returns nothing for a body that is not JSON, rather than throwing", () => {
    // A form post or plain-text body is ordinary, not something to warn about.
    expect(detectEscalationFlags("name=x&value=y")).toEqual([]);
  });

  it("returns nothing for JSON that is not an object", () => {
    expect(detectEscalationFlags("[1,2,3]")).toEqual([]);
    expect(detectEscalationFlags('"a string"')).toEqual([]);
    expect(detectEscalationFlags("null")).toEqual([]);
  });

  it("tolerates a wrongly-typed nested value instead of throwing", () => {
    // The body is model output; nothing guarantees its shape.
    expect(detectEscalationFlags(groupBody({ dynamicAgents: "yes" }))).toEqual([]);
    expect(detectEscalationFlags(groupBody({ dynamicAgents: null }))).toEqual([]);
  });

  it("requires a real boolean, not a truthy string", () => {
    // A permissive `!!value` check would flag the string "false".
    expect(
      detectEscalationFlags(
        groupBody({ dynamicAgents: { enabled: "true", allowCreation: "false" } }),
      ),
    ).toEqual([]);
  });

  describe("agentCreatedWithoutGate — evasions that used to pass", () => {
    it("flags an exempt list broad enough to swallow every gated write", () => {
      // The backend tests `exempt` FIRST and short-circuits to allowed, so this
      // beats any requireApproval next to it. The old non-empty-list check
      // passed it.
      const flags = detectEscalationFlags(
        setupAgentBody({
          hitlConfig: { toolApprovals: { requireApproval: ["http.post:*"], exempt: ["*"] } },
        }),
      );
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithoutGate");
    });

    it("flags a decoy requireApproval that only gates reads", () => {
      const flags = detectEscalationFlags(
        setupAgentBody({ hitlConfig: { toolApprovals: { requireApproval: ["http.get:*"] } } }),
      );
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithoutGate");
    });

    it("flags a tool-level AUTO_APPROVE — the one the backend honours verbatim", () => {
      // Distinct from hitlConfig.timeoutPolicy, which the backend DEMOTES for
      // tool pauses. This one auto-executes a gated call with nobody watching.
      const flags = detectEscalationFlags(
        setupAgentBody({
          hitlConfig: {
            toolApprovals: { requireApproval: ["http.post:*"], timeoutPolicy: "AUTO_APPROVE" },
          },
        }),
      );
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithoutGate");
    });

    it("flags a per-rule AUTO_APPROVE aimed at a write", () => {
      const flags = detectEscalationFlags(
        setupAgentBody({
          hitlConfig: {
            toolApprovals: {
              requireApproval: ["http.post:*"],
              rules: [{ match: "http.post:/agentstore/agents", timeoutPolicy: "AUTO_APPROVE" }],
            },
          },
        }),
      );
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithoutGate");
    });

    it("still recognises a create body that uses the accepted 'name' alias", () => {
      // The backend record carries @JsonAlias("name"), so this is a fully valid
      // create body — and requiring only `agentName` silenced every check below.
      const body = JSON.parse(setupAgentBody());
      body.name = body.agentName;
      delete body.agentName;
      delete body.hitlConfig;
      expect(detectEscalationFlags(JSON.stringify(body)).map((f) => f.id)).toContain(
        "agentCreatedWithoutGate",
      );
    });
  });

  describe("malformed hitlConfig must not crash the approval surface", () => {
    // detectEscalationFlags runs during render, and the nearest boundary is
    // app-level — so a throw here replaced the whole page with the error
    // fallback, leaving the admin unable to approve OR reject and pushing the
    // decision to Slack/MCP, where the self-guard does not run. The body is
    // arbitrary LLM-composed JSON; every one of these is a plausible emission.
    it.each([
      ["requireApproval as a string", { toolApprovals: { requireApproval: "http.post:*" } }],
      ["requireApproval of numbers", { toolApprovals: { requireApproval: [1, 2] } }],
      ["requireApproval containing null", { toolApprovals: { requireApproval: [null] } }],
      ["exempt as a string", { toolApprovals: { requireApproval: ["http.post:*"], exempt: "http.get:*" } }],
      ["a rule with no match", { toolApprovals: { requireApproval: ["http.post:*"], rules: [{ timeoutPolicy: "AUTO_APPROVE" }] }}],
      ["rules as a string", { toolApprovals: { requireApproval: ["http.post:*"], rules: "none" } }],
      ["toolApprovals as an array", { toolApprovals: [] }],
      ["hitlConfig as a string", "nope"],
      ["hitlConfig as an array", []],
    ])("does not throw on %s", (_label, hitlConfig) => {
      expect(() => detectEscalationFlags(setupAgentBody({ hitlConfig }))).not.toThrow();
    });

    it("treats an unparseable gate as NO gate, not as a valid one", () => {
      // The safe direction: a shape nobody can read confidently should raise
      // the warning, never silently certify the agent as gated.
      const flags = detectEscalationFlags(
        setupAgentBody({ hitlConfig: { toolApprovals: { requireApproval: "http.post:*" } } }),
      );
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithoutGate");
    });

    it("still accepts a well-formed gate after normalisation", () => {
      // The mirror: normalising must not break the valid case it passes through.
      expect(detectEscalationFlags(setupAgentBody()).map((f) => f.id)).not.toContain("agentCreatedWithoutGate");
    });
  });

  describe("agentCreatedWithExternalTools", () => {
    it("flags an MCP server URL, which attaches a whole external tool surface", () => {
      const flags = detectEscalationFlags(setupAgentBody({ mcpServerUrls: "https://tools.example/mcp" }));
      expect(flags.map((f) => f.id)).toContain("agentCreatedWithExternalTools");
    });

    it("stays silent when the field is absent or blank", () => {
      expect(detectEscalationFlags(setupAgentBody()).map((f) => f.id)).not.toContain(
        "agentCreatedWithExternalTools",
      );
      expect(
        detectEscalationFlags(setupAgentBody({ mcpServerUrls: "  " })).map((f) => f.id),
      ).not.toContain("agentCreatedWithExternalTools");
    });

    it("does not fire on a group create that happens to carry the field name", () => {
      expect(
        detectEscalationFlags(groupBody({ mcpServerUrls: "https://tools.example/mcp" })),
      ).toEqual([]);
    });
  });

  describe("agentCreatedWithoutGate", () => {
    it("finds nothing when a setup_agent create carries a real gate", () => {
      expect(detectEscalationFlags(setupAgentBody())).toEqual([]);
    });

    it("flags a setup_agent create with no hitlConfig at all", () => {
      const body = JSON.parse(setupAgentBody());
      delete body.hitlConfig;
      const flags = detectEscalationFlags(JSON.stringify(body));
      expect(flags).toEqual([{ id: "agentCreatedWithoutGate", path: "hitlConfig" }]);
    });

    it("flags a create whose toolApprovals has no requireApproval entries", () => {
      expect(
        detectEscalationFlags(
          setupAgentBody({ hitlConfig: { toolApprovals: { requireApproval: [], exempt: ["http.get:*"] } } }),
        ),
      ).toEqual([{ id: "agentCreatedWithoutGate", path: "hitlConfig" }]);
    });

    it("flags a create whose hitlConfig has no toolApprovals block", () => {
      expect(detectEscalationFlags(setupAgentBody({ hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } }))).toEqual([
        { id: "agentCreatedWithoutGate", path: "hitlConfig" },
      ]);
    });

    it("finds nothing when a create_api_agent create carries a real gate", () => {
      expect(detectEscalationFlags(createApiAgentBody())).toEqual([]);
    });

    it("flags a create_api_agent create with no gate the same way", () => {
      const body = JSON.parse(createApiAgentBody());
      delete body.hitlConfig;
      expect(detectEscalationFlags(JSON.stringify(body)).map((f) => f.id)).toContain(
        "agentCreatedWithoutGate",
      );
    });

    it("does not cry wolf on an ordinary group create, which has no agentName/systemPrompt", () => {
      // A group body has neither required field, so this check must stay
      // silent rather than misreading unrelated fields as a missing gate.
      expect(detectEscalationFlags(groupBody())).toEqual([]);
    });

    it("does not fire on a body missing only one of the two required fields", () => {
      expect(detectEscalationFlags(JSON.stringify({ agentName: "x" }))).toEqual([]);
      expect(detectEscalationFlags(JSON.stringify({ systemPrompt: "x" }))).toEqual([]);
    });
  });

  describe("agentCreatedWithBroadEndpoints", () => {
    it("finds nothing when create_api_agent scopes endpoints to reads", () => {
      expect(detectEscalationFlags(createApiAgentBody())).toEqual([]);
    });

    it("flags an endpoints filter that includes a write verb", () => {
      const flags = detectEscalationFlags(
        createApiAgentBody({ endpoints: "GET /tickets,DELETE /tickets/{id}" }),
      );
      expect(flags).toEqual([{ id: "agentCreatedWithBroadEndpoints", path: "endpoints" }]);
    });

    it("flags an omitted endpoints filter — broader than any explicit list", () => {
      const body = JSON.parse(createApiAgentBody());
      delete body.endpoints;
      const flags = detectEscalationFlags(JSON.stringify(body));
      expect(flags).toEqual([{ id: "agentCreatedWithBroadEndpoints", path: "endpoints" }]);
    });

    it("flags a blank endpoints filter the same way as an omitted one", () => {
      expect(
        detectEscalationFlags(createApiAgentBody({ endpoints: "   " })).map((f) => f.id),
      ).toContain("agentCreatedWithBroadEndpoints");
    });

    it("does not fire on a setup_agent body, which has no endpoints field", () => {
      // openApiSpec is what distinguishes create_api_agent; a setup_agent body
      // has neither it nor the risk this check exists for.
      expect(detectEscalationFlags(setupAgentBody())).toEqual([]);
    });

    it("does not cry wolf on an ordinary group create", () => {
      expect(detectEscalationFlags(groupBody({ endpoints: "not a real field here" }))).toEqual([]);
    });
  });

  it("reports an ungated, endpoint-unbounded create_api_agent as both flags", () => {
    const body = JSON.parse(createApiAgentBody());
    delete body.hitlConfig;
    delete body.endpoints;
    const flags = detectEscalationFlags(JSON.stringify(body));
    expect(flags.map((f) => f.id).sort()).toEqual(
      ["agentCreatedWithBroadEndpoints", "agentCreatedWithoutGate"].sort(),
    );
  });
});

/**
 * The inline-credential flag: a credential-shaped literal where a ${vault:…}
 * reference belongs. Signal 1 is the backend's own redaction marker (evidence,
 * not heuristics); signal 2 is raw credential shapes for older backends.
 */
describe("inlineCredential", () => {
  it("flags the backend's redaction marker outside a vault reference", () => {
    const body = JSON.stringify({ llm: { provider: "anthropic", apiKey: "sk-ant-<REDACTED>" } });
    const flags = detectEscalationFlags(body);
    expect(flags.map((f) => f.id)).toContain("inlineCredential");
    // The path slot carries the marker so the approver sees WHAT was masked.
    // The marker verbatim — no surrounding body characters ride along.
    expect(flags.find((f) => f.id === "inlineCredential")?.path).toBe("<REDACTED>");
  });

  it("does NOT flag a redacted vault reference — that is the correct way to pass a secret", () => {
    const body = JSON.stringify({ llm: { apiKey: "${vault:<REDACTED>}" } });
    expect(detectEscalationFlags(body)).toEqual([]);
  });

  it("flags a raw sk- key an older backend failed to redact, without echoing it", () => {
    const key = "sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx";
    const body = JSON.stringify({ llm: { apiKey: key } });
    const flags = detectEscalationFlags(body);
    const flag = flags.find((f) => f.id === "inlineCredential");
    expect(flag).toBeTruthy();
    // A FIXED label — not a slice of the match. This branch sees the credential
    // unredacted, so copying any of it into a rendered string defeats the check.
    expect(flag!.path).toBe("sk-…");
    // Nothing past the generic prefix survives: the key's own distinguishing
    // characters must appear nowhere in anything the warning renders.
    expect(flag!.path).not.toContain("ant");
    expect(flag!.path).not.toContain("CeIJ");
    expect(key).toContain("CeIJ");
  });

  it("labels a Bearer literal without echoing the token", () => {
    const flags = detectEscalationFlags('{"h": {"Authorization": "Bearer abcdefghij1234567890abcdef"}}');
    expect(flags.find((f) => f.id === "inlineCredential")?.path).toBe("Bearer …");
  });

  it("flags a Bearer token literal", () => {
    const flags = detectEscalationFlags('{"headers": {"Authorization": "Bearer abcdefghij1234567890abcdef"}}');
    expect(flags.map((f) => f.id)).toContain("inlineCredential");
  });

  it("scans non-JSON bodies too — a credential in a form post is still a credential", () => {
    const flags = detectEscalationFlags("api_key=sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7z");
    expect(flags.map((f) => f.id)).toContain("inlineCredential");
  });

  it("stays quiet on an ordinary body", () => {
    expect(detectEscalationFlags('{"name": "Test Agent", "description": "plain config"}')).toEqual([]);
  });

  it("composes with the setting checks — an ungated create with an embedded key raises both", () => {
    const body = JSON.stringify({
      agentName: "Test Agent",
      systemPrompt: "Be helpful.",
      llm: { apiKey: "sk-ant-<REDACTED>" },
    });
    const ids = detectEscalationFlags(body).map((f) => f.id).sort();
    expect(ids).toEqual(["agentCreatedWithoutGate", "inlineCredential"].sort());
  });
});

/**
 * The setting checks all sit behind a parse, and every body carrying a
 * credential-named field arrives from `SecretRedactionFilter` unparseable —
 * `"apiKey":"…"` comes back as `"apiKey=<REDACTED>"` (see `redacted-json.ts`).
 * So the checks were silently skipped on precisely the class of request that
 * most needs them, and the approver saw the credential warning alone. "No
 * second warning" reads as "no capability grant".
 */
describe("a body the redaction filter left unparseable", () => {
  it("still runs the setting checks — a credential AND a capability grant raise both", () => {
    const body = '{"name":"board","apiKey=<REDACTED>","dynamicAgents":{"enabled":true,"allowCreation":true}}';
    const ids = detectEscalationFlags(body).map((f) => f.id).sort();
    expect(ids).toEqual(["dynamicAgentCreation", "inlineCredential"].sort());
  });

  it("still sees an auto-approve timeout policy behind a mangled secret field", () => {
    const body = '{"name":"a","clientSecret=<REDACTED>","hitlConfig":{"timeoutPolicy":"AUTO_APPROVE"}}';
    expect(detectEscalationFlags(body).map((f) => f.id)).toContain("autoApproveOnTimeout");
  });

  it("still flags an agent created with no gate behind a mangled secret field", () => {
    const body = '{"agentName":"Refund helper","systemPrompt":"You help.","apiKey=<REDACTED>"}';
    expect(detectEscalationFlags(body).map((f) => f.id)).toContain("agentCreatedWithoutGate");
  });

  it("still runs them when the secret itself contained a comma", () => {
    // Reported on the PR: the filter's value class stops at a comma, so the
    // tail of the secret is left inside the string. An earlier version of the
    // repair stopped at that comma too, the body stayed unparseable, and this
    // grant went unwarned with only the credential flag to show for it.
    const body = '{"name":"board","password=<REDACTED>,rest","dynamicAgents":{"enabled":true,"allowCreation":true}}';
    const ids = detectEscalationFlags(body).map((f) => f.id).sort();
    expect(ids).toEqual(["dynamicAgentCreation", "inlineCredential"].sort());
  });

  it("keeps returning only the credential flag for a body that is genuinely not JSON", () => {
    // The repair explains one shape; it must not turn a form post into an object.
    const flags = detectEscalationFlags("api_key=sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7z");
    expect(flags.map((f) => f.id)).toEqual(["inlineCredential"]);
  });
});
