import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  readProperties,
  mergeProperties,
  deleteProperties,
} from "../properties";

describe("properties API", () => {
  describe("readProperties", () => {
    it("reads properties for a user — raw values, not Property wrappers", async () => {
      const result = await readProperties("user1");
      expect(result.user_name).toBe("Jane Doe");
      expect(result.age).toBe(32);
    });

    it("resolves {} on the backend's 204 for a user with no properties", async () => {
      // TanStack Query v5 fails a query that resolves undefined, so the raw
      // empty body rendered the page's error state.
      server.use(
        http.get("*/propertiesstore/properties/:userId", () => new HttpResponse(null, { status: 204 })),
      );
      await expect(readProperties("nobody")).resolves.toEqual({});
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/propertiesstore/properties/:userId", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(readProperties("user-fail")).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  describe("mergeProperties", () => {
    it("merges properties for a user", async () => {
      await expect(
        mergeProperties("user1", { lang: "en" })
      ).resolves.toBeUndefined();
    });
  });

  describe("deleteProperties", () => {
    it("deletes properties for a user", async () => {
      await expect(deleteProperties("user1")).resolves.toBeUndefined();
    });
  });
});
