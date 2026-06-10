import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { discoverEndpoints } from "../openapi-discover";

describe("openapi-discover API", () => {
  describe("discoverEndpoints", () => {
    it("discovers endpoints from an OpenAPI spec", async () => {
      const result = await discoverEndpoints(
        "https://petstore.example.com/openapi.json"
      );
      expect(result).toBeDefined();
      expect(result.title).toBe("Petstore API");
      expect(result.endpointCount).toBe(5);
      expect(result.groups).toHaveProperty("pets");
    });

    it("passes apiBaseUrl and apiAuth params", async () => {
      const result = await discoverEndpoints(
        "https://petstore.example.com/openapi.json",
        "https://custom.base.com",
        "Bearer token123"
      );
      expect(result).toBeDefined();
    });

    it("omits apiBaseUrl and apiAuth when empty", async () => {
      const result = await discoverEndpoints(
        "https://petstore.example.com/openapi.json",
        "",
        ""
      );
      expect(result).toBeDefined();
    });

    it("handles missing specUrl (400 from backend)", async () => {
      server.use(
        http.get("*/apicallstore/apicalls/discover-endpoints", () =>
          HttpResponse.json(
            { error: "specUrl query parameter is required" },
            { status: 400 }
          )
        )
      );
      await expect(discoverEndpoints("")).rejects.toMatchObject({
        status: 400,
      });
    });
  });
});
