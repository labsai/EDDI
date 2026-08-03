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
