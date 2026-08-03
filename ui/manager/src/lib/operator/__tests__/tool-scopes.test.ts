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
  });

  describe("write scope", () => {
    it("is exactly the curated entries — narrow verbs, not a resource-level grant", () => {
      // Pinned deliberately, not just "non-empty": each entry is chosen so an
      // approved-but-wrong call is small and reversible (see the doc comment on
      // WRITE_ENDPOINTS for why each one, and why not PUT /agentstore/agents,
      // schedule creation, or any DELETE). A silent addition here is exactly as
      // dangerous as a silent removal from an allow-list — this test catches
      // either direction.
      expect(WRITE_ENDPOINTS).toEqual([
        "PATCH /descriptorstore/descriptors/{id}",
        "POST /administration/{environment}/deploy/{agentId}",
        "POST /administration/{environment}/undeploy/{agentId}",
        "POST /schedulestore/schedules/{scheduleId}/disable",
        "POST /groupstore/groups",
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

    it("binds no agent-authoring endpoint — the handoff to the wizard is structural", () => {
      // The system prompt tells the operator to send the user to the agent
      // wizard. That instruction is only honest because there is no tool it
      // could reach for instead; a prompt is not what enforces this.
      const agentAuthoring = WRITE_ENDPOINTS.filter(
        (e) => e.includes("/agentstore/") || e.includes("/administration/agents/setup"),
      );
      expect(agentAuthoring).toEqual([]);
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
      "PUT /agentstore/agents/{id}",
      "POST /agentstore/agents",
      "POST /llmstore/llms",
      "PUT /llmstore/llms/{id}",
      "POST /schedulestore/schedules",
      "POST /schedulestore/schedules/{scheduleId}/enable",
      "POST /schedulestore/schedules/{scheduleId}/fire",
      // The two composite create endpoints: one call provisions an agent with an
      // arbitrary `endpoints` filter and no gate — a complete escape from this
      // allow-list — and their request bodies carry a raw provider API key.
      "POST /administration/agents/setup",
      "POST /administration/agents/setup-api",
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
});
