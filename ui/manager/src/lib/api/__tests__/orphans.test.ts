import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { scanOrphans, purgeOrphans } from "../orphans";

describe("orphans API", () => {
  describe("scanOrphans", () => {
    it("scans for orphans with default includeDeleted=false", async () => {
      const result = await scanOrphans();
      expect(result).toBeDefined();
    });

    it("scans with includeDeleted=true", async () => {
      const result = await scanOrphans(true);
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/administration/orphans", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(scanOrphans()).rejects.toMatchObject({ status: 500 });
    });
  });

  describe("purgeOrphans", () => {
    it("purges orphans with default includeDeleted=true", async () => {
      const result = await purgeOrphans();
      expect(result).toBeDefined();
    });

    it("purges with includeDeleted=false", async () => {
      const result = await purgeOrphans(false);
      expect(result).toBeDefined();
    });
  });
});
