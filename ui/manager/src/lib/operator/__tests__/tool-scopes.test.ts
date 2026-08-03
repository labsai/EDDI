import { describe, it, expect } from "vitest";
import {
  READ_ENDPOINTS,
  WRITE_ENDPOINTS,
  endpointsForScope,
  buildEndpointFilter,
  buildToolApprovals,
  parseEndpoint,
  isWriteScopeAvailable,
  grantsWriteCapability,
  grantsAgentCreation,
  grantsAgentModification,
  type WriteScopeFacts,
} from "../tool-scopes";

function allFacts(overrides: Partial<WriteScopeFacts> = {}): WriteScopeFacts {
  return {
    backendAcceptsHitlConfig: true,
    gateVerifiedOnEveryVersion: true,
    authMode: "caller-identity",
    approvalSurfaceMounted: true,
    ...overrides,
  };
}

describe("tool-scopes", () => {
  describe("the allow-list itself", () => {
    it("only contains GET endpoints", () => {
      const nonRead = READ_ENDPOINTS.filter((e) => !e.startsWith("GET "));
      expect(nonRead).toEqual([]);
    });

    it("contains only well-formed 'METHOD /path' entries", () => {
      for (const entry of READ_ENDPOINTS) {
        expect(parseEndpoint(entry), `malformed: ${entry}`).not.toBeNull();
      }
    });

    it("has no duplicates", () => {
      expect(new Set(READ_ENDPOINTS).size).toBe(READ_ENDPOINTS.length);
    });

    it("keeps OpenAPI path templates intact", () => {
      // The backend matches the filter against spec path templates verbatim, so
      // a substituted value (e.g. /agents/123) would silently bind no tool.
      expect(READ_ENDPOINTS).toContain("GET /agentstore/agents/{id}");

      // No entry may carry a concrete id where the spec has a placeholder.
      const looksSubstituted = READ_ENDPOINTS.filter((e) =>
        /\/(\d+|[0-9a-f]{18,})(\/|$)/.test(e),
      );
      expect(looksSubstituted).toEqual([]);
    });

    it("includes the by-id and status reads its starter prompts need", () => {
      // Descriptors alone cannot diagnose a failing deployment.
      expect(READ_ENDPOINTS).toContain(
        "GET /administration/{environment}/deploymentstatus/{agentId}",
      );
      expect(READ_ENDPOINTS).toContain("GET /administration/logs");
    });

    it("can see the schedule it might later be asked to disable", () => {
      // WRITE_ENDPOINTS can bind /disable without this — but an operator that
      // cannot list schedules would recommend disabling one it cannot name.
      expect(READ_ENDPOINTS).toContain("GET /schedulestore/schedules");
    });

    it("can read a workflow's step list — the only way to learn an extension's id and version", () => {
      // Every workflow-extension URI (e.g. eddi://ai.labs.llm/llmstore/llms/{id}?version=N)
      // is only ever discovered by reading the workflow that references it; every
      // by-id read below requires a version, so without this no authoring read
      // or write is reachable at all.
      expect(READ_ENDPOINTS).toContain("GET /workflowstore/workflows/{id}");
    });

    it("can read a specific group in detail, not just its descriptor", () => {
      expect(READ_ENDPOINTS).toContain("GET /groupstore/groups/{id}");
    });

    it("has a by-id read for every workflow-extension store it can also write", () => {
      // A PUT requires the resource's current version; a POST that duplicates
      // or extends one requires reading it first. Write access without a
      // matching read would be unusable, not just incomplete.
      const writeStores = WRITE_ENDPOINTS.filter((e) => e.startsWith("PUT /")).map(
        (e) => e.replace(/^PUT \//, "").replace(/\/\{id\}$/, ""),
      );
      for (const store of writeStores) {
        expect(READ_ENDPOINTS, `missing a by-id read for ${store}`).toContain(`GET /${store}/{id}`);
      }
    });
  });

  describe("write scope", () => {
    it("is exactly the curated entries — narrow verbs, not a resource-level grant", () => {
      // Pinned deliberately, not just "non-empty": each entry is chosen so an
      // approved-but-wrong call is small and reversible, or (the authoring
      // entries) cannot touch the document that gates it (see the doc comment
      // on WRITE_ENDPOINTS for why each one, and why not PUT /agentstore/agents,
      // PUT /groupstore/groups/{id}, schedule creation, or any DELETE). A silent
      // addition here is exactly as dangerous as a silent removal from an
      // allow-list — this test catches either direction.
      expect(WRITE_ENDPOINTS).toEqual([
        "PATCH /descriptorstore/descriptors/{id}",
        "POST /administration/{environment}/deploy/{agentId}",
        "POST /administration/{environment}/undeploy/{agentId}",
        "POST /schedulestore/schedules/{scheduleId}/disable",
        "POST /groupstore/groups",
        "POST /administration/agents/setup",
        "POST /administration/agents/setup-api",
        "PUT /workflowstore/workflows/{id}",
        "POST /workflowstore/workflows",
        "PUT /rulestore/rulesets/{id}",
        "POST /rulestore/rulesets",
        "PUT /outputstore/outputsets/{id}",
        "POST /outputstore/outputsets",
        "PUT /propertysetterstore/propertysetters/{id}",
        "POST /propertysetterstore/propertysetters",
        "PUT /dictionarystore/dictionaries/{id}",
        "POST /dictionarystore/dictionaries",
        "PUT /apicallstore/apicalls/{id}",
        "POST /apicallstore/apicalls",
        "PUT /mcpcallsstore/mcpcalls/{id}",
        "POST /mcpcallsstore/mcpcalls",
      ]);
    });

    it("can create a group but never update, duplicate or delete one", () => {
      // Create is the only group verb where the generated tool's whole-document
      // body is reviewable: there is no prior version, so the approver reads the
      // document rather than diffing one they cannot see.
      expect(WRITE_ENDPOINTS).toContain("POST /groupstore/groups");
      expect(WRITE_ENDPOINTS).not.toContain("PUT /groupstore/groups/{id}");
      expect(WRITE_ENDPOINTS).not.toContain("POST /groupstore/groups/{id}");
      expect(WRITE_ENDPOINTS).not.toContain("DELETE /groupstore/groups/{id}");
    });

    it("can create a whole new agent (both shapes), but never touch an existing agent's own document", () => {
      // setup and setup-api build a new AgentConfiguration from nothing, so
      // "does this body carry a real gate" needs no prior version to compare
      // against — escalation-flags.ts's agentCreatedWithoutGate answers it. A
      // PUT to an existing agent has no such answer available (see the doc
      // comment on WRITE_ENDPOINTS), so that stays out categorically.
      expect(WRITE_ENDPOINTS).toContain("POST /administration/agents/setup");
      expect(WRITE_ENDPOINTS).toContain("POST /administration/agents/setup-api");
      const agentDocumentWrites = WRITE_ENDPOINTS.filter((e) => e.includes("/agentstore/"));
      expect(agentDocumentWrites).toEqual([]);
    });

    it("authors every workflow-extension store that cannot carry a gate", () => {
      // "Modify an agent" in practice: behavior rules, outputs, slot-filling,
      // dictionaries, HTTP and MCP tool wiring, and the pipeline that runs
      // them. Every one of these documents was checked field-by-field against
      // the backend model — none carries a hitlConfig or any other gate field.
      for (const store of [
        "rulestore/rulesets",
        "outputstore/outputsets",
        "propertysetterstore/propertysetters",
        "dictionarystore/dictionaries",
        "apicallstore/apicalls",
        "mcpcallsstore/mcpcalls",
        "workflowstore/workflows",
      ]) {
        expect(WRITE_ENDPOINTS).toContain(`PUT /${store}/{id}`);
        expect(WRITE_ENDPOINTS).toContain(`POST /${store}`);
      }
    });

    it("never grants an llmstore WRITE — it carries a gate that fully replaces the agent's", () => {
      // The load-bearing exclusion. LlmConfiguration.Task.toolApprovals fully
      // REPLACES the agent-level hitlConfig.toolApprovals (LlmTask.java:
      // task.getToolApprovals() != null ? task.getToolApprovals() : <agent>).
      // So a granted llmstore PUT would let the operator propose a write to its
      // OWN config setting requireApproval: [] — one approved, near-unreviewable
      // whole-document write and every future write executes ungated. Same class
      // of risk as PUT /agentstore/agents/{id}, excluded for the same reason.
      expect(WRITE_ENDPOINTS).not.toContain("PUT /llmstore/llms/{id}");
      expect(WRITE_ENDPOINTS).not.toContain("POST /llmstore/llms");
      expect(WRITE_ENDPOINTS.filter((e) => e.includes("/llmstore/"))).toEqual([]);
    });

    it("still READS llmstore, so it can show a prompt it cannot change", () => {
      // Excluding the write must not blind it: "what is this agent's prompt?"
      // stays answerable, and the handoff to the manager UI is only useful if
      // the operator can first show the user what is there now.
      expect(READ_ENDPOINTS).toContain("GET /llmstore/llms/{id}");
    });

    it("never contains a read verb", () => {
      // The gate classifies by HTTP method (GET exempt, everything else
      // required). A mutating GET in this list would sail through ungated.
      const looksLikeARead = WRITE_ENDPOINTS.filter((e) => e.startsWith("GET "));
      expect(looksLikeARead).toEqual([]);
    });

    it("never contains DELETE — no undo exists in any of these stores", () => {
      expect(WRITE_ENDPOINTS.filter((e) => e.startsWith("DELETE "))).toEqual([]);
    });

    it("deploy is paired with undeploy — no rollback would be worse than useless", () => {
      expect(WRITE_ENDPOINTS).toContain("POST /administration/{environment}/deploy/{agentId}");
      expect(WRITE_ENDPOINTS).toContain("POST /administration/{environment}/undeploy/{agentId}");
    });

    it.each([
      // The one pair of documents anywhere in this list that carry their own
      // gate — see the doc comment on WRITE_ENDPOINTS for why a create can be
      // checked (escalation-flags.ts) but a full-document update cannot.
      "PUT /agentstore/agents/{id}",
      "POST /agentstore/agents",
      "PUT /groupstore/groups/{id}",
      "POST /groupstore/groups/{id}",
      // Attacker persistence: a scheduled turn has no human present, so an
      // approval prompt covering these would never actually appear.
      "POST /schedulestore/schedules",
      "POST /schedulestore/schedules/{scheduleId}/enable",
      "POST /schedulestore/schedules/{scheduleId}/fire",
    ])("excludes %s — a full-document write or attacker persistence", (excluded) => {
      expect(WRITE_ENDPOINTS).not.toContain(excluded);
    });

    // The approval seam: writes must be unreachable, not merely discouraged.
    it("is unavailable with no verified facts", () => {
      expect(
        isWriteScopeAvailable({
          backendAcceptsHitlConfig: false,
          gateVerifiedOnEveryVersion: false,
          authMode: "none",
          approvalSurfaceMounted: false,
        }),
      ).toBe(false);
    });

    it("becomes available once every fact holds — the seam actually opens, not just closes", () => {
      // The mirror of every "stays unavailable" test below: this is the one
      // proving the mechanism WORKS, not just that it fails safe. A regression
      // that made writes permanently unreachable would pass every other test in
      // this block while silently breaking the feature.
      expect(isWriteScopeAvailable(allFacts())).toBe(true);
    });

    it.each([
      ["backendAcceptsHitlConfig", { backendAcceptsHitlConfig: false }],
      ["gateVerifiedOnEveryVersion", { gateVerifiedOnEveryVersion: false }],
      ["approvalSurfaceMounted", { approvalSurfaceMounted: false }],
    ] as const)("stays unavailable when only %s is false", (_name, override) => {
      expect(isWriteScopeAvailable(allFacts(override))).toBe(false);
    });

    it("stays unavailable when authMode is 'none', even if every other fact holds", () => {
      // 'none' cannot support attributed approval decisions or self-approval
      // prevention, so it must never be treated as good enough on its own.
      expect(isWriteScopeAvailable(allFacts({ authMode: "none" }))).toBe(false);
    });

    it("read_write grants exactly READ_ENDPOINTS plus WRITE_ENDPOINTS, in that order", () => {
      expect(endpointsForScope("read_write")).toEqual([...READ_ENDPOINTS, ...WRITE_ENDPOINTS]);
    });

    it("read_only grants no write endpoint, however isWriteScopeAvailable resolves", () => {
      // isWriteScopeAvailable gates OFFERING read_write; it must never leak into
      // what read_only itself is provisioned with.
      for (const write of WRITE_ENDPOINTS) {
        expect(endpointsForScope("read_only")).not.toContain(write);
      }
    });
  });

  describe("buildToolApprovals", () => {
    it("gates every write method and exempts reads", () => {
      const config = buildToolApprovals();
      expect(config.requireApproval).toEqual(
        expect.arrayContaining(["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"]),
      );
      expect(config.exempt).toEqual(["http.get:*"]);
    });

    it("never allows AUTO_APPROVE", () => {
      expect(buildToolApprovals().timeoutPolicy).toBe("WAIT_INDEFINITELY");
    });

    it("is the same shape whatever the caller asks for — read_write reuses it unchanged", () => {
      // buildToolApprovals takes no scope parameter on purpose: the gate must not
      // need updating the day WRITE_ENDPOINTS stops being empty.
      expect(buildToolApprovals()).toEqual(buildToolApprovals());
    });
  });

  describe("buildEndpointFilter", () => {
    it("produces the comma-separated wire format the backend splits on", () => {
      const filter = buildEndpointFilter("read_only");
      expect(filter.split(", ")).toEqual([...READ_ENDPOINTS]);
    });

    it("names every allow-listed endpoint", () => {
      const filter = buildEndpointFilter("read_only");
      for (const entry of READ_ENDPOINTS) {
        expect(filter).toContain(entry);
      }
    });
  });

  describe("parseEndpoint", () => {
    it("splits method and path", () => {
      expect(parseEndpoint("GET /administration/logs")).toEqual({
        method: "GET",
        path: "/administration/logs",
      });
    });

    it("keeps parameter placeholders", () => {
      expect(parseEndpoint("GET /auditstore/agent/{agentId}")?.path).toBe(
        "/auditstore/agent/{agentId}",
      );
    });

    it("rejects malformed entries", () => {
      expect(parseEndpoint("/no-method")).toBeNull();
      expect(parseEndpoint("get /lowercase-method")).toBeNull();
      expect(parseEndpoint("GET no-leading-slash")).toBeNull();
    });
  });

  describe("grantsWriteCapability", () => {
    it("is false for a set of reads", () => {
      expect(grantsWriteCapability(READ_ENDPOINTS)).toBe(false);
    });

    it("is false for an empty set", () => {
      expect(grantsWriteCapability([])).toBe(false);
    });

    it.each(["POST", "PUT", "PATCH", "DELETE"])("is true for a single %s", (method) => {
      expect(grantsWriteCapability([...READ_ENDPOINTS, `${method} /agentstore/agents`])).toBe(true);
    });

    it("fails safe on an entry it cannot parse", () => {
      // An unparseable entry cannot be shown to be a read, so it counts as a
      // write. Being needlessly cautious is recoverable; describing an agent as
      // read-only while it holds a write tool is not.
      expect(grantsWriteCapability(["garbage"])).toBe(true);
      expect(grantsWriteCapability(["get /lowercase"])).toBe(true);
    });

    it("fails safe on a method nobody updated it for", () => {
      expect(grantsWriteCapability(["PURGE /somewhere"])).toBe(true);
    });

    it("agrees with the resolved read_write endpoint set now that it grants writes", () => {
      // Pairs with the prompt test of the same invariant: the scope is an
      // intent, the resolved endpoint set is the fact — and now that
      // WRITE_ENDPOINTS is populated, the fact for read_write is "yes".
      expect(grantsWriteCapability(endpointsForScope("read_write"))).toBe(true);
    });

    it("still reports no write capability for read_only", () => {
      expect(grantsWriteCapability(endpointsForScope("read_only"))).toBe(false);
    });
  });

  describe("grantsAgentCreation", () => {
    it("is true once either creation endpoint is granted", () => {
      expect(grantsAgentCreation(["POST /administration/agents/setup"])).toBe(true);
      expect(grantsAgentCreation(["POST /administration/agents/setup-api"])).toBe(true);
    });

    it("is false for an unrelated write, including a deploy", () => {
      expect(
        grantsAgentCreation(["POST /administration/production/deploy/{agentId}"]),
      ).toBe(false);
    });

    it("does not match on a substring of the administration path", () => {
      // /administration/ also holds deploy, undeploy, logs, and quotas.
      expect(grantsAgentCreation(["GET /administration/logs"])).toBe(false);
      expect(grantsAgentCreation(["GET /administration/quotas"])).toBe(false);
    });

    it("agrees with the real read_write endpoint set", () => {
      expect(grantsAgentCreation(endpointsForScope("read_write"))).toBe(true);
      expect(grantsAgentCreation(endpointsForScope("read_only"))).toBe(false);
    });
  });

  describe("grantsAgentModification", () => {
    it("is true once any writable workflow-extension store's update verb is granted", () => {
      for (const entry of [
        "PUT /workflowstore/workflows/{id}",
        "PUT /rulestore/rulesets/{id}",
        "PUT /outputstore/outputsets/{id}",
        "PUT /propertysetterstore/propertysetters/{id}",
        "PUT /dictionarystore/dictionaries/{id}",
        "PUT /apicallstore/apicalls/{id}",
        "PUT /mcpcallsstore/mcpcalls/{id}",
      ]) {
        expect(grantsAgentModification([entry]), entry).toBe(true);
      }
    });

    it("is false for an llmstore write, which is never granted and never ordinary modify", () => {
      // Reads the writable list, not the full read list — so a hypothetical
      // llmstore grant could never be reported as routine modify capability.
      expect(grantsAgentModification(["PUT /llmstore/llms/{id}"])).toBe(false);
    });

    it("is false for the corresponding create verb alone — creating is not modifying", () => {
      expect(grantsAgentModification(["POST /rulestore/rulesets"])).toBe(false);
    });

    it("is false for an unrelated write, including a deploy", () => {
      expect(
        grantsAgentModification(["POST /administration/production/deploy/{agentId}"]),
      ).toBe(false);
    });

    it("agrees with the real read_write endpoint set", () => {
      expect(grantsAgentModification(endpointsForScope("read_write"))).toBe(true);
      expect(grantsAgentModification(endpointsForScope("read_only"))).toBe(false);
    });
  });
});
