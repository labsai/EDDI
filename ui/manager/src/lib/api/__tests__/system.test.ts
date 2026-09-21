import { describe, it, expect, vi } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { getEddiVersion } from "../system";

describe("system API", () => {
  describe("getEddiVersion", () => {
    it("returns version from OpenAPI spec", async () => {
      const result = await getEddiVersion();
      expect(result).toBe("6.0.0-demo");
    });

    it("returns 'Unknown' when info.version is missing", async () => {
      server.use(
        http.get("*/openapi", () =>
          HttpResponse.json({ info: {} })
        )
      );
      const result = await getEddiVersion();
      expect(result).toBe("Unknown");
    });

    it("returns 'Unknown' when info is missing", async () => {
      server.use(
        http.get("*/openapi", () =>
          HttpResponse.json({})
        )
      );
      const result = await getEddiVersion();
      expect(result).toBe("Unknown");
    });

    it("returns 'Unknown' when API call fails", async () => {
      server.use(
        http.get("*/openapi", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      const result = await getEddiVersion();
      expect(result).toBe("Unknown");
      consoleSpy.mockRestore();
    });
  });
});
