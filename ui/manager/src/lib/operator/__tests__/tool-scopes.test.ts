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
  });

  describe("write scope", () => {
    it("is empty until the approval gate ships", () => {
      expect(WRITE_ENDPOINTS).toEqual([]);
    });

    it("never contains a read verb", () => {
      // The gate classifies by HTTP method (GET exempt, everything else
      // required). A mutating GET in this list would sail through ungated.
      const looksLikeARead = WRITE_ENDPOINTS.filter((e) => e.startsWith("GET "));
      expect(looksLikeARead).toEqual([]);
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

    it("stays unavailable even with every fact true, while no write endpoints exist", () => {
      expect(isWriteScopeAvailable(allFacts())).toBe(false);
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

    it("grants no extra endpoints even if read_write is somehow requested", () => {
      expect(endpointsForScope("read_write")).toEqual([...READ_ENDPOINTS]);
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

    it("reports no write capability for read_write while the write list is empty", () => {
      // Pairs with the prompt test of the same invariant: the scope is an
      // intent, the resolved endpoint set is the fact.
      expect(grantsWriteCapability(endpointsForScope("read_write"))).toBe(false);
    });
  });
});
