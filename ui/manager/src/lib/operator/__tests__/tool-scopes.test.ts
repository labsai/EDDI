import { describe, it, expect } from "vitest";
import {
  READ_ENDPOINTS,
  WRITE_ENDPOINTS,
  endpointsForScope,
  buildEndpointFilter,
  parseEndpoint,
  isWriteScopeAvailable,
} from "../tool-scopes";

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

    // The approval seam: writes must be unreachable, not merely discouraged.
    it("is unavailable without an approval handler", () => {
      expect(isWriteScopeAvailable(false)).toBe(false);
    });

    it("stays unavailable even with a handler while no write endpoints exist", () => {
      expect(isWriteScopeAvailable(true)).toBe(false);
    });

    it("grants no extra endpoints even if read_write is somehow requested", () => {
      expect(endpointsForScope("read_write")).toEqual([...READ_ENDPOINTS]);
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
});
