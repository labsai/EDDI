import { describe, it, expect } from "vitest";
import { parseResourceUri } from "@/lib/api/backup";

describe("parseResourceUri", () => {
  it("parses a full EDDI resource URI with version", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/abc123?version=3"
    );
    expect(result).toEqual({ id: "abc123", version: 3 });
  });

  it("parses a resource URI without version", () => {
    const result = parseResourceUri(
      "eddi://ai.labs.agent/agentstore/agents/xyz789"
    );
    expect(result).toEqual({ id: "xyz789", version: null });
  });

  it("parses a simple path-like resource", () => {
    const result = parseResourceUri("/agentstore/agents/myagent");
    expect(result).toEqual({ id: "myagent", version: null });
  });

  it("parses a bare ID", () => {
    const result = parseResourceUri("simple-id");
    expect(result).toEqual({ id: "simple-id", version: null });
  });

  it("parses version=0 correctly", () => {
    const result = parseResourceUri("/store/items/item1?version=0");
    expect(result).toEqual({ id: "item1", version: 0 });
  });
});
