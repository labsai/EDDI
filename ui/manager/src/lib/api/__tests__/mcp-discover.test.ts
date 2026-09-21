import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { discoverMcpTools } from "../mcp-discover";

const ENDPOINT = "*/mcpcallsstore/mcpcalls/discover-tools";

describe("mcp-discover API", () => {
  describe("discoverMcpTools", () => {
    it("discovers MCP tools with defaults", async () => {
      const result = await discoverMcpTools("http://mcp.example.com");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("tools");
      expect(result).toHaveProperty("count");
    });

    it("passes transport and the probed server's key", async () => {
      // The key used to travel as `?apiKey=`, which EDDI now rejects by name.
      // It goes in a header; `discovery-credentials.test.ts` pins where.
      const result = await discoverMcpTools(
        "http://mcp.example.com",
        "sse",
        "my-api-key"
      );
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.post(ENDPOINT, () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(
        discoverMcpTools("http://bad.example.com")
      ).rejects.toMatchObject({ status: 500 });
    });
  });
});
