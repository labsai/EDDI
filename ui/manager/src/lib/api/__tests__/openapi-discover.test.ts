import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { discoverEndpoints, LiteralCredentialError } from "../openapi-discover";

const ENDPOINT = "*/apicallstore/apicalls/discover-endpoints";

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

    it("passes apiBaseUrl and a credential reference", async () => {
      // Was "passes apiBaseUrl and apiAuth params", with a literal
      // `Bearer token123` and both values in the query string. EDDI removed the
      // parameter: the reference goes in the body, and a literal is refused.
      const result = await discoverEndpoints(
        "https://petstore.example.com/openapi.json",
        "https://custom.base.com",
        "${vault:petstore-key}"
      );
      expect(result).toBeDefined();
    });

    it("omits apiBaseUrl and authHeaderRef when empty", async () => {
      const result = await discoverEndpoints(
        "https://petstore.example.com/openapi.json",
        "",
        ""
      );
      expect(result).toBeDefined();
    });

    it("refuses a literal credential without contacting the backend", async () => {
      // Not a 400 round trip. The reason the parameter left the URL is that a
      // credential is logged by every hop before EDDI sees it, so a request
      // sent to be rejected would leak it exactly as before.
      let called = false;
      server.use(
        http.post(ENDPOINT, () => {
          called = true;
          return HttpResponse.json({}, { status: 400 });
        })
      );

      await expect(
        discoverEndpoints("https://petstore.example.com/openapi.json", "", "Bearer token123")
      ).rejects.toBeInstanceOf(LiteralCredentialError);
      expect(called).toBe(false);
    });

    it("handles missing specUrl (400 from backend)", async () => {
      server.use(
        http.post(ENDPOINT, () =>
          HttpResponse.json(
            { error: "a request body with a 'specUrl' is required" },
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
