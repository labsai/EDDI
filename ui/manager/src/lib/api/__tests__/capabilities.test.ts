import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { searchBySkill, listSkills } from "../capabilities";

describe("capabilities API", () => {
  describe("searchBySkill", () => {
    it("searches by skill with default strategy", async () => {
      const result = await searchBySkill("customer-support");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("searches by skill with custom strategy", async () => {
      const result = await searchBySkill("customer-support", "all_matches");
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/capabilities", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(searchBySkill("fail")).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  describe("listSkills", () => {
    it("lists all available skills", async () => {
      const result = await listSkills();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("returns empty array when API returns non-array", async () => {
      server.use(
        http.get("*/capabilities/skills", () =>
          HttpResponse.json("not-an-array")
        )
      );
      const result = await listSkills();
      expect(result).toEqual([]);
    });
  });
});
