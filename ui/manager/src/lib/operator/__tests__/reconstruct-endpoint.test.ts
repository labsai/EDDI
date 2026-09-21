import { describe, it, expect } from "vitest";
import { buildOperationIdIndex, reconstructEndpoint, resolveToolNameForEndpoint } from "../reconstruct-endpoint";
import type { FetchedSpec } from "@/lib/api/operator";

function spec(paths: FetchedSpec["paths"]): FetchedSpec {
  return { raw: { openapi: "3.1.0", paths }, paths };
}

describe("buildOperationIdIndex", () => {
  it("maps each operationId to its method and path, uppercasing the method", () => {
    const index = buildOperationIdIndex(
      spec({
        "/agentstore/agents": { post: { operationId: "createAgent" } },
        "/agentstore/agents/{id}": { get: { operationId: "readAgent" } },
      }),
    );
    expect(index).toEqual({
      createAgent: { method: "POST", path: "/agentstore/agents" },
      readAgent: { method: "GET", path: "/agentstore/agents/{id}" },
    });
  });

  it("mirrors the backend's own lookup — reading operationId is not a guess", () => {
    // McpApiToolBuilder.buildApiCall names a generated tool
    // operation.getOperationId(); this indexes the identical field from the
    // identical spec, so a hit here is exactly the name the backend produced.
    const index = buildOperationIdIndex(
      spec({ "/administration/{environment}/deploy/{agentId}": { post: { operationId: "deployAgent" } } }),
    );
    expect(index.deployAgent).toEqual({
      method: "POST",
      path: "/administration/{environment}/deploy/{agentId}",
    });
  });

  it("indexes every method under a path with multiple operations", () => {
    const index = buildOperationIdIndex(
      spec({
        "/agentstore/agents/{id}": {
          get: { operationId: "readAgent" },
          put: { operationId: "updateAgent" },
          delete: { operationId: "deleteAgent" },
        },
      }),
    );
    expect(Object.keys(index).sort()).toEqual(["deleteAgent", "readAgent", "updateAgent"]);
  });

  it("skips operations with no operationId — the backend's slug fallback is not reconstructable this way", () => {
    const index = buildOperationIdIndex(spec({ "/some/path": { get: {} } }));
    expect(index).toEqual({});
  });

  it("tolerates a malformed spec (no paths, non-object operation) without throwing", () => {
    expect(buildOperationIdIndex({ raw: {}, paths: {} })).toEqual({});
    expect(() =>
      buildOperationIdIndex(spec({ "/x": null as unknown as Record<string, unknown> })),
    ).not.toThrow();
  });
});

describe("reconstructEndpoint", () => {
  it("resolves a known tool name", () => {
    const index = buildOperationIdIndex(spec({ "/agentstore/agents": { post: { operationId: "createAgent" } } }));
    expect(reconstructEndpoint("createAgent", index)).toEqual({ method: "POST", path: "/agentstore/agents" });
  });

  it("returns null rather than a guess for an unmatched tool name", () => {
    // An approver must never be shown a fabricated "this is what it calls" —
    // this covers the backend's slug fallback and non-HTTP tool sources (mcp).
    const index = buildOperationIdIndex(spec({ "/agentstore/agents": { post: { operationId: "createAgent" } } }));
    expect(reconstructEndpoint("sendEmail", index)).toBeNull();
  });
});

describe("resolveToolNameForEndpoint", () => {
  it("finds the tool name for a known allow-list entry", () => {
    const index = buildOperationIdIndex(
      spec({ "/descriptorstore/descriptors/{id}": { patch: { operationId: "patchDescriptor" } } }),
    );
    expect(resolveToolNameForEndpoint("PATCH /descriptorstore/descriptors/{id}", index)).toBe("patchDescriptor");
  });

  it("is the exact inverse of buildOperationIdIndex — round-trips both ways", () => {
    const index = buildOperationIdIndex(
      spec({ "/administration/{environment}/deploy/{agentId}": { post: { operationId: "deployAgent" } } }),
    );
    const endpoint = reconstructEndpoint("deployAgent", index)!;
    expect(resolveToolNameForEndpoint(`${endpoint.method} ${endpoint.path}`, index)).toBe("deployAgent");
  });

  it("returns null for an endpoint the spec does not expose", () => {
    const index = buildOperationIdIndex(spec({ "/agentstore/agents": { post: { operationId: "createAgent" } } }));
    expect(resolveToolNameForEndpoint("PATCH /descriptorstore/descriptors/{id}", index)).toBeNull();
  });

  it("returns null for a malformed 'METHOD /path' string rather than throwing", () => {
    const index = buildOperationIdIndex(spec({ "/x": { get: { operationId: "readX" } } }));
    expect(resolveToolNameForEndpoint("not-an-endpoint", index)).toBeNull();
  });

  it("distinguishes method — GET and PATCH on the same path resolve to different tools", () => {
    const index = buildOperationIdIndex(
      spec({
        "/descriptorstore/descriptors/{id}": {
          get: { operationId: "readDescriptor" },
          patch: { operationId: "patchDescriptor" },
        },
      }),
    );
    expect(resolveToolNameForEndpoint("GET /descriptorstore/descriptors/{id}", index)).toBe("readDescriptor");
    expect(resolveToolNameForEndpoint("PATCH /descriptorstore/descriptors/{id}", index)).toBe("patchDescriptor");
  });
});
